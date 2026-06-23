package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ProxyStateDao;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class PypiProxyService {
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};

  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final PypiAssetWriter writer;
  private final PypiAssetReader reader;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;

  public PypiProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      PypiAssetWriter writer,
      PypiAssetReader reader,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
    this.proxyStateDao = proxyStateDao;
    this.fetcher = fetcher;
    this.negativeCache = negativeCache;
    this.assetMetadataCache = assetMetadataCache;
    this.cacheController = cacheController;
  }

  public PypiResponse getRootIndex(RepositoryRuntime runtime, boolean headOnly) {
    return getIndexAsset(runtime, PypiPaths.INDEX_PREFIX, null, "root-index", headOnly);
  }

  public PypiResponse getIndex(RepositoryRuntime runtime, String projectName, boolean headOnly) {
    String normalized = PypiPaths.normalizeName(projectName);
    return getIndexAsset(runtime, PypiPaths.indexPath(normalized), normalized, "index", headOnly);
  }

  public PypiResponse getPackage(RepositoryRuntime runtime, String path, boolean headOnly) {
    ensureProxy(runtime);
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path);
    Instant now = Instant.now();
    NexusCacheType cacheType = cacheType(path);
    if (cached.isPresent()
        && isFresh(runtime, cached.get(), runtime.contentMaxAgeMinutesOrDefault(), now, cacheType)) {
      return reader.serveSnapshot(cached.get(), headOnly, path);
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new PypiExceptions.PypiNotFoundException(path);
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return reader.serveSnapshot(cached.get(), headOnly, path);
      throw new PypiExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }

    String projectName = PypiPaths.packageNameFromPath(path);
    String filename = PypiPaths.fileName(path);
    String url;
    try {
      url = resolvePackageUrl(runtime, projectName, filename);
    } catch (PypiExceptions.PypiNotFoundException e) {
      proxyStateDao.recordSuccess(runtime.id(), now);
      if (cached.isPresent()) return reader.serveSnapshot(cached.get(), headOnly, path);
      throw e;
    } catch (PypiExceptions.BadUpstreamException e) {
      return handleUpstreamFailure(runtime, path, cached, headOnly, e.getMessage(), now);
    }
    return fetchAndCachePackage(runtime, path, url, cached, headOnly, now);
  }

  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  private PypiResponse getIndexAsset(
      RepositoryRuntime runtime, String path, String projectName, String kind, boolean headOnly) {
    ensureProxy(runtime);
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path);
    Instant now = Instant.now();
    if (cached.isPresent()
        && isFresh(runtime, cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now, NexusCacheType.METADATA)) {
      return reader.serveSnapshot(cached.get(), headOnly, path);
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new PypiExceptions.PypiNotFoundException(path);
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return reader.serveSnapshot(cached.get(), headOnly, path);
      throw new PypiExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }

    String url = buildRemoteUrl(runtime.proxyRemoteUrl(), path);
    String etag = null;
    Instant lastModified = null;
    if (cached.isPresent() && cached.get().blob() != null) {
      Map<String, Object> attrs = cached.get().blob().attributes();
      etag = stringAttr(attrs, "remoteEtag");
      lastModified = instantAttr(attrs, "remoteLastModified");
    }
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, etag, lastModified, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, path, result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          Map<String, Object> attributes = refreshedAttributes(runtime, cached.get(), NexusCacheType.METADATA, now);
          if (attributes == null) {
            assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          } else {
            assetDao.touchAssetLastUpdatedAndAttributes(cached.get().assetId(), now, attributes);
          }
          assetMetadataCache.touchVerified(runtime.id(), path, now, attributes);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path);
          return reader.serveSnapshot(cached.get(), headOnly, path);
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, path);
          byte[] upstream = UpstreamBodyReadException.readAllBytes(result.body());
          String transformed = transformIndex(kind, projectName,
              new String(upstream, StandardCharsets.UTF_8));
          PypiAssetWriter.Stored stored = writer.write(
              runtime,
              blobStorage(runtime),
              requireBlobStore(runtime),
              path,
              new ByteArrayInputStream(transformed.getBytes(StandardCharsets.UTF_8)),
              "text/html",
              kind,
              null,
              cacheAttributes(runtime, NexusCacheType.METADATA, now,
                  projectName == null ? Map.of() : Map.of("name", projectName)),
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
          throw new PypiExceptions.PypiNotFoundException(path);
        }
        return handleUpstreamFailure(runtime, path, cached, headOnly,
            "Upstream returned " + status, now);
      });
    } catch (IOException e) {
      return handleUpstreamFailure(runtime, path, cached, headOnly,
          "Upstream IO error: " + e.getMessage(), now);
    }
  }

  private PypiResponse fetchAndCachePackage(
      RepositoryRuntime runtime, String path, String url, Optional<CachedAssetMetadata> cached,
      boolean headOnly, Instant now) {
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
      return fetcher.fetchWithBodyRetry(req, path, result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          NexusCacheType cacheType = cacheType(path);
          Map<String, Object> attributes = refreshedAttributes(runtime, cached.get(), cacheType, now);
          if (attributes == null) {
            assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          } else {
            assetDao.touchAssetLastUpdatedAndAttributes(cached.get().assetId(), now, attributes);
          }
          assetMetadataCache.touchVerified(runtime.id(), path, now, attributes);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path);
          return reader.serveSnapshot(cached.get(), headOnly, path);
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, path);
          PypiAssetWriter.PackageCoordinate coordinate = coordinateFromPackagePath(path);
          Map<String, Object> attrs = new LinkedHashMap<>();
          attrs.put("name", coordinate.originalName());
          attrs.put("normalizedName", coordinate.normalizedName());
          attrs.put("version", coordinate.version());
          attrs.put("remoteUrl", url);
          NexusCacheType cacheType = cacheType(path);
          PypiAssetWriter.Stored stored = writer.write(
              runtime,
              blobStorage(runtime),
              requireBlobStore(runtime),
              path,
              result.body(),
              result.contentType(),
              path.endsWith(".asc") ? "package-signature" : "package",
              coordinate,
              cacheAttributes(runtime, cacheType, now, attrs),
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
          throw new PypiExceptions.PypiNotFoundException(path);
        }
        return handleUpstreamFailure(runtime, path, cached, headOnly,
            "Upstream returned " + status, now);
      });
    } catch (IOException e) {
      return handleUpstreamFailure(runtime, path, cached, headOnly,
          "Upstream IO error: " + e.getMessage(), now);
    }
  }

  private String transformIndex(String kind, String projectName, String html) {
    List<PypiLink> links = PypiIndex.parse(html);
    if ("root-index".equals(kind)) {
      return PypiIndex.buildRoot(PypiIndex.rewriteRootLinks(links));
    }
    if (!PypiIndex.validatesForProject(projectName, links)) {
      throw new PypiExceptions.PypiNotFoundException(projectName);
    }
    return PypiIndex.buildProject(
        projectName,
        PypiIndex.rewriteProjectLinks(PypiPaths.normalizeName(projectName), links));
  }

  private PypiResponse responseFromStored(PypiAssetWriter.Stored stored, boolean headOnly) {
    try {
      if (headOnly) {
        stored.discardBody();
        return PypiResponse.noBody(200, stored.blob().size(), stored.asset().contentType(),
            stored.blob().sha1(), stored.asset().lastUpdatedAt());
      }
      return PypiResponse.ok(stored.openBody(), stored.blob().size(), stored.asset().contentType(),
          stored.blob().sha1(), stored.asset().lastUpdatedAt());
    } catch (RuntimeException e) {
      stored.discardBody();
      throw e;
    }
  }

  private String resolvePackageUrl(RepositoryRuntime runtime, String projectName, String filename) {
    if (projectName == null || projectName.isBlank()) {
      throw new PypiExceptions.PypiNotFoundException(filename);
    }
    List<PypiLink> links = fetchRemoteProjectLinks(runtime, projectName);
    String rootFilename = filename.endsWith(".asc")
        ? filename.substring(0, filename.length() - 4)
        : filename;
    PypiLink match = links.stream()
        .filter(link -> rootFilename.equals(link.file()))
        .findFirst()
        .orElseThrow(() -> new PypiExceptions.PypiNotFoundException(filename));
    String href = match.href();
    int hashIdx = href.indexOf('#');
    if (hashIdx >= 0) href = href.substring(0, hashIdx);
    URI base = RemoteUrlBuilder.repositoryPath(runtime.proxyRemoteUrl(), PypiPaths.indexPath(projectName));
    return base.resolve(href).toString();
  }

  private List<PypiLink> fetchRemoteProjectLinks(RepositoryRuntime runtime, String projectName) {
    String path = PypiPaths.indexPath(projectName);
    HttpRemoteFetcher.Request req = HttpRemoteFetcher.Request.get(
        buildRemoteUrl(runtime.proxyRemoteUrl(), path))
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, path, result -> {
        if (result.status() == 404 || result.status() == 410) {
          throw new PypiExceptions.PypiNotFoundException(projectName);
        }
        if (result.status() < 200 || result.status() >= 300) {
          throw new PypiExceptions.BadUpstreamException("Upstream returned " + result.status());
        }
        String html = new String(UpstreamBodyReadException.readAllBytes(result.body()), StandardCharsets.UTF_8);
        List<PypiLink> links = PypiIndex.parse(html);
        if (!PypiIndex.validatesForProject(projectName, links)) {
          throw new PypiExceptions.PypiNotFoundException(projectName);
        }
        return links;
      });
    } catch (IOException e) {
      throw new PypiExceptions.BadUpstreamException("Upstream IO error: " + e.getMessage(), e);
    }
  }

  private PypiResponse handleUpstreamFailure(RepositoryRuntime runtime, String path,
      Optional<CachedAssetMetadata> cached, boolean headOnly, String error, Instant now) {
    int failCount = proxyStateDao.loadState(runtime.id())
        .map(ProxyStateDao.ProxyRemoteState::failCount).orElse(0);
    long block = runtime.autoBlockOrDefault()
        ? BACKOFF_SECONDS[Math.min(failCount, BACKOFF_SECONDS.length - 1)]
        : 0;
    proxyStateDao.recordFailure(runtime.id(), block, error, now);
    if (cached.isPresent()) return reader.serveSnapshot(cached.get(), headOnly, path);
    throw new PypiExceptions.BadUpstreamException(error);
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

  private PypiAssetWriter.PackageCoordinate coordinateFromPackagePath(String path) {
    String name = PypiPaths.packageNameFromPath(path);
    String version = PypiPaths.versionFromPath(path);
    String filename = PypiPaths.fileName(path);
    String originalName = name.isBlank() ? PypiPaths.nameFromFilename(filename) : name;
    if (version.isBlank()) {
      version = PypiPaths.versionFromFilename(filename);
    }
    return new PypiAssetWriter.PackageCoordinate(
        originalName, PypiPaths.normalizeName(originalName), version, null);
  }

  private Map<String, String> remoteAttrs(HttpRemoteFetcher.Result result) {
    Map<String, String> attrs = new HashMap<>();
    if (result.etag() != null) attrs.put("remoteEtag", result.etag());
    if (result.lastModified() != null) attrs.put("remoteLastModified", result.lastModified().toString());
    return attrs;
  }

  private Map<String, Object> cacheAttributes(
      RepositoryRuntime runtime,
      NexusCacheType type,
      Instant now,
      Map<String, Object> base) {
    Map<String, Object> attrs = new LinkedHashMap<>(base == null ? Map.of() : base);
    if (cacheController == null) {
      return attrs;
    }
    return NexusLikeCacheInfo.applyToAttributes(attrs, cacheController.current(runtime.id(), type, now));
  }

  private Map<String, Object> refreshedAttributes(
      RepositoryRuntime runtime,
      CachedAssetMetadata snapshot,
      NexusCacheType type,
      Instant now) {
    if (cacheController == null) {
      return null;
    }
    return NexusLikeCacheInfo.applyToAttributes(
        snapshot.attributes() == null ? Map.of() : snapshot.attributes(),
        cacheController.current(runtime.id(), type, now));
  }

  private void ensureProxy(RepositoryRuntime runtime) {
    if (!runtime.isProxy()) {
      throw new IllegalStateException("PypiProxyService called on non-proxy " + runtime.name());
    }
  }

  private BlobStorage blobStorage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime));
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("PyPI proxy " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private static NexusCacheType cacheType(String path) {
    return path != null && path.endsWith(".asc") ? NexusCacheType.METADATA : NexusCacheType.CONTENT;
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
}
