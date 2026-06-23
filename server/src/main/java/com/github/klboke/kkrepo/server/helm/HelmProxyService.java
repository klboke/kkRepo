package com.github.klboke.kkrepo.server.helm;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ProxyStateDao;
import com.github.klboke.kkrepo.protocol.helm.HelmAssetKind;
import com.github.klboke.kkrepo.protocol.helm.HelmIndex;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class HelmProxyService {
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};

  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final HelmAssetWriter writer;
  private final HelmAssetReader reader;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;

  public HelmProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      HelmAssetWriter writer,
      HelmAssetReader reader,
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
    String path = HelmHostedService.normalizePath(rawPath);
    HelmAssetKind kind = readableKind(path);
    if (kind == HelmAssetKind.INDEX) {
      return getIndex(runtime, headOnly);
    }
    if (kind != HelmAssetKind.PACKAGE) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
    return getPackage(runtime, path, headOnly);
  }

  private MavenResponse getIndex(RepositoryRuntime runtime, boolean headOnly) {
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, HelmHostedService.INDEX_PATH);
    Instant now = Instant.now();
    if (cached.isPresent() && isFresh(cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now)) {
      return reader.serveSnapshot(cached.get(), headOnly, HelmHostedService.INDEX_PATH);
    }
    if (negativeCache.isNotFoundCached(runtime, HelmHostedService.INDEX_PATH)) {
      throw new MavenExceptions.MavenNotFoundException(HelmHostedService.INDEX_PATH);
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return reader.serveSnapshot(cached.get(), headOnly, HelmHostedService.INDEX_PATH);
      throw new MavenExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    return fetchAndCacheIndex(runtime, cached, headOnly, now);
  }

  private MavenResponse getPackage(RepositoryRuntime runtime, String path, boolean headOnly) {
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path);
    Instant now = Instant.now();
    if (cached.isPresent() && isFresh(cached.get(), runtime.contentMaxAgeMinutesOrDefault(), now)) {
      return reader.serveSnapshot(cached.get(), headOnly, path);
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return reader.serveSnapshot(cached.get(), headOnly, path);
      throw new MavenExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    String remoteUrl = resolvePackageRemoteUrl(runtime, path, now);
    return fetchAndCachePackage(runtime, path, remoteUrl, cached, headOnly, now);
  }

  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  private MavenResponse fetchAndCacheIndex(
      RepositoryRuntime runtime, Optional<CachedAssetMetadata> cached, boolean headOnly, Instant now) {
    String etag = null;
    Instant lastModified = null;
    if (cached.isPresent() && cached.get().blob() != null) {
      Map<String, Object> attrs = cached.get().blob().attributes();
      etag = stringAttr(attrs, "remoteEtag");
      lastModified = instantAttr(attrs, "remoteLastModified");
    }
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        buildRemoteUrl(runtime.proxyRemoteUrl(), HelmHostedService.INDEX_PATH),
        etag,
        lastModified,
        null,
        false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, HelmHostedService.INDEX_PATH, result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          assetMetadataCache.touchVerified(runtime.id(), HelmHostedService.INDEX_PATH, now);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, HelmHostedService.INDEX_PATH);
          return reader.serveSnapshot(cached.get(), headOnly, HelmHostedService.INDEX_PATH);
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, HelmHostedService.INDEX_PATH);
          HelmAssetWriter.Stored stored = cacheIndex(runtime, result, !headOnly);
          proxyStateDao.recordSuccess(runtime.id(), now);
          return responseFromStored(stored, headOnly);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return reader.serveSnapshot(cached.get(), headOnly, HelmHostedService.INDEX_PATH);
          }
          if (status == 404) negativeCache.rememberNotFound(runtime, HelmHostedService.INDEX_PATH);
          throw new MavenExceptions.MavenNotFoundException(HelmHostedService.INDEX_PATH);
        }
        return handleUpstreamFailure(runtime, HelmHostedService.INDEX_PATH, cached, headOnly,
            "Upstream returned " + status, now);
      });
    } catch (IllegalArgumentException e) {
      return handleUpstreamFailure(runtime, HelmHostedService.INDEX_PATH, cached, headOnly,
          "Invalid upstream Helm index: " + e.getMessage(), now);
    } catch (IOException e) {
      return handleUpstreamFailure(runtime, HelmHostedService.INDEX_PATH, cached, headOnly,
          "Upstream IO error: " + e.getMessage(), now);
    }
  }

  private HelmAssetWriter.Stored cacheIndex(
      RepositoryRuntime runtime, HttpRemoteFetcher.Result result, boolean keepResponseFile)
      throws IOException {
    byte[] upstream = UpstreamBodyReadException.readAllBytes(result.body());
    HelmIndex.RewriteResult rewritten = HelmIndex.rewriteProxyIndex(upstream, runtime.proxyRemoteUrl());
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("remoteUrls", rewritten.remoteUrlsByLocalPath());
    return writer.writeBytes(
        runtime,
        blobStorage(runtime),
        requireBlobStore(runtime),
        HelmHostedService.INDEX_PATH,
        rewritten.body(),
        HelmIndex.CONTENT_TYPE,
        HelmAssetKind.INDEX,
        null,
        attrs,
        remoteAttrs(result),
        "proxy",
        runtime.proxyRemoteUrl(),
        keepResponseFile);
  }

  private MavenResponse fetchAndCachePackage(
      RepositoryRuntime runtime,
      String path,
      String remoteUrl,
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
    try {
      return fetcher.fetchWithBodyRetry(req, path, result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          assetMetadataCache.touchVerified(runtime.id(), path, now);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path);
          return reader.serveSnapshot(cached.get(), headOnly, path);
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, path);
          Map<String, Object> attrs = new LinkedHashMap<>();
          attrs.put("remoteUrl", remoteUrl);
          HelmAssetWriter.Stored stored = writer.write(
              runtime,
              blobStorage(runtime),
              requireBlobStore(runtime),
              path,
              result.body(),
              result.contentType(),
              HelmAssetKind.PACKAGE,
              null,
              attrs,
              remoteAttrs(result),
              "proxy",
              runtime.proxyRemoteUrl(),
              !headOnly);
          proxyStateDao.recordSuccess(runtime.id(), now);
          return responseFromStored(stored, headOnly);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return reader.serveSnapshot(cached.get(), headOnly, path);
          }
          if (status == 404) negativeCache.rememberNotFound(runtime, path);
          throw new MavenExceptions.MavenNotFoundException(path);
        }
        return handleUpstreamFailure(runtime, path, cached, headOnly,
            "Upstream returned " + status, now);
      });
    } catch (IllegalArgumentException e) {
      return handleUpstreamFailure(runtime, path, cached, headOnly,
          "Invalid upstream Helm chart: " + e.getMessage(), now);
    } catch (IOException e) {
      return handleUpstreamFailure(runtime, path, cached, headOnly,
          "Upstream IO error: " + e.getMessage(), now);
    }
  }

  private String resolvePackageRemoteUrl(RepositoryRuntime runtime, String path, Instant now) {
    CachedAssetMetadata index = ensureIndexCached(runtime, now);
    String fromIndex = remoteUrlFromIndexAttributes(index, path);
    if (fromIndex != null && !fromIndex.isBlank()) {
      return fromIndex;
    }
    return buildRemoteUrl(runtime.proxyRemoteUrl(), path);
  }

  private MavenResponse responseFromStored(HelmAssetWriter.Stored stored, boolean headOnly) {
    try {
      if (headOnly) {
        stored.discardBody();
        return MavenResponse.noBody(200, stored.blob().size(), stored.asset().contentType(),
            stored.blob().sha1(), stored.asset().lastUpdatedAt());
      }
      return MavenResponse.ok(stored.openBody(), stored.blob().size(), stored.asset().contentType(),
          stored.blob().sha1(), stored.asset().lastUpdatedAt());
    } catch (RuntimeException e) {
      stored.discardBody();
      throw e;
    }
  }

  private CachedAssetMetadata ensureIndexCached(RepositoryRuntime runtime, Instant now) {
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, HelmHostedService.INDEX_PATH);
    if (cached.isPresent() && isFresh(cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now)) {
      return cached.get();
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      return cached.orElseThrow(() -> new MavenExceptions.BadUpstreamException(
          "Upstream temporarily blocked: " + runtime.proxyRemoteUrl()));
    }
    fetchAndCacheIndex(runtime, cached, true, now);
    return lookupCached(runtime, HelmHostedService.INDEX_PATH)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(HelmHostedService.INDEX_PATH));
  }

  private static HelmAssetKind readableKind(String path) {
    try {
      return HelmAssetKind.fromPath(path);
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
  }

  @SuppressWarnings("unchecked")
  private String remoteUrlFromIndexAttributes(CachedAssetMetadata index, String path) {
    Object raw = index.attributes() == null ? null : index.attributes().get("remoteUrls");
    if (!(raw instanceof Map<?, ?> map)) {
      return null;
    }
    Object exact = map.get(path);
    if (exact != null) return exact.toString();
    String file = fileName(path);
    Object byFile = map.get(file);
    return byFile == null ? null : byFile.toString();
  }

  private MavenResponse handleUpstreamFailure(RepositoryRuntime runtime, String path,
      Optional<CachedAssetMetadata> cached, boolean headOnly, String error, Instant now) {
    int failCount = proxyStateDao.loadState(runtime.id())
        .map(ProxyStateDao.ProxyRemoteState::failCount).orElse(0);
    long block = runtime.autoBlockOrDefault()
        ? BACKOFF_SECONDS[Math.min(failCount, BACKOFF_SECONDS.length - 1)]
        : 0;
    proxyStateDao.recordFailure(runtime.id(), block, error, now);
    if (cached.isPresent()) return reader.serveSnapshot(cached.get(), headOnly, path);
    throw new MavenExceptions.BadUpstreamException(error);
  }

  private boolean isFresh(CachedAssetMetadata snapshot, int ttlMinutes, Instant now) {
    if (snapshot.lastUpdatedAt() == null) return false;
    if (ttlMinutes < 0) return true;
    return snapshot.lastUpdatedAt().plusSeconds(ttlMinutes * 60L).isAfter(now);
  }

  private Map<String, String> remoteAttrs(HttpRemoteFetcher.Result result) {
    Map<String, String> attrs = new HashMap<>();
    if (result.etag() != null) attrs.put("remoteEtag", result.etag());
    if (result.lastModified() != null) attrs.put("remoteLastModified", result.lastModified().toString());
    return attrs;
  }

  private void ensureProxy(RepositoryRuntime runtime) {
    if (!runtime.isProxy()) {
      throw new MavenExceptions.MethodNotAllowed("Operation is only valid on proxy Helm repositories");
    }
  }

  private BlobStorage blobStorage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime));
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Helm proxy " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private static String buildRemoteUrl(String base, String path) {
    return RemoteUrlBuilder.repositoryPathString(base, path);
  }

  private static String fileName(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
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
}
