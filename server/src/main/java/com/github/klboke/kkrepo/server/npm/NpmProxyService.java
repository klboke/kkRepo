package com.github.klboke.kkrepo.server.npm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.protocol.npm.NpmPath;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

@Service
public class NpmProxyService {
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final NpmAssetWriter writer;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final NpmHostedService hosted;
  private final ObjectMapper mapper;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;

  public NpmProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      NpmAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      NpmHostedService hosted,
      ObjectMapper mapper,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.proxyStateDao = proxyStateDao;
    this.fetcher = fetcher;
    this.hosted = hosted;
    this.mapper = mapper;
    this.negativeCache = negativeCache;
    this.assetMetadataCache = assetMetadataCache;
    this.cacheController = cacheController;
  }

  public MavenResponse get(RepositoryRuntime runtime, NpmPath path, String repositoryBaseUrl, boolean headOnly) {
    return get(runtime, path, repositoryBaseUrl, headOnly, NpmPackumentVariant.FULL);
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      NpmPath path,
      String repositoryBaseUrl,
      boolean headOnly,
      NpmPackumentVariant variant) {
    if (!runtime.isProxy()) {
      throw new IllegalStateException("NpmProxyService.get called on non-proxy " + runtime.name());
    }
    return switch (path.kind()) {
      case PACKAGE_ROOT, PACKAGE_VERSION -> getPackage(runtime, path.packageId(), repositoryBaseUrl, headOnly, variant);
      case TARBALL -> getTarball(runtime, path.packageId(), path.tarballName(), headOnly);
      case DIST_TAGS -> getDistTags(runtime, path.packageId(), headOnly);
      default -> throw new NpmExceptions.NpmNotFoundException(path.rawPath());
    };
  }

  public Map<String, Object> search(RepositoryRuntime runtime, String keyword, int limit) {
    if (!runtime.isProxy()) {
      throw new IllegalStateException("NpmProxyService.search called on non-proxy " + runtime.name());
    }
    String text = keyword == null ? "" : keyword;
    String url = RemoteUrlBuilder.repositoryPathWithQueryString(
        runtime.proxyRemoteUrl(),
        "-/v1/search",
        "text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
            + "&size=" + Math.max(1, limit));
    Instant now = Instant.now();
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, null, null, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.SEARCH)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, "-/v1/search", result -> {
        int status = result.status();
        if (status >= 200 && status < 300) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          return mapper.readValue(UpstreamBodyReadException.readAllBytes(result.body()), MAP_TYPE);
        }
        handleFailure(runtime, Optional.empty(), "Upstream search returned " + status, now);
        return Map.of("objects", List.of(), "total", 0, "time", "0ms");
      });
    } catch (IOException e) {
      handleFailure(runtime, Optional.empty(), "Upstream search IO error: " + e.getMessage(), now);
      return Map.of("objects", List.of(), "total", 0, "time", "0ms");
    }
  }

  public MavenResponse getPackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String repositoryBaseUrl,
      boolean headOnly) {
    return getPackage(runtime, packageId, repositoryBaseUrl, headOnly, NpmPackumentVariant.FULL);
  }

  public MavenResponse getPackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String repositoryBaseUrl,
      boolean headOnly,
      NpmPackumentVariant variant) {
    String path = packageId.id();
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path);
    Instant now = Instant.now();
    if (cached.isPresent()
        && isFresh(runtime, cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now, NexusCacheType.METADATA)) {
      return hosted.getPackage(runtime, packageId, repositoryBaseUrl, headOnly, variant);
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found");
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return hosted.getPackage(runtime, packageId, repositoryBaseUrl, headOnly, variant);
      throw new NpmExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    MavenResponse response = fetchAndCachePackage(runtime, packageId, repositoryBaseUrl, cached, headOnly, variant, now);
    return response == null ? hosted.getPackage(runtime, packageId, repositoryBaseUrl, headOnly, variant) : response;
  }

  public MavenResponse getDistTags(RepositoryRuntime runtime, NpmPackageId packageId, boolean headOnly) {
    getPackage(runtime, packageId, runtime.name(), true);
    return hosted.getDistTags(runtime, packageId, headOnly);
  }

  public MavenResponse getTarball(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      boolean headOnly) {
    String path = packageId.tarballPath(tarballName);
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path);
    Instant now = Instant.now();
    if (cached.isPresent()
        && isFresh(runtime, cached.get(), runtime.contentMaxAgeMinutesOrDefault(), now, NexusCacheType.CONTENT)) {
      return hosted.getTarball(runtime, packageId, tarballName, headOnly);
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new NpmExceptions.NpmNotFoundException(
          "Tarball '" + tarballName + "' in package '" + packageId.id() + "' not found");
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return hosted.getTarball(runtime, packageId, tarballName, headOnly);
      throw new NpmExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    NpmAssetWriter.Stored stored = fetchAndCacheTarball(runtime, packageId, tarballName, cached, headOnly, now);
    if (stored != null) {
      return tarballResponseFromStored(stored, headOnly);
    }
    return hosted.getTarball(runtime, packageId, tarballName, headOnly);
  }

  private MavenResponse tarballResponseFromStored(NpmAssetWriter.Stored stored, boolean headOnly) {
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

  private MavenResponse fetchAndCachePackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String repositoryBaseUrl,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      NpmPackumentVariant variant,
      Instant now) {
    String url = buildRemoteUrl(runtime.proxyRemoteUrl(), remotePackagePath(packageId));
    Conditional conditional = conditional(cached);
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, conditional.etag(), conditional.lastModified(), null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, packageId.id(), result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          Map<String, Object> attributes = refreshedAttributes(runtime, cached.get(), NexusCacheType.METADATA, now);
          if (attributes == null) {
            assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          } else {
            assetDao.touchAssetLastUpdatedAndAttributes(cached.get().assetId(), now, attributes);
          }
          assetMetadataCache.touchVerified(runtime.id(), packageId.id(), now, attributes);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, packageId.id());
          return null;
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, packageId.id());
          CachedPackage stored = persistPackage(runtime, packageId, result, now);
          return packageResponse(packageId, repositoryBaseUrl, stored, headOnly, variant);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return hosted.getPackage(runtime, packageId, repositoryBaseUrl, headOnly, variant);
          }
          if (status == 404) negativeCache.rememberNotFound(runtime, packageId.id());
          throw new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found");
        }
        handleFailure(runtime, cached, "Upstream returned " + status, now);
        return null;
      });
    } catch (IOException e) {
      handleFailure(runtime, cached, "Upstream IO error: " + e.getMessage(), now);
      return null;
    }
  }

  private CachedPackage persistPackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      HttpRemoteFetcher.Result result,
      Instant now) throws IOException {
    String contentType = result.contentType();
    if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
      throw new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found");
    }
    Map<String, Object> packageRoot = mapper.readValue(
        UpstreamBodyReadException.readAllBytes(result.body()), MAP_TYPE);
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    Map<String, String> extras = remoteAttributes(result);
    NpmAssetWriter.Stored stored = writer.writePackageRoot(runtime, storage, blobStoreId, packageId,
        NpmResponseSupport.write(mapper, packageRoot), "proxy", runtime.proxyRemoteUrl(), extras);
    updateCacheInfo(runtime, stored.asset(), NexusCacheType.METADATA, now);
    proxyStateDao.recordSuccess(runtime.id(), now);
    return new CachedPackage(packageRoot, stored.asset().lastUpdatedAt());
  }

  private MavenResponse packageResponse(
      NpmPackageId packageId,
      String repositoryBaseUrl,
      CachedPackage stored,
      boolean headOnly,
      NpmPackumentVariant variant) {
    Map<String, Object> copy = NpmMetadata.deepCopy(stored.packageRoot());
    NpmMetadata.rewriteTarballUrls(copy, packageId, repositoryBaseUrl);
    if (variant.abbreviated()) {
      copy = NpmMetadata.abbreviatePackageRoot(copy);
    }
    byte[] bytes = NpmResponseSupport.write(mapper, copy);
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, NpmResponseSupport.JSON, null, stored.lastModified());
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
        NpmResponseSupport.JSON, null, stored.lastModified());
  }

  private record CachedPackage(Map<String, Object> packageRoot, Instant lastModified) {}

  private NpmAssetWriter.Stored fetchAndCacheTarball(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      Instant now) {
    String url = buildRemoteUrl(runtime.proxyRemoteUrl(), packageId.tarballPath(tarballName));
    Conditional conditional = conditional(cached);
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, conditional.etag(), conditional.lastModified(), null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, packageId.tarballPath(tarballName), result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          Map<String, Object> attributes = refreshedAttributes(runtime, cached.get(), NexusCacheType.CONTENT, now);
          if (attributes == null) {
            assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          } else {
            assetDao.touchAssetLastUpdatedAndAttributes(cached.get().assetId(), now, attributes);
          }
          assetMetadataCache.touchVerified(runtime.id(), packageId.tarballPath(tarballName), now, attributes);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, packageId.tarballPath(tarballName));
          return null;
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, packageId.tarballPath(tarballName));
          return persistTarball(runtime, packageId, tarballName, result, !headOnly, now);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return null;
          }
          if (status == 404) negativeCache.rememberNotFound(runtime, packageId.tarballPath(tarballName));
          throw new NpmExceptions.NpmNotFoundException(
              "Tarball '" + tarballName + "' in package '" + packageId.id() + "' not found");
        }
        handleFailure(runtime, cached, "Upstream returned " + status, now);
        return null;
      });
    } catch (IOException e) {
      handleFailure(runtime, cached, "Upstream IO error: " + e.getMessage(), now);
      return null;
    }
  }

  private NpmAssetWriter.Stored persistTarball(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      HttpRemoteFetcher.Result result,
      boolean keepResponseFile,
      Instant now) {
    String contentType = result.contentType();
    if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
      throw new NpmExceptions.NpmNotFoundException(
          "Tarball '" + tarballName + "' in package '" + packageId.id() + "' not found");
    }
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    NpmAssetWriter.Stored stored = writer.writeTarball(runtime, storage, blobStoreId, packageId,
        inferVersion(packageId, tarballName), tarballName, result.body(),
        contentType, "proxy", runtime.proxyRemoteUrl(), remoteAttributes(result), keepResponseFile);
    updateCacheInfo(runtime, stored.asset(), NexusCacheType.CONTENT, now);
    proxyStateDao.recordSuccess(runtime.id(), now);
    return stored;
  }

  private void handleFailure(RepositoryRuntime runtime, Optional<CachedAssetMetadata> cached, String error, Instant now) {
    int failCount = proxyStateDao.loadState(runtime.id())
        .map(ProxyStateDao.ProxyRemoteState::failCount).orElse(0);
    long block = runtime.autoBlockOrDefault()
        ? BACKOFF_SECONDS[Math.min(failCount, BACKOFF_SECONDS.length - 1)]
        : 0;
    proxyStateDao.recordFailure(runtime.id(), block, error, now);
    if (cached.isPresent()) {
      return;
    }
    throw new NpmExceptions.BadUpstreamException(error);
  }

  private boolean isFresh(
      RepositoryRuntime runtime,
      CachedAssetMetadata snapshot,
      int ttlMinutes,
      Instant now,
      NexusCacheType type) {
    Optional<NexusLikeCacheInfo> cacheInfo = NexusLikeCacheInfo.fromAttributes(snapshot.attributes());
    if (cacheController != null && cacheInfo.isPresent()) {
      return !cacheController.isStale(runtime.id(), type, cacheInfo.get(), ttlMinutes, now);
    }
    if (snapshot.lastUpdatedAt() == null) return false;
    if (ttlMinutes < 0) return true;
    return snapshot.lastUpdatedAt().plusSeconds(ttlMinutes * 60L).isAfter(now);
  }

  private Conditional conditional(Optional<CachedAssetMetadata> cached) {
    if (cached.isEmpty() || cached.get().blob() == null) {
      return new Conditional(null, null);
    }
    String etag = stringAttr(cached.get().blob().attributes(), "remoteEtag");
    Instant lastModified = null;
    String lm = stringAttr(cached.get().blob().attributes(), "remoteLastModified");
    if (lm != null) {
      try { lastModified = Instant.parse(lm); } catch (RuntimeException ignored) {}
    }
    return new Conditional(etag, lastModified);
  }

  private Map<String, String> remoteAttributes(HttpRemoteFetcher.Result result) {
    Map<String, String> extras = new HashMap<>();
    if (result.etag() != null) extras.put("remoteEtag", result.etag());
    if (result.lastModified() != null) extras.put("remoteLastModified", result.lastModified().toString());
    return extras;
  }

  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  private void updateCacheInfo(
      RepositoryRuntime runtime,
      AssetRecord asset,
      NexusCacheType type,
      Instant now) {
    if (cacheController == null || asset == null) {
      return;
    }
    Map<String, Object> attrs = new HashMap<>(asset.attributes() == null ? Map.of() : asset.attributes());
    Map<String, Object> refreshed =
        NexusLikeCacheInfo.applyToAttributes(attrs, cacheController.current(runtime.id(), type, now));
    assetDao.updateAssetAttributes(asset.id(), refreshed);
  }

  private Map<String, Object> refreshedAttributes(
      RepositoryRuntime runtime,
      CachedAssetMetadata snapshot,
      NexusCacheType type,
      Instant now) {
    if (cacheController == null) {
      return null;
    }
    Map<String, Object> attrs = new HashMap<>(snapshot.attributes() == null ? Map.of() : snapshot.attributes());
    return NexusLikeCacheInfo.applyToAttributes(attrs, cacheController.current(runtime.id(), type, now));
  }

  private String inferVersion(NpmPackageId packageId, String tarballName) {
    String prefix = packageId.name() + "-";
    if (tarballName.startsWith(prefix) && tarballName.endsWith(".tgz")) {
      return tarballName.substring(prefix.length(), tarballName.length() - ".tgz".length());
    }
    return "";
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Proxy " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private String remotePackagePath(NpmPackageId packageId) {
    if (packageId.scope() == null) {
      return packageId.name();
    }
    return "@" + packageId.scope() + "%2F" + packageId.name();
  }

  private static String buildRemoteUrl(String base, String path) {
    return RemoteUrlBuilder.repositoryPathString(base, path);
  }

  private static String stringAttr(Map<String, Object> attrs, String key) {
    if (attrs == null) return null;
    Object v = attrs.get(key);
    return v == null ? null : v.toString();
  }

  private record Conditional(String etag, Instant lastModified) {}
}
