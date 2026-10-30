package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
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
import com.github.klboke.kkrepo.server.proxy.ProxyRequestAudit;
import com.github.klboke.kkrepo.server.releaseage.MinimumReleaseAge;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * PyPI proxy.
 *
 * <p>Minimum release age (quarantine): when {@code proxy.minimumReleaseAgeMinutes > 0} the project
 * index is fetched as PEP 691 JSON so each file's PEP 700 {@code upload-time} is known. The stored
 * copy keeps every file with a {@code data-upload-time} attribute (marked by the {@code uploadTimes}
 * asset attribute); files younger than the cooldown are removed each time the index is served, so a
 * file appears as soon as it matures instead of when the cached page expires. Direct downloads of
 * quarantined files, and of files whose upload time is unknown, get 403. Upstreams that do not
 * publish upload times (Nexus, Artifactory) fail closed with 502 rather than silently serving
 * unfiltered content. Everything is derived from shared state; the node-local {@code publishTimes}
 * cache only avoids re-parsing the index and holds immutable timestamps, so replicas agree.
 */
@Service
public class PypiProxyService {
  private static final Logger log = LoggerFactory.getLogger(PypiProxyService.class);
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};
  private static final String SIMPLE_JSON = "application/vnd.pypi.simple.v1+json";
  private static final String UPLOAD_TIMES_ATTRIBUTE = "uploadTimes";

  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final PypiAssetWriter writer;
  private final PypiAssetReader reader;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;
  private final PypiRepositorySettings repositorySettings;
  private final Clock clock;
  private final LocalCache<String, Instant> publishTimes = LocalCacheFactory.standard()
      .<String, Instant>builder("pypi-release-age-publish-times")
      .maximumSize(200_000)
      .expireAfterWrite(Duration.ofHours(6))
      .build();

  @Autowired
  public PypiProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      PypiAssetWriter writer,
      PypiAssetReader reader,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      PypiRepositorySettings repositorySettings) {
    this(assetDao, blobStorageRegistry, writer, reader, proxyStateDao, fetcher, negativeCache,
        assetMetadataCache, cacheController, repositorySettings, Clock.systemUTC());
  }

  PypiProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      PypiAssetWriter writer,
      PypiAssetReader reader,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      PypiRepositorySettings repositorySettings,
      Clock clock) {
    this.clock = clock;
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
    this.proxyStateDao = proxyStateDao;
    this.fetcher = fetcher;
    this.negativeCache = negativeCache;
    this.assetMetadataCache = assetMetadataCache;
    this.cacheController = cacheController;
    this.repositorySettings = repositorySettings;
  }

  public PypiResponse getRootIndex(RepositoryRuntime runtime, boolean headOnly) {
    return getIndexAsset(runtime, PypiPaths.INDEX_PREFIX, null, "root-index", headOnly, false);
  }

  public PypiResponse getIndex(RepositoryRuntime runtime, String projectName, boolean headOnly) {
    String normalized = PypiPaths.normalizeName(projectName);
    if (runtime.minimumReleaseAgeEnabled()) {
      return filteredIndex(runtime, normalized, headOnly);
    }
    return getIndexAsset(
        runtime, PypiPaths.indexPath(normalized), normalized, "index", headOnly, false);
  }

  public PypiResponse getPackage(RepositoryRuntime runtime, String path, boolean headOnly) {
    ensureProxy(runtime);
    if (runtime.minimumReleaseAgeEnabled()) {
      // Decide before the cache lookup so files cached before the policy was enabled are gated too.
      enforcePackageAge(runtime, path);
    }
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
      throw temporarilyBlocked(runtime, path);
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
      RepositoryRuntime runtime, String path, String projectName, String kind, boolean headOnly,
      boolean withUploadTimes) {
    ensureProxy(runtime);
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path);
    if (withUploadTimes && cached.isPresent()
        && !"true".equals(stringAttr(cached.get().attributes(), UPLOAD_TIMES_ATTRIBUTE))) {
      // Cached before the policy was enabled: it has no upload times, so refetch instead of
      // serving (or revalidating) it.
      cached = Optional.empty();
    }
    final Optional<CachedAssetMetadata> cachedIndex = cached;
    Instant now = Instant.now();
    if (cachedIndex.isPresent()
        && isFresh(runtime, cachedIndex.get(), runtime.metadataMaxAgeMinutesOrDefault(), now, NexusCacheType.METADATA)) {
      return reader.serveSnapshot(cachedIndex.get(), headOnly, path);
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new PypiExceptions.PypiNotFoundException(path);
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cachedIndex.isPresent()) return reader.serveSnapshot(cachedIndex.get(), headOnly, path);
      throw temporarilyBlocked(runtime, path);
    }

    String url = upstreamIndexUrl(runtime, projectName);
    String etag = null;
    Instant lastModified = null;
    if (cachedIndex.isPresent() && cachedIndex.get().blob() != null) {
      Map<String, Object> attrs = cachedIndex.get().blob().attributes();
      etag = stringAttr(attrs, "remoteEtag");
      lastModified = instantAttr(attrs, "remoteLastModified");
    }
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, etag, lastModified, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withRepositoryForContent(runtime);
    if (withUploadTimes) {
      req = req.withAccept(SIMPLE_JSON);
    }
    try {
      return fetcher.fetchWithBodyRetry(req, path, result -> {
        int status = result.status();
        if (status == 304 && cachedIndex.isPresent()) {
          Map<String, Object> attributes = refreshedAttributes(runtime, cachedIndex.get(), NexusCacheType.METADATA, now);
          if (attributes == null) {
            assetDao.touchAssetLastUpdated(cachedIndex.get().assetId(), now);
          } else {
            assetDao.touchAssetLastUpdatedAndAttributes(cachedIndex.get().assetId(), now, attributes);
          }
          assetMetadataCache.touchVerified(runtime.id(), path, now, attributes);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path);
          return reader.serveSnapshot(cachedIndex.get(), headOnly, path);
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, path);
          byte[] upstream = UpstreamBodyReadException.readAllBytes(result.body());
          String transformed = withUploadTimes
              ? transformJsonIndex(projectName, result.contentType(),
                  new String(upstream, StandardCharsets.UTF_8))
              : transformIndex(kind, projectName, new String(upstream, StandardCharsets.UTF_8));
          PypiAssetWriter.Stored stored = writer.write(
              runtime,
              blobStorage(runtime),
              requireBlobStore(runtime),
              path,
              new ByteArrayInputStream(transformed.getBytes(StandardCharsets.UTF_8)),
              "text/html",
              kind,
              null,
              cacheAttributes(runtime, NexusCacheType.METADATA, now, indexAttributes(projectName, withUploadTimes)),
              remoteAttrs(result),
              "proxy",
              ProxyRequestAudit.currentClientIp(),
              !headOnly);
          proxyStateDao.recordSuccess(runtime.id(), now);
          return responseFromStored(stored, headOnly);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cachedIndex.isPresent()) {
            return reader.serveSnapshot(cachedIndex.get(), headOnly, path);
          }
          if (status == 404) negativeCache.rememberNotFound(runtime, path);
          throw new PypiExceptions.PypiNotFoundException(path);
        }
        return handleUpstreamFailure(runtime, path, cachedIndex, headOnly,
            "Upstream returned " + status, now);
      });
    } catch (IOException e) {
      return handleUpstreamFailure(runtime, path, cachedIndex, headOnly,
          "Upstream IO error: " + e.getMessage(), now);
    }
  }

  private static Map<String, Object> indexAttributes(String projectName, boolean withUploadTimes) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    if (projectName != null) attributes.put("name", projectName);
    if (withUploadTimes) attributes.put(UPLOAD_TIMES_ATTRIBUTE, "true");
    return attributes;
  }

  /** Project page from a PEP 691 JSON response; keeps each file's upload time for later filtering. */
  private String transformJsonIndex(String projectName, String contentType, String body) {
    if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).contains("json")) {
      throw new PypiExceptions.BadUpstreamException(
          "Upstream does not provide the PEP 691/700 JSON simple index, so the minimum release age "
              + "cannot be enforced; use an upstream that publishes upload-time (for example pypi.org)");
    }
    List<PypiLink> links;
    try {
      links = PypiIndex.parseJson(body);
    } catch (IOException e) {
      throw new PypiExceptions.BadUpstreamException("Unreadable JSON simple index: " + e.getMessage(), e);
    }
    if (!PypiIndex.validatesForProject(projectName, links)) {
      throw new PypiExceptions.PypiNotFoundException(projectName);
    }
    return PypiIndex.buildProject(
        projectName,
        PypiIndex.rewriteProjectLinks(PypiPaths.normalizeName(projectName), links),
        true);
  }

  // ---- minimum release age -------------------------------------------------------------------

  /** Serves the project page with files younger than the cooldown (or of unknown age) removed. */
  private PypiResponse filteredIndex(RepositoryRuntime runtime, String normalized, boolean headOnly) {
    List<PypiLink> links = indexLinksWithUploadTimes(runtime, normalized);
    int minutes = runtime.minimumReleaseAgeMinutesOrDefault();
    Instant now = clock.instant();
    List<PypiLink> kept = links.stream()
        .filter(link -> MinimumReleaseAge.eligible(link.uploadTime(), minutes, now))
        .toList();
    if (kept.isEmpty() && !links.isEmpty()) {
      throw new PypiExceptions.PypiNotFoundException(normalized);
    }
    byte[] html = PypiIndex.buildProject(normalized, kept, false).getBytes(StandardCharsets.UTF_8);
    String etag = sha256(html);
    return headOnly
        ? PypiResponse.noBody(200, html.length, "text/html", etag, now)
        : PypiResponse.ok(new ByteArrayInputStream(html), html.length, "text/html", etag, now);
  }

  private void enforcePackageAge(RepositoryRuntime runtime, String path) {
    String projectName = PypiPaths.packageNameFromPath(path);
    if (projectName == null || projectName.isBlank()) {
      return; // not a project file path; the normal flow answers 404
    }
    String filename = PypiPaths.fileName(path);
    String rootFilename = filename.endsWith(".asc")
        ? filename.substring(0, filename.length() - 4)
        : filename;
    String normalized = PypiPaths.normalizeName(projectName);
    String key = publishKey(runtime, normalized, rootFilename);
    Instant published = publishTimes.getIfPresent(key);
    if (published == null) {
      List<PypiLink> links = indexLinksWithUploadTimes(runtime, normalized);
      if (links.stream().noneMatch(link -> rootFilename.equals(link.file()))) {
        throw new PypiExceptions.PypiNotFoundException(filename);
      }
      published = publishTimes.getIfPresent(key);
    }
    MinimumReleaseAge.require(
        "PyPI file " + rootFilename, published, runtime.minimumReleaseAgeMinutesOrDefault(),
        clock.instant());
  }

  /** The cached project page (refetched if needed) with every file and its upload time. */
  private List<PypiLink> indexLinksWithUploadTimes(RepositoryRuntime runtime, String normalized) {
    PypiResponse raw = getIndexAsset(
        runtime, PypiPaths.indexPath(normalized), normalized, "index", false, true);
    String html;
    try (InputStream body = raw.body()) {
      html = new String(body == null ? new byte[0] : body.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new PypiExceptions.BadUpstreamException("Failed reading cached index: " + e.getMessage(), e);
    }
    List<PypiLink> links = PypiIndex.parse(html);
    for (PypiLink link : links) {
      if (link.uploadTime() != null) {
        publishTimes.put(publishKey(runtime, normalized, link.file()), link.uploadTime());
      }
    }
    return links;
  }

  /**
   * Cache identity of a publish time. It includes the upstream the time was read from: a proxy's
   * remote URL can be edited without any signal reaching this node-local cache, and a timestamp
   * learned from the former upstream must never answer for the new one (a warm node would otherwise
   * admit what a cold node rejects).
   */
  private static String publishKey(RepositoryRuntime runtime, String normalized, String file) {
    return runtime.id() + "|" + runtime.proxyRemoteUrl() + "|" + normalized + "|" + file;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
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
        .withRepositoryForContent(runtime);
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
          String kind = path.endsWith(".asc") ? "package-signature" : "package";
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
              kind,
              coordinate,
              cacheAttributes(runtime, cacheType, now, attrs),
              remoteAttrs(result),
              "proxy",
              ProxyRequestAudit.currentClientIp(),
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
      reader.beforeRead(
          stored.asset().id(), stored.blob().id(), stored.asset().repositoryId());
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
    String upstreamIndexPath = upstreamIndexPath(
        repositorySettings.get(runtime).indexPath(), projectName);
    List<PypiLink> links = fetchRemoteProjectLinks(runtime, projectName, upstreamIndexPath);
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
    URI base = RemoteUrlBuilder.repositoryPath(runtime.proxyRemoteUrl(), upstreamIndexPath);
    return base.resolve(href).toString();
  }

  private List<PypiLink> fetchRemoteProjectLinks(
      RepositoryRuntime runtime, String projectName, String upstreamIndexPath) {
    String path = PypiPaths.indexPath(projectName);
    HttpRemoteFetcher.Request req = HttpRemoteFetcher.Request.get(
        buildRemoteUrl(runtime.proxyRemoteUrl(), upstreamIndexPath))
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withRepositoryForContent(runtime);
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
    log.warn(
        "PyPI proxy upstream request failed: repository={} path={} failCount={} blockSeconds={} error={}",
        runtime.name(), proxyLogValue(path), failCount + 1, block, proxyLogValue(error));
    proxyStateDao.recordFailure(runtime.id(), block, error, now);
    if (cached.isPresent()) return reader.serveSnapshot(cached.get(), headOnly, path);
    throw new PypiExceptions.BadUpstreamException(error);
  }

  private PypiExceptions.BadUpstreamException temporarilyBlocked(
      RepositoryRuntime runtime, String path) {
    if (log.isDebugEnabled()) {
      Optional<ProxyStateDao.ProxyRemoteState> state = proxyStateDao.loadState(runtime.id());
      if (state.isPresent()) {
        ProxyStateDao.ProxyRemoteState blocked = state.orElseThrow();
        log.debug(
            "PyPI proxy request skipped while upstream is blocked: repository={} path={} "
                + "failCount={} blockedUntil={} lastFailureAt={} lastError={}",
            runtime.name(),
            proxyLogValue(path),
            blocked.failCount(),
            blocked.blockedUntil(),
            blocked.lastFailureAt(),
            proxyLogValue(blocked.lastError()));
      } else {
        log.debug(
            "PyPI proxy request skipped while upstream is blocked: repository={} path={} state=unavailable",
            runtime.name(), proxyLogValue(path));
      }
    }
    return new PypiExceptions.BadUpstreamException(
        "Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
  }

  private static String proxyLogValue(String value) {
    if (value == null || value.isBlank()) return "-";
    String normalized = value.replaceAll("[\\p{Cntrl}]", " ");
    return normalized.length() <= 1024 ? normalized : normalized.substring(0, 1024);
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

  private String upstreamIndexUrl(RepositoryRuntime runtime, String projectName) {
    return buildRemoteUrl(runtime.proxyRemoteUrl(), upstreamIndexPath(
        repositorySettings.get(runtime).indexPath(), projectName));
  }

  private static String upstreamIndexPath(String configured, String projectName) {
    String normalizedProject = projectName == null ? null : PypiPaths.normalizeName(projectName);
    return PypiRemoteIndexPath.upstreamPath(configured, normalizedProject);
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
