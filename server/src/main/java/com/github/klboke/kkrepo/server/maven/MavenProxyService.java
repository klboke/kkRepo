package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Maven proxy facet — caches upstream content into our own blob store and serves it back.
 * <p>
 * Caching rules:
 * <ul>
 *   <li>Metadata paths use {@code metadataMaxAgeMinutes}; everything else uses
 *       {@code contentMaxAgeMinutes}. {@code -1} disables age-based revalidation.</li>
 *   <li>Fresh cache hit → served from blob store with no network call.</li>
 *   <li>Stale or missing → conditional GET upstream (If-None-Match + If-Modified-Since from the
 *       cached blob's stored {@code remoteEtag} / {@code remoteLastModified} attributes).</li>
 *   <li>304 Not Modified → bump asset {@code last_updated_at} so it goes hot again, serve cached.</li>
 *   <li>200 OK → stream upstream body straight to S3 via {@link MavenAssetWriter}, persist new
 *       blob + remote ETag/LM; we skip generating local checksum siblings because Maven clients
 *       request those URLs directly (and we want each cached as its own asset).</li>
 *   <li>404/410 → don't auto-block (legitimate miss); if we had cached, drop it; respond 404.</li>
 *   <li>Other failures → {@link ProxyStateDao#recordFailure}; if {@code autoBlock} and we have a
 *       cached copy, serve stale; otherwise 502.</li>
 *   <li>Circuit breaker open (blocked_until &gt; now) → never call upstream; serve stale or 502.</li>
 * </ul>
 * Backoff schedule for repeated failures: 30s → 5min → 30min cap.
 */
@Service
public class MavenProxyService {
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};

  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final MavenAssetWriter writer;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;
  private final MavenPathParser parser = new MavenPathParser();

  @Autowired
  public MavenProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      MavenAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.proxyStateDao = proxyStateDao;
    this.fetcher = fetcher;
    this.negativeCache = negativeCache;
    this.assetMetadataCache = assetMetadataCache;
    this.cacheController = cacheController;
  }

  public MavenProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      MavenAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache) {
    this(assetDao, blobStorageRegistry, writer, proxyStateDao, fetcher, negativeCache, assetMetadataCache, null);
  }

  public MavenResponse get(RepositoryRuntime runtime, MavenPath path, boolean headOnly) {
    if (!runtime.isProxy()) {
      throw new IllegalStateException("MavenProxyService.get called on non-proxy " + runtime.name());
    }
    // Maven artifacts always have a file name. Trailing-slash / directory-shaped requests
    // would otherwise round-trip to upstream and (for many Maven mirrors) come back as an HTML
    // index page that we'd then happily cache as an "asset". Stop them at the door.
    if (path.path().endsWith("/") || path.fileName().isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    Optional<CachedAssetMetadata> cached = assetMetadataCache.find(
        runtime.id(),
        path.path(),
        () -> AssetMetadataCache.Loaded.from(
            assetDao.findAssetByPath(runtime.id(), path.path()), assetDao));
    Instant now = Instant.now();
    int ttl = ttlMinutes(runtime, path);
    boolean fresh = cached.isPresent() && isFresh(runtime, path, cached.get(), ttl, now);
    if (fresh) {
      return serveCached(cached.get(), headOnly, path);
    }
    if (negativeCache != null && negativeCache.isNotFoundCached(runtime, path)) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return serveCached(cached.get(), headOnly, path);
      throw new MavenExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    return fetchAndCache(runtime, path, cached, headOnly, now);
  }

  private MavenResponse fetchAndCache(RepositoryRuntime runtime, MavenPath path,
      Optional<CachedAssetMetadata> cached, boolean headOnly, Instant now) {
    String url = buildRemoteUrl(runtime.proxyRemoteUrl(), path.path());
    String etag = null;
    Instant lastModified = null;
    if (cached.isPresent() && cached.get().blob() != null) {
      Map<String, Object> attrs = cached.get().blob().attributes();
      etag = stringAttr(attrs, "remoteEtag");
      String lm = stringAttr(attrs, "remoteLastModified");
      if (lm != null) {
        try { lastModified = Instant.parse(lm); } catch (RuntimeException ignored) {}
      }
    }
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, etag, lastModified, null, headOnly)
        .withTimeoutProfile(mavenTimeoutProfile(path))
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, path.path(), result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          Map<String, Object> attributes = refreshedAttributes(runtime, path, cached.get(), now);
          if (attributes == null) {
            assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          } else {
            assetDao.touchAssetLastUpdatedAndAttributes(cached.get().assetId(), now, attributes);
          }
          // #5: refresh the cached snapshot in-place so other replicas observe the freshness bump
          // without re-reading MySQL on their next hit.
          assetMetadataCache.touchVerified(runtime.id(), path.path(), now, attributes);
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (negativeCache != null) negativeCache.invalidate(runtime, path);
          return serveCached(cached.get(), headOnly, path);
        }
        if (status >= 200 && status < 300) {
          if (negativeCache != null) negativeCache.invalidate(runtime, path);
          if (headOnly) {
            proxyStateDao.recordSuccess(runtime.id(), now);
            return remoteHeadResponse(result);
          }
          return persistAndServe(runtime, path, result, headOnly, now);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return serveCached(cached.get(), headOnly, path);
          }
          if (status == 404 && negativeCache != null) negativeCache.rememberNotFound(runtime, path);
          throw new MavenExceptions.MavenNotFoundException(path.path());
        }
        return handleUpstreamFailure(runtime, path, cached, headOnly,
            "Upstream returned " + status, now);
      });
    } catch (IOException e) {
      return handleUpstreamFailure(runtime, path, cached, headOnly,
          "Upstream IO error: " + e.getMessage(), now);
    }
  }

  private MavenResponse persistAndServe(RepositoryRuntime runtime, MavenPath path,
      HttpRemoteFetcher.Result result, boolean headOnly, Instant now) {
    String contentType = result.contentType();
    // Defense in depth: some upstream mirrors return an HTML directory page (200 OK) for
    // unknown / directory-shaped paths instead of a 404. We must not cache that as the asset
    // — otherwise the bogus entry shows up in browse_node and poisons future lookups.
    // Real Maven content is never text/html; metadata XML files have content-type application/xml.
    if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    Map<String, String> extras = new HashMap<>();
    if (result.etag() != null) extras.put("remoteEtag", result.etag());
    if (result.lastModified() != null) extras.put("remoteLastModified", result.lastModified().toString());
    MavenAssetWriter.Stored stored = writer.writePrimary(
        runtime, storage, blobStoreId, path, result.body(),
        contentType, "proxy", runtime.proxyRemoteUrl(),
        extras, false, !headOnly);
    try {
      proxyStateDao.recordSuccess(runtime.id(), now);
      AssetRecord asset = stored.asset();
      AssetBlobRecord blob = stored.blob();
      String etag = blob.sha1();
      Instant lastModified = asset.lastUpdatedAt();
      if (headOnly) {
        stored.discardBody();
        return MavenResponse.noBody(200, blob.size(), asset.contentType(), etag, lastModified);
      }
      return MavenResponse.ok(stored.openBody(), blob.size(), asset.contentType(), etag, lastModified);
    } catch (RuntimeException e) {
      stored.discardBody();
      throw e;
    }
  }

  private MavenResponse remoteHeadResponse(HttpRemoteFetcher.Result result) {
    return MavenResponse.noBody(
        result.status(),
        contentLength(result.header("Content-Length")),
        result.contentType(),
        result.etag(),
        result.lastModified());
  }

  private MavenResponse serveCached(CachedAssetMetadata snapshot, boolean headOnly, MavenPath path) {
    AssetBlobRecord blob = snapshot.toBlobRecord();
    if (blob == null) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    String etag = blob.sha1();
    Instant lastModified = snapshot.lastUpdatedAt();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), snapshot.contentType(), etag, lastModified);
    }
    return MavenResponse.ok(
        () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(
            BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.path())),
        blob.size(), snapshot.contentType(), etag, lastModified);
  }

  private MavenResponse handleUpstreamFailure(RepositoryRuntime runtime, MavenPath path,
      Optional<CachedAssetMetadata> cached, boolean headOnly, String error, Instant now) {
    int failCount = proxyStateDao.loadState(runtime.id())
        .map(ProxyStateDao.ProxyRemoteState::failCount).orElse(0);
    long block = runtime.autoBlockOrDefault()
        ? BACKOFF_SECONDS[Math.min(failCount, BACKOFF_SECONDS.length - 1)]
        : 0;
    proxyStateDao.recordFailure(runtime.id(), block, error, now);
    if (cached.isPresent()) return serveCached(cached.get(), headOnly, path);
    throw new MavenExceptions.BadUpstreamException(error);
  }

  private boolean isFresh(
      RepositoryRuntime runtime,
      MavenPath path,
      CachedAssetMetadata snapshot,
      int ttlMinutes,
      Instant now) {
    Optional<NexusLikeCacheInfo> cacheInfo = NexusLikeCacheInfo.fromAttributes(snapshot.attributes());
    if (cacheController != null && cacheInfo.isPresent()) {
      return !cacheController.isStale(runtime.id(), cacheType(path), cacheInfo.get(), ttlMinutes, now);
    }
    if (snapshot.lastUpdatedAt() == null) return false;
    if (ttlMinutes < 0) return true;
    return snapshot.lastUpdatedAt().plusSeconds(ttlMinutes * 60L).isAfter(now);
  }

  private Map<String, Object> refreshedAttributes(
      RepositoryRuntime runtime,
      MavenPath path,
      CachedAssetMetadata snapshot,
      Instant now) {
    if (cacheController == null) {
      return null;
    }
    Map<String, Object> attrs = new LinkedHashMap<>();
    if (snapshot.attributes() != null) {
      attrs.putAll(snapshot.attributes());
    }
    return NexusLikeCacheInfo.applyToAttributes(attrs, cacheController.current(runtime.id(), cacheType(path), now));
  }

  private int ttlMinutes(RepositoryRuntime runtime, MavenPath path) {
    return parser.isRepositoryMetadata(path)
        ? runtime.metadataMaxAgeMinutesOrDefault()
        : runtime.contentMaxAgeMinutesOrDefault();
  }

  private static NexusCacheType cacheType(MavenPath path) {
    return "maven-metadata.xml".equals(path.main().fileName())
        ? NexusCacheType.METADATA
        : NexusCacheType.CONTENT;
  }

  private static HttpRemoteFetcher.TimeoutProfile mavenTimeoutProfile(MavenPath path) {
    return cacheType(path) == NexusCacheType.METADATA
        ? HttpRemoteFetcher.TimeoutProfile.METADATA
        : HttpRemoteFetcher.TimeoutProfile.CONTENT;
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Proxy " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private static String buildRemoteUrl(String base, String path) {
    return RemoteUrlBuilder.repositoryPathString(base, path);
  }

  private static String stringAttr(Map<String, Object> attrs, String key) {
    if (attrs == null) return null;
    Object v = attrs.get(key);
    return v == null ? null : v.toString();
  }

  private static long contentLength(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Long.parseLong(raw.trim()));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

}
