package com.github.klboke.kkrepo.server.pub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.protocol.pub.PubContentTypes;
import com.github.klboke.kkrepo.protocol.pub.PubPackageName;
import com.github.klboke.kkrepo.protocol.pub.PubPath;
import com.github.klboke.kkrepo.protocol.pub.PubPaths;
import com.github.klboke.kkrepo.protocol.pub.PubVersions;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PubProxyService {
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};
  private static final Set<String> PUB_ARCHIVE_REDIRECT_HOSTS = Set.of(
      "storage.googleapis.com",
      "storage-download.googleapis.com",
      "pub-packages.storage.googleapis.com");
  static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final PubAssetWriter writer;
  private final PubAssetReader reader;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;
  private final ObjectMapper objectMapper;

  public PubProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      PubAssetWriter writer,
      PubAssetReader reader,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      ObjectMapper objectMapper) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
    this.proxyStateDao = proxyStateDao;
    this.fetcher = fetcher;
    this.negativeCache = negativeCache;
    this.assetMetadataCache = assetMetadataCache;
    this.objectMapper = objectMapper;
  }

  public MavenResponse get(RepositoryRuntime runtime, PubPath path, String baseUrl, boolean headOnly) {
    ensureProxy(runtime);
    return switch (path.kind()) {
      case PACKAGE_METADATA -> packageMetadata(runtime, path.packageName(), baseUrl, headOnly);
      case VERSION_METADATA -> versionMetadata(runtime, path.packageName(), path.version(), baseUrl, headOnly);
      case VERSION_JSON -> versionJson(runtime, path.packageName(), path.version(), headOnly);
      case ARCHIVE -> download(runtime, path.packageName(), path.version(), headOnly);
      case PACKAGE_NAMES, PACKAGE_NAME_COMPLETION -> packageNames(headOnly);
      case PUBLISH_INIT, PUBLISH_UPLOAD, PUBLISH_FINALIZE ->
          throw new PubExceptions.PubNotFoundException(path.rawPath());
      default -> throw new PubExceptions.PubNotFoundException(path.rawPath());
    };
  }

  MavenResponse packageMetadata(RepositoryRuntime runtime, String packageName, String baseUrl, boolean headOnly) {
    String normalized = PubPackageName.require(packageName);
    CachedMetadata metadata = cachedOrFetchedMetadata(runtime, normalized, Instant.now());
    Map<String, Object> rewritten = rewriteMetadata(normalized, metadata.body(), baseUrl);
    return PubResponses.json(objectMapper, rewritten, 200, metadata.etag(), metadata.lastModified(), headOnly);
  }

  MavenResponse versionMetadata(
      RepositoryRuntime runtime,
      String packageName,
      String version,
      String baseUrl,
      boolean headOnly) {
    String safeVersion = PubVersions.require(version);
    CachedMetadata metadata = cachedOrFetchedMetadata(runtime, packageName, Instant.now());
    Map<String, Object> body = rewriteMetadata(packageName, metadata.body(), baseUrl);
    for (Map<String, Object> entry : versions(body)) {
      if (safeVersion.equals(String.valueOf(entry.get("version")))) {
        return PubResponses.json(objectMapper, entry, 200, metadata.etag(), metadata.lastModified(), headOnly);
      }
    }
    throw new PubExceptions.PubNotFoundException(packageName + " " + safeVersion);
  }

  MavenResponse versionJson(
      RepositoryRuntime runtime,
      String packageName,
      String version,
      boolean headOnly) {
    String safeVersion = PubVersions.require(version);
    CachedMetadata metadata = cachedOrFetchedMetadata(runtime, packageName, Instant.now());
    for (Map<String, Object> entry : versions(metadata.body())) {
      if (safeVersion.equals(String.valueOf(entry.get("version")))) {
        return PubResponses.json(objectMapper, entry, 200, metadata.etag(), metadata.lastModified(),
            PubContentTypes.VERSION_JSON, headOnly);
      }
    }
    throw new PubExceptions.PubNotFoundException(packageName + " " + safeVersion);
  }

  MavenResponse download(RepositoryRuntime runtime, String packageName, String version, boolean headOnly) {
    ensureProxy(runtime);
    String path = PubPaths.archivePath(packageName, version);
    Instant now = Instant.now();
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path);
    if (cached.isPresent() && isFresh(cached.get(), runtime.contentMaxAgeMinutesOrDefault(), now)) {
      return reader.serveSnapshot(cached.get(), headOnly, path);
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new PubExceptions.PubNotFoundException(path);
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) {
        return reader.serveSnapshot(cached.get(), headOnly, path);
      }
      throw new PubExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    Map<String, Object> remoteVersion = remoteVersion(runtime, packageName, version, now)
        .orElseThrow(() -> new PubExceptions.PubNotFoundException(packageName + " " + version));
    String archiveUrl = text(remoteVersion.get("archive_url"));
    if (archiveUrl == null) {
      throw new PubExceptions.BadUpstreamException("Pub metadata is missing archive_url for " + packageName);
    }
    String expectedSha256 = text(remoteVersion.get("archive_sha256"));
    HttpRemoteFetcher.Request req = HttpRemoteFetcher.Request.get(archiveUrl)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
        .withRepositoryAllowingUnsignedRedirects(
            runtime,
            sameOrigin(runtime.proxyRemoteUrl(), archiveUrl),
            PUB_ARCHIVE_REDIRECT_HOSTS);
    try {
      return fetcher.fetchWithBodyRetry(req, path, result -> {
        int status = result.status();
        if (status >= 200 && status < 300) {
          PubAssetWriter.Stored stored = writer.writeArchive(
              runtime,
              blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime)),
              requireBlobStore(runtime),
              result.body(),
              packageName,
              version,
              expectedSha256,
              Map.of("cacheSource", "proxy", "sourceRepository", runtime.name()),
              archiveRemoteAttrs(result, expectedSha256),
              "proxy",
              runtime.proxyRemoteUrl(),
              true,
              !headOnly);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path);
          if (headOnly) {
            stored.discardBody();
            return MavenResponse.noBody(200, stored.digests().size(),
                stored.asset().contentType(), stored.digests().sha1(), stored.asset().lastUpdatedAt());
          }
          return MavenResponse.ok(stored::openBody, stored.digests().size(),
              stored.asset().contentType(), stored.digests().sha1(), stored.asset().lastUpdatedAt());
        }
        if (isNegative(status)) {
          negativeCache.rememberNotFound(runtime, path);
          throw new PubExceptions.PubNotFoundException(path);
        }
        recordFailure(runtime, status, now);
        throw new PubExceptions.BadUpstreamException("Pub upstream archive returned " + status);
      });
    } catch (IOException e) {
      if (cached.isPresent()) {
        return reader.serveSnapshot(cached.get(), headOnly, path);
      }
      recordFailure(runtime, 0, now);
      throw new PubExceptions.BadUpstreamException("Failed fetching Pub archive", e);
    }
  }

  Optional<Map<String, Object>> remoteVersion(
      RepositoryRuntime runtime,
      String packageName,
      String version,
      Instant now) {
    String safeVersion = PubVersions.require(version);
    CachedMetadata metadata = cachedOrFetchedMetadata(runtime, packageName, now);
    for (Map<String, Object> entry : versions(metadata.body())) {
      if (safeVersion.equals(String.valueOf(entry.get("version")))) {
        return Optional.of(entry);
      }
    }
    return Optional.empty();
  }

  CachedMetadata cachedOrFetchedMetadata(RepositoryRuntime runtime, String packageName, Instant now) {
    ensureProxy(runtime);
    String normalized = PubPackageName.require(packageName);
    String path = PubPaths.metadataPath(normalized);
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path);
    if (cached.isPresent() && isFresh(cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now)) {
      return new CachedMetadata(parseJson(reader.readText(cached.get(), path)), cached.get().blob().sha1(),
          cached.get().lastUpdatedAt());
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new PubExceptions.PubNotFoundException(normalized);
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) {
        return new CachedMetadata(parseJson(reader.readText(cached.get(), path)), cached.get().blob().sha1(),
            cached.get().lastUpdatedAt());
      }
      throw new PubExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    return fetchAndCacheMetadata(runtime, normalized, path, cached, now);
  }

  private CachedMetadata fetchAndCacheMetadata(
      RepositoryRuntime runtime,
      String packageName,
      String path,
      Optional<CachedAssetMetadata> cached,
      Instant now) {
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        RemoteUrlBuilder.repositoryPathString(runtime.proxyRemoteUrl(), path),
        remoteEtag(cached),
        remoteLastModified(cached),
        null,
        false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, path, result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          assetMetadataCache.touchVerified(runtime.id(), path, now);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path);
          return new CachedMetadata(parseJson(reader.readText(cached.get(), path)), cached.get().blob().sha1(), now);
        }
        if (status >= 200 && status < 300) {
          byte[] body = UpstreamBodyReadException.readAllBytes(result.body());
          Map<String, Object> parsed = parseJson(new String(body, StandardCharsets.UTF_8));
          validateMetadata(packageName, parsed);
          PubAssetWriter.Stored stored = writer.writeMetadata(
              runtime,
              blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime)),
              requireBlobStore(runtime),
              path,
              body,
              Map.of("packageName", packageName),
              remoteAttrs(result),
              false);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path);
          stored.discardBody();
          return new CachedMetadata(parsed, stored.digests().sha1(), stored.asset().lastUpdatedAt());
        }
        if (isNegative(status)) {
          negativeCache.rememberNotFound(runtime, path);
          throw new PubExceptions.PubNotFoundException(packageName);
        }
        recordFailure(runtime, status, now);
        throw new PubExceptions.BadUpstreamException("Pub upstream metadata returned " + status);
      });
    } catch (IOException e) {
      if (cached.isPresent()) {
        return new CachedMetadata(parseJson(reader.readText(cached.get(), path)), cached.get().blob().sha1(),
            cached.get().lastUpdatedAt());
      }
      recordFailure(runtime, 0, now);
      throw new PubExceptions.BadUpstreamException("Failed fetching Pub metadata", e);
    }
  }

  private Map<String, Object> rewriteMetadata(String packageName, Map<String, Object> body, String baseUrl) {
    Map<String, Object> rewritten = new LinkedHashMap<>(body);
    rewritten.remove("advisoriesUpdated");
    List<Map<String, Object>> versionEntries = versions(body).stream()
        .map(entry -> rewriteVersion(entry, baseUrl, packageName))
        .toList();
    rewritten.put("name", packageName);
    rewritten.put("versions", versionEntries);
    Object latest = body.get("latest");
    if (latest instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> latestMap = (Map<String, Object>) map;
      rewritten.put("latest", rewriteVersion(latestMap, baseUrl, packageName));
    } else if (!versionEntries.isEmpty()) {
      List<Map<String, Object>> sorted = versionEntries.stream()
          .sorted(java.util.Comparator.comparing(
              entry -> String.valueOf(entry.get("version")), PubVersions.COMPARATOR))
          .toList();
      rewritten.put("latest", PubMetadataSupport.latestStableFirst(
          sorted, entry -> String.valueOf(entry.get("version"))));
    }
    return rewritten;
  }

  private Map<String, Object> rewriteVersion(Map<String, Object> entry, String baseUrl, String packageName) {
    Map<String, Object> rewritten = new LinkedHashMap<>(entry);
    String version = PubVersions.require(String.valueOf(entry.get("version")));
    rewritten.put("archive_url", baseUrl + "/" + PubPaths.apiArchivePath(packageName, version));
    return rewritten;
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> versions(Map<String, Object> body) {
    Object value = body.get("versions");
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(Map.class::isInstance)
        .<Map<String, Object>>map(entry -> new LinkedHashMap<>((Map<String, Object>) entry))
        .toList();
  }

  private void recordFailure(RepositoryRuntime runtime, int status, Instant now) {
    ProxyStateDao.ProxyRemoteState state = proxyStateDao.loadState(runtime.id()).orElse(null);
    int failures = state == null ? 0 : state.failCount();
    long blockSeconds = BACKOFF_SECONDS[Math.min(failures, BACKOFF_SECONDS.length - 1)];
    proxyStateDao.recordFailure(runtime.id(), blockSeconds,
        status <= 0 ? "Pub upstream IO error" : "Pub upstream returned " + status, now);
  }

  private void validateMetadata(String packageName, Map<String, Object> body) {
    String name = text(body.get("name"));
    if (name != null && !PubPackageName.require(name).equals(packageName)) {
      throw new PubExceptions.BadUpstreamException("Pub metadata package name mismatch for " + packageName);
    }
    for (Map<String, Object> entry : versions(body)) {
      PubVersions.require(String.valueOf(entry.get("version")));
    }
  }

  private MavenResponse packageNames(boolean headOnly) {
    return PubResponses.json(objectMapper, Map.of("packages", List.of()), 200, headOnly);
  }

  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  private Map<String, Object> parseJson(String body) {
    try {
      return objectMapper.readValue(body, JSON_MAP);
    } catch (IOException e) {
      throw new PubExceptions.BadUpstreamException("Invalid Pub metadata JSON", e);
    }
  }

  private boolean isFresh(CachedAssetMetadata snapshot, int maxAgeMinutes, Instant now) {
    if (snapshot.lastUpdatedAt() == null) {
      return false;
    }
    return !snapshot.lastUpdatedAt().plusSeconds(Math.max(0, maxAgeMinutes) * 60L).isBefore(now);
  }

  private String remoteEtag(Optional<CachedAssetMetadata> cached) {
    return cached.map(CachedAssetMetadata::attributes)
        .map(attrs -> text(attrs.get("remoteEtag")))
        .orElse(null);
  }

  private Instant remoteLastModified(Optional<CachedAssetMetadata> cached) {
    return cached.map(CachedAssetMetadata::attributes)
        .map(attrs -> text(attrs.get("remoteLastModified")))
        .flatMap(value -> {
          try {
            return Optional.of(Instant.parse(value));
          } catch (RuntimeException ignored) {
            return Optional.empty();
          }
        })
        .orElse(null);
  }

  private Map<String, String> remoteAttrs(HttpRemoteFetcher.Result result) {
    Map<String, String> attrs = new LinkedHashMap<>();
    if (result.etag() != null) attrs.put("remoteEtag", result.etag());
    if (result.lastModified() != null) attrs.put("remoteLastModified", result.lastModified().toString());
    if (result.contentType() != null) attrs.put("remoteContentType", result.contentType());
    return attrs;
  }

  Map<String, String> archiveRemoteAttrs(HttpRemoteFetcher.Result result, String expectedSha256) {
    Map<String, String> attrs = remoteAttrs(result);
    String normalizedSha256 = text(expectedSha256);
    if (normalizedSha256 == null) {
      attrs.put("pubChecksumSource", "computed-only");
      attrs.put("remoteArchiveSha256Missing", "true");
    } else {
      attrs.put("pubChecksumSource", "archive_sha256");
      attrs.put("remoteArchiveSha256", normalizedSha256.toLowerCase(Locale.ROOT));
    }
    return attrs;
  }

  private boolean isNegative(int status) {
    return status == 404 || status == 410 || status == 451;
  }

  private boolean sameOrigin(String left, String right) {
    try {
      java.net.URI a = java.net.URI.create(left);
      java.net.URI b = java.net.URI.create(right);
      return a.getScheme().equalsIgnoreCase(b.getScheme())
          && a.getHost().equalsIgnoreCase(b.getHost())
          && effectivePort(a) == effectivePort(b);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private int effectivePort(java.net.URI uri) {
    if (uri.getPort() >= 0) return uri.getPort();
    if ("http".equalsIgnoreCase(uri.getScheme())) return 80;
    if ("https".equalsIgnoreCase(uri.getScheme())) return 443;
    return -1;
  }

  private void ensureProxy(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.PUB || !runtime.isProxy()) {
      throw new PubExceptions.MethodNotAllowed("Operation is only valid on proxy Pub repositories");
    }
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Pub repository " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isBlank() ? null : text;
  }

  record CachedMetadata(Map<String, Object> body, String etag, Instant lastModified) {
  }
}
