package com.github.klboke.kkrepo.server.raw;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.proxy.ProxyRequestAudit;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class RawProxyService {
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};
  static final String REMOTE_SOURCE_FINGERPRINT = "remoteSourceFingerprint";

  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final RawAssetWriter writer;
  private final RawAssetReader reader;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;

  public RawProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      RawAssetWriter writer,
      RawAssetReader reader,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
    this.proxyStateDao = proxyStateDao;
    this.fetcher = fetcher;
    this.negativeCache = negativeCache;
    this.assetMetadataCache = assetMetadataCache;
  }

  public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    ensureProxy(runtime);
    if (RawHostedService.isDirectoryRequest(rawPath)) {
      return getIndex(runtime, rawPath, headOnly);
    }
    String path = RawHostedService.normalizeAssetPath(rawPath);
    return getAsset(runtime, path, headOnly);
  }

  private MavenResponse getIndex(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    String base = RawHostedService.normalizeDirectoryPath(rawPath);
    MavenExceptions.BadUpstreamException lastUpstream = null;
    for (String candidate : RawHostedService.indexCandidates(base)) {
      try {
        return getAsset(runtime, candidate, headOnly);
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // Try index.htm after index.html, then report the Nexus raw browse-style 404.
      } catch (MavenExceptions.BadUpstreamException e) {
        lastUpstream = e;
      }
    }
    if (lastUpstream != null) throw lastUpstream;
    throw new MavenExceptions.MavenNotFoundException("You can't browse this way");
  }

  public MavenResponse getAsset(RepositoryRuntime runtime, String path, boolean headOnly) {
    return getAsset(runtime, path, path, headOnly);
  }

  public MavenResponse getAsset(RepositoryRuntime runtime, String path, String remotePath, boolean headOnly) {
    String remoteUrl = buildRemoteUrl(runtime.proxyRemoteUrl(), remotePath);
    String sourceFingerprint = remoteSourceFingerprint(runtime, remoteUrl);
    Optional<CachedAssetMetadata> cached = sourceCompatible(
        lookupCached(runtime, path), sourceFingerprint);
    Instant now = Instant.now();
    if (cached.isPresent() && isFresh(cached.get(), runtime.contentMaxAgeMinutesOrDefault(), now)) {
      return reader.serveSnapshot(cached.get(), headOnly, path, runtime.rawContentDispositionOrDefault());
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) {
        return reader.serveSnapshot(cached.get(), headOnly, path, runtime.rawContentDispositionOrDefault());
      }
      throw new MavenExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    return fetchAndCache(
        runtime, path, remoteUrl, sourceFingerprint, cached, headOnly, now);
  }

  public MavenResponse getAssetFromUrl(RepositoryRuntime runtime, String path, String remoteUrl, boolean headOnly) {
    return getAssetFromUrl(
        runtime, path, remoteUrl, runtime.contentMaxAgeMinutesOrDefault(),
        HttpRemoteFetcher.TimeoutProfile.CONTENT, ComponentBinding.perAsset(), headOnly);
  }

  public MavenResponse getAssetFromUrlWithComponent(
      RepositoryRuntime runtime,
      String path,
      String remoteUrl,
      ComponentRecord component,
      boolean headOnly) {
    return getAssetFromUrl(
        runtime, path, remoteUrl, runtime.contentMaxAgeMinutesOrDefault(),
        HttpRemoteFetcher.TimeoutProfile.CONTENT, ComponentBinding.explicit(component), headOnly);
  }

  /**
   * Serves content pinned by a metadata response for the lifetime of that metadata snapshot.
   * The content still uses the large-body timeout profile when it eventually needs refreshing.
   */
  public MavenResponse getPinnedAssetFromUrl(
      RepositoryRuntime runtime, String path, String remoteUrl, boolean headOnly) {
    return getAssetFromUrl(
        runtime, path, remoteUrl, runtime.metadataMaxAgeMinutesOrDefault(),
        HttpRemoteFetcher.TimeoutProfile.CONTENT, ComponentBinding.perAsset(), headOnly);
  }

  /**
   * Caches immutable protocol content without inventing a generic component coordinate.
   *
   * <p>Formats whose exact component identity comes from a large remote index can return the
   * bytes first and attach the component projection after that index has been processed.
   */
  public MavenResponse getPinnedAssetFromUrlUnindexed(
      RepositoryRuntime runtime, String path, String remoteUrl, boolean headOnly) {
    return getAssetFromUrl(
        runtime, path, remoteUrl, runtime.metadataMaxAgeMinutesOrDefault(),
        HttpRemoteFetcher.TimeoutProfile.CONTENT, ComponentBinding.none(), "", headOnly);
  }

  public MavenResponse getPinnedAssetFromUrlWithComponent(
      RepositoryRuntime runtime,
      String path,
      String remoteUrl,
      ComponentRecord component,
      boolean headOnly) {
    return getAssetFromUrl(
        runtime, path, remoteUrl, runtime.metadataMaxAgeMinutesOrDefault(),
        HttpRemoteFetcher.TimeoutProfile.CONTENT, ComponentBinding.explicit(component), headOnly);
  }

  /**
   * Caches a protocol asset at its canonical repository path while indexing an independent
   * logical path for Browse. The two paths intentionally differ for formats such as Conda whose
   * Nexus component tree is richer than the client-facing download layout.
   */
  public MavenResponse getPinnedAssetFromUrlWithComponentAtBrowsePath(
      RepositoryRuntime runtime,
      String path,
      String remoteUrl,
      ComponentRecord component,
      String browsePath,
      boolean headOnly) {
    return getAssetFromUrl(
        runtime, path, remoteUrl, runtime.metadataMaxAgeMinutesOrDefault(),
        HttpRemoteFetcher.TimeoutProfile.CONTENT, ComponentBinding.explicit(component),
        browsePath, headOnly);
  }

  public MavenResponse getMetadataFromUrl(
      RepositoryRuntime runtime, String path, String remoteUrl, boolean headOnly) {
    return getAssetFromUrl(
        runtime, path, remoteUrl, runtime.metadataMaxAgeMinutesOrDefault(),
        HttpRemoteFetcher.TimeoutProfile.METADATA, ComponentBinding.perAsset(), headOnly);
  }

  public MavenResponse getMetadataFromUrlUnindexed(
      RepositoryRuntime runtime, String path, String remoteUrl, boolean headOnly) {
    return getAssetFromUrl(
        runtime, path, remoteUrl, runtime.metadataMaxAgeMinutesOrDefault(),
        HttpRemoteFetcher.TimeoutProfile.METADATA, ComponentBinding.none(), headOnly);
  }

  /** Caches protocol discovery state without creating a component or Browse node. */
  public MavenResponse getMetadataFromUrlHidden(
      RepositoryRuntime runtime, String path, String remoteUrl, boolean headOnly) {
    return getAssetFromUrl(
        runtime, path, remoteUrl, runtime.metadataMaxAgeMinutesOrDefault(),
        HttpRemoteFetcher.TimeoutProfile.METADATA, ComponentBinding.hidden(), "", headOnly);
  }

  public MavenResponse getMetadataFromUrlWithComponent(
      RepositoryRuntime runtime,
      String path,
      String remoteUrl,
      ComponentRecord component,
      boolean headOnly) {
    return getAssetFromUrl(
        runtime, path, remoteUrl, runtime.metadataMaxAgeMinutesOrDefault(),
        HttpRemoteFetcher.TimeoutProfile.METADATA, ComponentBinding.explicit(component), headOnly);
  }

  private MavenResponse getAssetFromUrl(
      RepositoryRuntime runtime, String path, String remoteUrl, int maxAgeMinutes,
      HttpRemoteFetcher.TimeoutProfile timeoutProfile,
      ComponentBinding componentBinding,
      boolean headOnly) {
    return getAssetFromUrl(
        runtime, path, remoteUrl, maxAgeMinutes, timeoutProfile, componentBinding, path, headOnly);
  }

  private MavenResponse getAssetFromUrl(
      RepositoryRuntime runtime, String path, String remoteUrl, int maxAgeMinutes,
      HttpRemoteFetcher.TimeoutProfile timeoutProfile,
      ComponentBinding componentBinding,
      String browsePath,
      boolean headOnly) {
    String sourceFingerprint = remoteSourceFingerprint(runtime, remoteUrl);
    Optional<CachedAssetMetadata> cached = sourceCompatible(
        lookupCached(runtime, path), sourceFingerprint);
    Instant now = Instant.now();
    if (cached.isPresent() && isFresh(cached.get(), maxAgeMinutes, now)) {
      return reader.serveSnapshot(cached.get(), headOnly, path, runtime.rawContentDispositionOrDefault());
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) {
        return reader.serveSnapshot(cached.get(), headOnly, path, runtime.rawContentDispositionOrDefault());
      }
      throw new MavenExceptions.BadUpstreamException("Upstream temporarily blocked: " + remoteUrl);
    }
    return fetchAndCacheUrl(
        runtime, path, remoteUrl, sourceFingerprint, cached, headOnly, now, timeoutProfile,
        componentBinding, browsePath);
  }

  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  private MavenResponse fetchAndCache(
      RepositoryRuntime runtime,
      String path,
      String remoteUrl,
      String sourceFingerprint,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      Instant now) {
    String etag = null;
    Instant lastModified = null;
    if (cached.isPresent() && cached.get().blob() != null) {
      Map<String, Object> attrs = cached.get().blob().attributes();
      etag = stringAttr(attrs, "remoteEtag");
      lastModified = instantAttr(attrs, "remoteLastModified");
    }
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        remoteUrl, etag, lastModified, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
        .withRepository(runtime);
    return fetchAndCache(
        runtime, path, sourceFingerprint, cached, headOnly, now, req,
        ComponentBinding.perAsset(), path);
  }

  private MavenResponse fetchAndCacheUrl(
      RepositoryRuntime runtime,
      String path,
      String remoteUrl,
      String sourceFingerprint,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      Instant now,
      HttpRemoteFetcher.TimeoutProfile timeoutProfile,
      ComponentBinding componentBinding,
      String browsePath) {
    String etag = null;
    Instant lastModified = null;
    if (cached.isPresent() && cached.get().blob() != null) {
      Map<String, Object> attrs = cached.get().blob().attributes();
      etag = stringAttr(attrs, "remoteEtag");
      lastModified = instantAttr(attrs, "remoteLastModified");
    }
    HttpRemoteFetcher.Request req = cachePopulationRequest(
        runtime, remoteUrl, etag, lastModified).withTimeoutProfile(timeoutProfile);
    return fetchAndCache(
        runtime, path, sourceFingerprint, cached, headOnly, now, req, componentBinding,
        browsePath);
  }

  static HttpRemoteFetcher.Request cachePopulationRequest(
      RepositoryRuntime runtime,
      String remoteUrl,
      String etag,
      Instant lastModified) {
    // Even when the client requested HEAD, an uncached proxy asset must be fetched with GET so
    // the cache is populated with the complete body. headOnly is applied only to the response
    // returned after persistence; forwarding HEAD upstream would create a zero-byte cached blob.
    return new HttpRemoteFetcher.Request(
        remoteUrl, etag, lastModified, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
        .withRepository(runtime);
  }

  private MavenResponse fetchAndCache(
      RepositoryRuntime runtime,
      String path,
      String sourceFingerprint,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      Instant now,
      HttpRemoteFetcher.Request req,
      ComponentBinding componentBinding,
      String browsePath) {
    try {
      return fetcher.fetchWithBodyRetry(req, path, result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          assetMetadataCache.touchVerified(runtime.id(), path, now);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path);
          return reader.serveSnapshot(cached.get(), headOnly, path, runtime.rawContentDispositionOrDefault());
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, path);
          RawAssetWriter.Stored stored = persist(
              runtime, path, result, sourceFingerprint, componentBinding, browsePath);
          try {
            proxyStateDao.recordSuccess(runtime.id(), now);
            if (headOnly) {
              stored.discardBody();
              return reader.serve(stored.asset(), true, path, runtime.rawContentDispositionOrDefault());
            }
            reader.beforeRead(
                stored.asset().id(), stored.blob().id(), stored.asset().repositoryId());
            return MavenResponse.ok(stored.openBody(), stored.blob().size(), stored.asset().contentType(),
                stored.blob().sha1(), stored.asset().lastUpdatedAt())
                .withHeader("Content-Disposition",
                    runtime.rawContentDispositionOrDefault().toLowerCase(Locale.ROOT));
          } catch (RuntimeException e) {
            stored.discardBody();
            throw e;
          }
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return reader.serveSnapshot(cached.get(), headOnly, path, runtime.rawContentDispositionOrDefault());
          }
          if (status == 404) negativeCache.rememberNotFound(runtime, path);
          throw new MavenExceptions.MavenNotFoundException(path);
        }
        return handleUpstreamFailure(runtime, path, cached, headOnly,
            "Upstream returned " + status, now);
      });
    } catch (IOException e) {
      return handleUpstreamFailure(runtime, path, cached, headOnly,
          "Upstream IO error: " + e.getMessage(), now);
    }
  }

  private RawAssetWriter.Stored persist(
      RepositoryRuntime runtime,
      String path,
      HttpRemoteFetcher.Result result,
      String sourceFingerprint,
      ComponentBinding componentBinding,
      String browsePath) {
    Map<String, String> extras = new HashMap<>();
    extras.put(REMOTE_SOURCE_FINGERPRINT, sourceFingerprint);
    if (result.etag() != null) extras.put("remoteEtag", result.etag());
    if (result.lastModified() != null) extras.put("remoteLastModified", result.lastModified().toString());
    String clientIp = ProxyRequestAudit.currentClientIp();
    return switch (componentBinding.mode()) {
      case PER_ASSET -> writer.write(
          runtime, blobStorage(runtime), requireBlobStore(runtime), path, result.body(),
          result.contentType(), extras, "proxy", clientIp, true);
      case NONE -> writer.writeUnindexed(
          runtime, blobStorage(runtime), requireBlobStore(runtime), path, result.body(),
          result.contentType(), extras, "proxy", clientIp, true);
      case HIDDEN -> writer.writeHidden(
          runtime, blobStorage(runtime), requireBlobStore(runtime), path, result.body(),
          result.contentType(), extras, "proxy", clientIp, true);
      case EXPLICIT -> writer.writeWithComponentAtBrowsePath(
          runtime, blobStorage(runtime), requireBlobStore(runtime), path, result.body(),
          result.contentType(), extras, "proxy", clientIp,
          componentBinding.component(), browsePath, true);
    };
  }

  private MavenResponse handleUpstreamFailure(
      RepositoryRuntime runtime,
      String path,
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
    if (cached.isPresent()) {
      return reader.serveSnapshot(cached.get(), headOnly, path, runtime.rawContentDispositionOrDefault());
    }
    throw new MavenExceptions.BadUpstreamException(error);
  }

  private boolean isFresh(CachedAssetMetadata snapshot, int ttlMinutes, Instant now) {
    if (snapshot.lastUpdatedAt() == null) return false;
    if (ttlMinutes < 0) return true;
    return snapshot.lastUpdatedAt().plusSeconds(ttlMinutes * 60L).isAfter(now);
  }

  /**
   * Rejects a cache entry written for another configured upstream. Entries created before source
   * fingerprints were introduced remain usable and acquire a fingerprint on their next 200
   * refresh, avoiding a fleet-wide cold-cache event during upgrades.
   */
  private static Optional<CachedAssetMetadata> sourceCompatible(
      Optional<CachedAssetMetadata> cached,
      String expectedFingerprint) {
    if (cached.isEmpty() || cached.get().blob() == null) {
      return cached;
    }
    String actual = stringAttr(
        cached.get().blob().attributes(), REMOTE_SOURCE_FINGERPRINT);
    return actual == null || actual.equals(expectedFingerprint) ? cached : Optional.empty();
  }

  static String remoteSourceFingerprint(RepositoryRuntime runtime, String remoteUrl) {
    String configuredSource = runtime == null ? null : runtime.proxyRemoteUrl();
    String source = configuredSource == null || configuredSource.isBlank()
        ? remoteUrl
        : configuredSource;
    return HexFormat.of().formatHex(
        PersistenceHashes.sha256("raw-proxy-source-v1", source));
  }

  private void ensureProxy(RepositoryRuntime runtime) {
    if (!runtime.isProxy()) {
      throw new MavenExceptions.MethodNotAllowed("Operation is only valid on proxy raw repositories");
    }
  }

  private BlobStorage blobStorage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime));
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Raw proxy " + runtime.name() + " has no blob store assigned");
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
    String value = stringAttr(attrs, key);
    if (value == null) return null;
    try {
      return Instant.parse(value);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private enum ComponentMode {
    PER_ASSET,
    NONE,
    HIDDEN,
    EXPLICIT
  }

  private record ComponentBinding(ComponentMode mode, ComponentRecord component) {
    private static ComponentBinding perAsset() {
      return new ComponentBinding(ComponentMode.PER_ASSET, null);
    }

    private static ComponentBinding none() {
      return new ComponentBinding(ComponentMode.NONE, null);
    }

    private static ComponentBinding hidden() {
      return new ComponentBinding(ComponentMode.HIDDEN, null);
    }

    private static ComponentBinding explicit(ComponentRecord component) {
      if (component == null) {
        throw new IllegalArgumentException("Explicit component is required");
      }
      return new ComponentBinding(ComponentMode.EXPLICIT, component);
    }
  }
}
