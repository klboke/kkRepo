package com.github.klboke.kkrepo.server.cargo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.mysql.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.cargo.CargoCrateName;
import com.github.klboke.kkrepo.protocol.cargo.CargoIndexPath;
import com.github.klboke.kkrepo.protocol.cargo.CargoPath;
import com.github.klboke.kkrepo.protocol.cargo.CargoVersions;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cargo.CargoPublishPayload.CargoPackageMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class CargoProxyService {
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};
  static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final CargoAssetWriter writer;
  private final CargoAssetReader reader;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;
  private final ObjectMapper objectMapper;

  public CargoProxyService(
      AssetDao assetDao,
      ComponentDao componentDao,
      BlobStorageRegistry blobStorageRegistry,
      CargoAssetWriter writer,
      CargoAssetReader reader,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      ObjectMapper objectMapper) {
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
    this.proxyStateDao = proxyStateDao;
    this.fetcher = fetcher;
    this.negativeCache = negativeCache;
    this.assetMetadataCache = assetMetadataCache;
    this.objectMapper = objectMapper;
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      CargoPath path,
      String baseUrl,
      CargoSearchQuery search,
      boolean headOnly) {
    ensureProxy(runtime);
    return switch (path.kind()) {
      case ROOT, CONFIG -> localConfig(runtime, baseUrl, headOnly);
      case INDEX -> index(runtime, path.crateName(), headOnly);
      case DOWNLOAD -> download(runtime, path.crateName(), path.version(), headOnly);
      case SEARCH -> search(runtime, search, headOnly);
      case OWNERS -> CargoResponses.json(objectMapper, Map.of("users", List.of()), 200, headOnly);
      default -> throw new CargoExceptions.CargoNotFoundException(path.rawPath());
    };
  }

  MavenResponse index(RepositoryRuntime runtime, String crateName, boolean headOnly) {
    String path = CargoIndexPath.forCrate(crateName);
    Optional<CachedAssetMetadata> cached = usableCached(runtime, path, lookupCached(runtime, path));
    Instant now = Instant.now();
    if (cached.isPresent() && isFresh(cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now)) {
      return reader.serveSnapshot(cached.get(), headOnly, path);
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new CargoExceptions.CargoIndexNotFoundException(crateName);
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) {
        return reader.serveSnapshot(cached.get(), headOnly, path);
      }
      throw new CargoExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    return fetchAndCacheIndex(runtime, path, crateName, cached, headOnly, now);
  }

  MavenResponse download(RepositoryRuntime runtime, String crateName, String version, boolean headOnly) {
    ensureProxy(runtime);
    String path = CargoAssetWriter.cratePath(crateName, version);
    Optional<CachedAssetMetadata> cached = usableCached(runtime, path, lookupCached(runtime, path));
    Instant now = Instant.now();
    if (cached.isPresent() && isFresh(cached.get(), runtime.contentMaxAgeMinutesOrDefault(), now)) {
      return reader.serveSnapshot(cached.get(), headOnly, path);
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new CargoExceptions.CargoNotFoundException(path);
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) {
        return reader.serveSnapshot(cached.get(), headOnly, path);
      }
      throw new CargoExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    Map<String, Object> entry = findRemoteEntry(runtime, crateName, version)
        .orElseThrow(() -> new CargoExceptions.CargoNotFoundException(crateName + " " + version));
    String effectiveName = text(entry.get("name"));
    String effectiveVersion = text(entry.get("vers"));
    String effectivePath = CargoAssetWriter.cratePath(effectiveName, effectiveVersion);
    if (!effectivePath.equals(path)) {
      path = effectivePath;
      cached = usableCached(runtime, path, lookupCached(runtime, path));
      if (cached.isPresent() && isFresh(cached.get(), runtime.contentMaxAgeMinutesOrDefault(), now)) {
        return reader.serveSnapshot(cached.get(), headOnly, path);
      }
    }
    CargoRemoteConfig config = remoteConfig(runtime, now);
    String url = downloadUrl(config.dl(), effectiveName, effectiveVersion, text(entry.get("cksum")));
    return fetchAndCacheCrate(runtime, path, url, entry, cached, config.authRequired(), headOnly, now);
  }

  Optional<Map<String, Object>> findRemoteEntry(RepositoryRuntime runtime, String crateName, String version) {
    String versionKey = CargoVersions.uniquenessKey(version);
    for (Map<String, Object> entry : indexEntries(runtime, crateName)) {
      if (versionKey.equals(CargoVersions.uniquenessKey(text(entry.get("vers"))))) {
        return Optional.of(entry);
      }
    }
    return Optional.empty();
  }

  List<Map<String, Object>> indexEntries(RepositoryRuntime runtime, String crateName) {
    String indexPath = CargoIndexPath.forCrate(crateName);
    Optional<CachedAssetMetadata> cached = usableCached(runtime, indexPath, lookupCached(runtime, indexPath));
    Instant now = Instant.now();
    if (cached.isPresent() && isFresh(cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now)) {
      return parseIndex(reader.readText(cached.get(), indexPath));
    }
    try {
      MavenResponse response = index(runtime, crateName, false);
      try (var body = response.body()) {
        return parseIndex(new String(body.readAllBytes(), StandardCharsets.UTF_8));
      }
    } catch (CargoExceptions.CargoIndexNotFoundException e) {
      return List.of();
    } catch (IOException e) {
      throw new CargoExceptions.BadUpstreamException("Failed reading Cargo index response", e);
    }
  }

  MavenResponse search(RepositoryRuntime runtime, CargoSearchQuery query, boolean headOnly) {
    ensureProxy(runtime);
    Instant now = Instant.now();
    if (proxyStateDao != null && proxyStateDao.isBlocked(runtime.id(), now)) {
      return localSearch(runtime, query, headOnly);
    }
    CargoRemoteConfig config;
    try {
      config = remoteConfig(runtime, now);
    } catch (CargoExceptions.BadUpstreamException e) {
      return searchFallback(runtime, query, headOnly, e.getMessage(), now);
    }
    if (config.api() == null) {
      return localSearch(runtime, query, headOnly);
    }
    String url = RemoteUrlBuilder.repositoryPathWithQueryString(
        config.api(),
        "api/v1/crates",
        searchQuery(query));
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(url, null, null, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.SEARCH)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, "api/v1/crates", result -> {
        int status = result.status();
        if (status >= 200 && status < 300) {
          byte[] body = UpstreamBodyReadException.readAllBytes(result.body());
          if (proxyStateDao != null) {
            proxyStateDao.recordSuccess(runtime.id(), now);
          }
          return CargoResponses.json(body, 200, null, null, headOnly);
        }
        return searchFallback(runtime, query, headOnly, "Upstream search returned " + status, now);
      });
    } catch (IOException e) {
      return searchFallback(runtime, query, headOnly, "Upstream search IO error: " + e.getMessage(), now);
    }
  }

  private MavenResponse fetchAndCacheIndex(
      RepositoryRuntime runtime,
      String path,
      String crateName,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      Instant now) {
    String etag = remoteEtag(cached);
    Instant lastModified = remoteLastModified(cached);
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        RemoteUrlBuilder.repositoryPathString(runtime.proxyRemoteUrl(), path),
        etag,
        lastModified,
        null,
        false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, path, result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          touchVerified(runtime, path, cached.get(), now);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path);
          return reader.serveSnapshot(cached.get(), headOnly, path);
        }
        if (status >= 200 && status < 300) {
          byte[] body = UpstreamBodyReadException.readAllBytes(result.body());
          try {
            parseIndex(new String(body, StandardCharsets.UTF_8));
          } catch (CargoExceptions.BadUpstreamException e) {
            return handleUpstreamFailure(runtime, path, cached, headOnly, e.getMessage(), now);
          }
          negativeCache.invalidate(runtime, path);
          CargoAssetWriter.Stored stored = writer.writeMetadata(
              runtime,
              blobStorage(runtime),
              requireBlobStore(runtime),
              path,
              body,
              contentType(result.contentType(), "text/plain"),
              "index",
              Map.of("crateName", crateName),
              remoteAttrs(result),
              !headOnly);
          proxyStateDao.recordSuccess(runtime.id(), now);
          return responseFromStored(stored, headOnly);
        }
        if (isNegativeSparseStatus(status)) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return reader.serveSnapshot(cached.get(), headOnly, path);
          }
          negativeCache.rememberNotFound(runtime, path);
          throw new CargoExceptions.CargoIndexNotFoundException(crateName);
        }
        return handleUpstreamFailure(runtime, path, cached, headOnly,
            "Upstream returned " + status, now);
      });
    } catch (IOException e) {
      return handleUpstreamFailure(runtime, path, cached, headOnly,
          "Upstream IO error: " + e.getMessage(), now);
    }
  }

  private MavenResponse localSearch(RepositoryRuntime runtime, CargoSearchQuery query, boolean headOnly) {
    List<ComponentRecord> components = componentDao == null
        ? List.of()
        : componentDao.searchComponentsByRepositoryIds(
            List.of(runtime.id()),
            RepositoryFormat.CARGO,
            query.query(),
            query.localScanLimit());
    return CargoSearchResults.fromComponents(objectMapper, components, query, headOnly);
  }

  private MavenResponse searchFallback(
      RepositoryRuntime runtime,
      CargoSearchQuery query,
      boolean headOnly,
      String error,
      Instant now) {
    if (proxyStateDao != null) {
      int failCount = proxyStateDao.loadState(runtime.id())
          .map(ProxyStateDao.ProxyRemoteState::failCount)
          .orElse(0);
      long block = runtime.autoBlockOrDefault()
          ? BACKOFF_SECONDS[Math.min(failCount, BACKOFF_SECONDS.length - 1)]
          : 0;
      proxyStateDao.recordFailure(runtime.id(), block, error, now);
    }
    return localSearch(runtime, query, headOnly);
  }

  private String searchQuery(CargoSearchQuery query) {
    StringBuilder value = new StringBuilder();
    value.append("q=").append(urlEncode(query.query()));
    value.append("&per_page=").append(query.perPage());
    if (query.page() > 1) {
      value.append("&page=").append(query.page());
    }
    return value.toString();
  }

  private MavenResponse fetchAndCacheCrate(
      RepositoryRuntime runtime,
      String path,
      String url,
      Map<String, Object> indexEntry,
      Optional<CachedAssetMetadata> cached,
      boolean upstreamAuthRequired,
      boolean headOnly,
      Instant now) {
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, remoteEtag(cached), remoteLastModified(cached), null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
        .withRepository(runtime, upstreamAuthRequired);
    try {
      return fetcher.fetchWithBodyRetry(req, path, result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          touchVerified(runtime, path, cached.get(), now);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path);
          return reader.serveSnapshot(cached.get(), headOnly, path);
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, path);
          CargoPackageMetadata metadata = metadataFromIndex(indexEntry);
          String expectedSha256 = expectedChecksum(indexEntry, path);
          CargoAssetWriter.Stored stored = writer.writeProxyCrate(
              runtime,
              blobStorage(runtime),
              requireBlobStore(runtime),
              metadata,
              indexEntry,
              path,
              result.body(),
              contentType(result.contentType(), "application/x-tar"),
              remoteAttrs(result, Map.of("remoteUrl", url)),
              expectedSha256);
          proxyStateDao.recordSuccess(runtime.id(), now);
          return responseFromStored(stored, headOnly);
        }
        if (isNegativeSparseStatus(status)) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return reader.serveSnapshot(cached.get(), headOnly, path);
          }
          negativeCache.rememberNotFound(runtime, path);
          throw new CargoExceptions.CargoNotFoundException(path);
        }
        return handleUpstreamFailure(runtime, path, cached, headOnly,
            "Upstream returned " + status, now);
      });
    } catch (IOException e) {
      return handleUpstreamFailure(runtime, path, cached, headOnly,
          "Upstream IO error: " + e.getMessage(), now);
    }
  }

  private CargoRemoteConfig remoteConfig(RepositoryRuntime runtime, Instant now) {
    String path = "config.json";
    Optional<CachedAssetMetadata> cached = usableCached(runtime, path, lookupCached(runtime, path));
    if (cached.isPresent() && isFresh(cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now)) {
      return parseRemoteConfig(reader.readText(cached.get(), path));
    }
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
          touchVerified(runtime, path, cached.get(), now);
          proxyStateDao.recordSuccess(runtime.id(), now);
          return parseRemoteConfig(reader.readText(cached.get(), path));
        }
        if (status >= 200 && status < 300) {
          byte[] body = UpstreamBodyReadException.readAllBytes(result.body());
          CargoRemoteConfig parsed;
          try {
            parsed = parseRemoteConfig(new String(body, StandardCharsets.UTF_8));
          } catch (CargoExceptions.BadUpstreamException e) {
            return handleConfigUpstreamFailure(runtime, path, cached, e.getMessage(), now);
          }
          writer.writeMetadata(
              runtime,
              blobStorage(runtime),
              requireBlobStore(runtime),
              path,
              body,
              contentType(result.contentType(), "application/json"),
              "config",
              Map.of(),
              remoteAttrs(result),
              false);
          proxyStateDao.recordSuccess(runtime.id(), now);
          return parsed;
        }
        if (cached.isPresent()) {
          return parseRemoteConfig(reader.readText(cached.get(), path));
        }
        throw new CargoExceptions.BadUpstreamException("Upstream config.json returned " + status);
      });
    } catch (IOException e) {
      if (cached.isPresent()) {
        return parseRemoteConfig(reader.readText(cached.get(), path));
      }
      throw new CargoExceptions.BadUpstreamException("Upstream config.json IO error: " + e.getMessage(), e);
    }
  }

  private CargoRemoteConfig handleConfigUpstreamFailure(
      RepositoryRuntime runtime,
      String path,
      Optional<CachedAssetMetadata> cached,
      String error,
      Instant now) {
    int failCount = proxyStateDao.loadState(runtime.id())
        .map(ProxyStateDao.ProxyRemoteState::failCount)
        .orElse(0);
    long block = runtime.autoBlockOrDefault()
        ? BACKOFF_SECONDS[Math.min(failCount, BACKOFF_SECONDS.length - 1)]
        : 0;
    proxyStateDao.recordFailure(runtime.id(), block, error, now);
    if (cached.isPresent()) {
      return parseRemoteConfig(reader.readText(cached.get(), path));
    }
    throw new CargoExceptions.BadUpstreamException(error);
  }

  private CargoRemoteConfig parseRemoteConfig(String raw) {
    try {
      Map<String, Object> json = objectMapper.readValue(raw, JSON_MAP);
      String dl = text(json.get("dl"));
      if (dl == null) {
        throw new CargoExceptions.BadUpstreamException("Cargo upstream config.json is missing dl");
      }
      return new CargoRemoteConfig(dl, text(json.get("api")), Boolean.TRUE.equals(json.get("auth-required")));
    } catch (JsonProcessingException e) {
      throw new CargoExceptions.BadUpstreamException("Invalid Cargo upstream config.json", e);
    }
  }

  private List<Map<String, Object>> parseIndex(String raw) {
    List<Map<String, Object>> entries = new ArrayList<>();
    for (String line : raw.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      try {
        entries.add(objectMapper.readValue(line, JSON_MAP));
      } catch (JsonProcessingException e) {
        throw new CargoExceptions.BadUpstreamException("Invalid Cargo upstream index entry", e);
      }
    }
    return entries;
  }

  private MavenResponse localConfig(RepositoryRuntime runtime, String baseUrl, boolean headOnly) {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("dl", baseUrl + "/crates");
    config.put("api", baseUrl + "/");
    if (runtime.cargoRequireAuthenticationOrDefault()) {
      config.put("auth-required", true);
    }
    return CargoResponses.jsonWithBodyEtag(objectMapper, config, 200, headOnly);
  }

  private MavenResponse responseFromStored(CargoAssetWriter.Stored stored, boolean headOnly) {
    try {
      if (headOnly) {
        stored.discardBody();
        return reader.serve(stored.asset(), true, stored.asset().path());
      }
      return MavenResponse.ok(stored.openBody(), stored.blob().size(), stored.asset().contentType(),
          stored.blob().sha1(), stored.asset().lastUpdatedAt());
    } catch (RuntimeException e) {
      stored.discardBody();
      throw e;
    }
  }

  private MavenResponse handleUpstreamFailure(
      RepositoryRuntime runtime,
      String path,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      String error,
      Instant now) {
    int failCount = proxyStateDao.loadState(runtime.id())
        .map(ProxyStateDao.ProxyRemoteState::failCount)
        .orElse(0);
    long block = runtime.autoBlockOrDefault()
        ? BACKOFF_SECONDS[Math.min(failCount, BACKOFF_SECONDS.length - 1)]
        : 0;
    proxyStateDao.recordFailure(runtime.id(), block, error, now);
    if (cached.isPresent()) {
      return reader.serveSnapshot(cached.get(), headOnly, path);
    }
    throw new CargoExceptions.BadUpstreamException(error);
  }

  private void touchVerified(
      RepositoryRuntime runtime,
      String path,
      CachedAssetMetadata snapshot,
      Instant now) {
    Map<String, Object> attrs = snapshot.attributes() == null
        ? Map.of()
        : new LinkedHashMap<>(snapshot.attributes());
    assetDao.touchAssetLastUpdatedAndAttributes(snapshot.assetId(), now, attrs);
    assetMetadataCache.touchVerified(runtime.id(), path, now, attrs);
  }

  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  private Optional<CachedAssetMetadata> usableCached(
      RepositoryRuntime runtime,
      String path,
      Optional<CachedAssetMetadata> cached) {
    if (cached.isEmpty()) {
      return Optional.empty();
    }
    if (reader.exists(cached.get())) {
      return cached;
    }
    assetMetadataCache.evict(runtime.id(), path);
    return Optional.empty();
  }

  private boolean isFresh(CachedAssetMetadata snapshot, int ttlMinutes, Instant now) {
    if (snapshot.lastUpdatedAt() == null) {
      return false;
    }
    if (ttlMinutes < 0) {
      return true;
    }
    return snapshot.lastUpdatedAt().plusSeconds(ttlMinutes * 60L).isAfter(now);
  }

  private CargoPackageMetadata metadataFromIndex(Map<String, Object> entry) {
    String name = text(entry.get("name"));
    String version = CargoVersions.requireVersion(text(entry.get("vers")));
    return new CargoPackageMetadata(
        name,
        CargoCrateName.parse(name).lowerDashUnderscoreKey(),
        version,
        CargoVersions.uniquenessKey(version),
        null,
        Map.of());
  }

  private String expectedChecksum(Map<String, Object> indexEntry, String path) {
    String checksum = text(indexEntry.get("cksum"));
    if (checksum == null) {
      throw new CargoExceptions.BadUpstreamException("Cargo upstream index entry is missing cksum for " + path);
    }
    return checksum;
  }

  private boolean isNegativeSparseStatus(int status) {
    return status == 404 || status == 410 || status == 451;
  }

  private String downloadUrl(String dl, String crateName, String version, String checksum) {
    String url = dl;
    if (url.contains("{crate}") || url.contains("{version}") || url.contains("{prefix}")
        || url.contains("{lowerprefix}") || url.contains("{sha256-checksum}")) {
      return url
          .replace("{crate}", crateName)
          .replace("{version}", version)
          .replace("{prefix}", CargoIndexPath.prefixForCrate(crateName, false))
          .replace("{lowerprefix}", CargoIndexPath.prefixForCrate(crateName, true))
          .replace("{sha256-checksum}", checksum == null ? "" : checksum);
    }
    return RemoteUrlBuilder.repositoryPathString(url, crateName + "/" + version + "/download");
  }

  private Map<String, String> remoteAttrs(HttpRemoteFetcher.Result result) {
    return remoteAttrs(result, Map.of());
  }

  private Map<String, String> remoteAttrs(HttpRemoteFetcher.Result result, Map<String, String> extra) {
    Map<String, String> attrs = new LinkedHashMap<>(extra);
    if (result.etag() != null) {
      attrs.put("remoteEtag", result.etag());
    }
    if (result.lastModified() != null) {
      attrs.put("remoteLastModified", result.lastModified().toString());
    }
    return attrs;
  }

  private String remoteEtag(Optional<CachedAssetMetadata> cached) {
    return cached.map(CachedAssetMetadata::blob)
        .map(CachedAssetMetadata.CachedBlob::attributes)
        .map(attrs -> text(attrs.get("remoteEtag")))
        .orElse(null);
  }

  private Instant remoteLastModified(Optional<CachedAssetMetadata> cached) {
    return cached.map(CachedAssetMetadata::blob)
        .map(CachedAssetMetadata.CachedBlob::attributes)
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

  private static String contentType(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private BlobStorage blobStorage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime));
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Cargo repository " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private void ensureProxy(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.CARGO || !runtime.isProxy()) {
      throw new CargoExceptions.MethodNotAllowed("Operation is only valid on proxy Cargo repositories");
    }
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isBlank() ? null : text;
  }

  private record CargoRemoteConfig(String dl, String api, boolean authRequired) {
  }
}
