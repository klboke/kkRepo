package com.github.klboke.kkrepo.server.goartifact;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class GoProxyService {
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};

  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final GoAssetWriter writer;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;

  public GoProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      GoAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.proxyStateDao = proxyStateDao;
    this.fetcher = fetcher;
    this.negativeCache = negativeCache;
    this.assetMetadataCache = assetMetadataCache;
  }

  public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    if (runtime.format() != RepositoryFormat.GO || !runtime.isProxy()) {
      throw new MavenExceptions.MethodNotAllowed("Go proxy requests require a go proxy repository");
    }
    GoPath path = parse(rawPath);
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path.path());
    Instant now = Instant.now();
    int ttl = path.metadata()
        ? runtime.metadataMaxAgeMinutesOrDefault()
        : runtime.contentMaxAgeMinutesOrDefault();
    if (cached.isPresent() && isFresh(cached.get(), ttl, now)) {
      return serveCached(cached.get(), headOnly, path);
    }
    if (negativeCache.isNotFoundCached(runtime, path.path())) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return serveCached(cached.get(), headOnly, path);
      throw new MavenExceptions.BadUpstreamException(
          "Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    return fetchAndCache(runtime, path, cached, headOnly, now);
  }

  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  private GoPath parse(String rawPath) {
    try {
      return GoPath.parse(rawPath);
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.MavenNotFoundException(e.getMessage());
    }
  }

  private MavenResponse fetchAndCache(
      RepositoryRuntime runtime,
      GoPath path,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      Instant now) {
    String url = buildRemoteUrl(runtime.proxyRemoteUrl(), path.path());
    String etag = null;
    Instant lastModified = null;
    if (cached.isPresent() && cached.get().blob() != null) {
      Map<String, Object> attrs = cached.get().blob().attributes();
      etag = stringAttr(attrs, "remoteEtag");
      lastModified = instantAttr(attrs, "remoteLastModified");
    }
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, etag, lastModified, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, path.path(), result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          assetMetadataCache.touchVerified(runtime.id(), path.path(), now);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path.path());
          return serveCached(cached.get(), headOnly, path);
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, path.path());
          return persistAndServe(runtime, path, result, headOnly, now);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return serveCached(cached.get(), headOnly, path);
          }
          if (status == 404) negativeCache.rememberNotFound(runtime, path.path());
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

  private MavenResponse persistAndServe(
      RepositoryRuntime runtime,
      GoPath path,
      HttpRemoteFetcher.Result result,
      boolean headOnly,
      Instant now) {
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    Map<String, String> extras = new HashMap<>();
    if (result.etag() != null) extras.put("remoteEtag", result.etag());
    if (result.lastModified() != null) extras.put("remoteLastModified", result.lastModified().toString());
    GoAssetWriter.Stored stored = writer.write(
        runtime, storage, blobStoreId, path, result.body(), extras, !headOnly);
    try {
      proxyStateDao.recordSuccess(runtime.id(), now);
      if (headOnly) {
        stored.discardBody();
        return toResponse(storage, stored.asset().lastUpdatedAt(), stored.blob(), true, path);
      }
      String etag = stringAttr(stored.blob().attributes(), "remoteEtag");
      Instant lastModified = instantAttr(stored.blob().attributes(), "remoteLastModified");
      if (lastModified == null) lastModified = stored.asset().lastUpdatedAt();
      return MavenResponse.ok(stored.openBody(), stored.blob().size(), path.contentType(), etag, lastModified);
    } catch (RuntimeException e) {
      stored.discardBody();
      throw e;
    }
  }

  private MavenResponse serveCached(CachedAssetMetadata snapshot, boolean headOnly, GoPath path) {
    AssetBlobRecord blob = snapshot.toBlobRecord();
    if (blob == null) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blob.blobStoreId());
    return toResponse(storage, snapshot.lastUpdatedAt(), blob, headOnly, path);
  }

  private MavenResponse toResponse(
      BlobStorage storage,
      Instant assetLastUpdatedAt,
      AssetBlobRecord blob,
      boolean headOnly,
      GoPath path) {
    String etag = stringAttr(blob.attributes(), "remoteEtag");
    Instant lastModified = instantAttr(blob.attributes(), "remoteLastModified");
    if (lastModified == null) lastModified = assetLastUpdatedAt;
    String contentType = path.contentType();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), contentType, etag, lastModified);
    }
    return MavenResponse.ok(
        () -> storage.get(BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.path())),
        blob.size(), contentType, etag, lastModified);
  }

  private MavenResponse handleUpstreamFailure(
      RepositoryRuntime runtime,
      GoPath path,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      String error,
      Instant now) {
    int failCount = proxyStateDao.loadState(runtime.id())
        .map(ProxyStateDao.ProxyRemoteState::failCount).orElse(0);
    long block = runtime.autoBlockOrDefault()
        ? BACKOFF_SECONDS[Math.min(failCount, BACKOFF_SECONDS.length - 1)]
        : 0;
    proxyStateDao.recordFailure(runtime.id(), block, error, now);
    if (cached.isPresent()) return serveCached(cached.get(), headOnly, path);
    throw new MavenExceptions.BadUpstreamException(error);
  }

  private boolean isFresh(CachedAssetMetadata snapshot, int ttlMinutes, Instant now) {
    if (snapshot.lastUpdatedAt() == null) return false;
    if (ttlMinutes < 0) return true;
    return snapshot.lastUpdatedAt().plusSeconds(ttlMinutes * 60L).isAfter(now);
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

  private static Instant instantAttr(Map<String, Object> attrs, String key) {
    String raw = stringAttr(attrs, key);
    if (raw == null) return null;
    try {
      return Instant.parse(raw);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

}
