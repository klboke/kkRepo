package com.github.klboke.kkrepo.server.composer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.composer.ComposerPackageName;
import com.github.klboke.kkrepo.protocol.composer.ComposerPath;
import com.github.klboke.kkrepo.protocol.composer.ComposerPaths;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ComposerProxyService {
  private static final int MAX_METADATA_BYTES = 64 * 1024 * 1024;
  private static final String ROUTE_PREFIX = "_composer/routes/";

  private final ObjectMapper objectMapper;
  private final AssetDao assetDao;
  private final ComposerAssetSupport assets;
  private final HttpRemoteFetcher fetcher;
  private final ProxyNegativeCache negativeCache;
  private final ProxyStateDao proxyStateDao;
  private final RawProxyService rawProxy;

  public ComposerProxyService(
      ObjectMapper objectMapper,
      AssetDao assetDao,
      ComposerAssetSupport assets,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      ProxyStateDao proxyStateDao,
      RawProxyService rawProxy) {
    this.objectMapper = objectMapper;
    this.assetDao = assetDao;
    this.assets = assets;
    this.fetcher = fetcher;
    this.negativeCache = negativeCache;
    this.proxyStateDao = proxyStateDao;
    this.rawProxy = rawProxy;
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      ComposerPath path,
      String baseUrl,
      String filter,
      boolean headOnly) {
    ensureProxy(runtime);
    return switch (path.kind()) {
      case ROOT, PACKAGES -> document(runtime, ComposerPaths.PACKAGES, baseUrl, null, headOnly);
      case PACKAGE_METADATA -> packageDocument(runtime, path.packageName(), path.dev(), baseUrl)
          .map(value -> ComposerResponses.json(objectMapper, value.body(), value.lastModified(), headOnly))
          .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.rawPath()));
      case PROVIDERS -> document(runtime, ComposerPaths.provider(path.packageName()), baseUrl, null, headOnly);
      case PACKAGE_LIST -> document(runtime, "packages/list.json", baseUrl, filter, headOnly);
      case DIST -> dist(runtime, path, headOnly);
      case UNKNOWN -> throw new MavenExceptions.MavenNotFoundException(path.rawPath());
    };
  }

  Optional<ComposerHostedService.PackageDocument> packageDocument(
      RepositoryRuntime runtime,
      String packageName,
      boolean dev,
      String baseUrl) {
    ensureProxy(runtime);
    String name = ComposerPackageName.require(packageName);
    String path = ComposerPaths.metadata(name, dev);
    Optional<CachedJson> cached = loadOrFetch(runtime, path, metadataUrl(runtime, name, dev));
    if (cached.isEmpty()) return Optional.empty();
    Map<String, Object> rewritten = rewritePackageMetadata(runtime, cached.get().body(), name, baseUrl);
    return Optional.of(new ComposerHostedService.PackageDocument(
        rewritten, cached.get().lastModified(), runtime.name()));
  }

  List<String> packageNames(RepositoryRuntime runtime) {
    ensureProxy(runtime);
    Optional<CachedJson> cached = loadOrFetch(runtime, listCachePath(null), "packages/list.json");
    if (cached.isEmpty()) return List.of();
    Object value = cached.get().body().get("packageNames");
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream().filter(String.class::isInstance).map(String.class::cast)
        .map(ComposerPackageName::normalize).filter(ComposerPackageName::isValid).distinct().sorted().toList();
  }

  List<Map<String, Object>> providers(RepositoryRuntime runtime, String packageName) {
    ensureProxy(runtime);
    String path = ComposerPaths.provider(packageName);
    Optional<CachedJson> cached = loadOrFetch(runtime, path, path);
    if (cached.isEmpty()) return List.of();
    Object value = cached.get().body().get("providers");
    if (!(value instanceof List<?> list)) return List.of();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) result.add(stringMap(map));
    }
    return result;
  }

  private MavenResponse document(
      RepositoryRuntime runtime,
      String localPath,
      String baseUrl,
      String filter,
      boolean headOnly) {
    String remotePath = stripQuery(localPath);
    String cachePath = "packages/list.json".equals(remotePath) ? listCachePath(filter) : localPath;
    Optional<CachedJson> cached = loadOrFetch(runtime, cachePath, remotePath + queryFilter(filter));
    if (cached.isEmpty()) throw new MavenExceptions.MavenNotFoundException(localPath);
    Map<String, Object> body = localPath.startsWith(ComposerPaths.PACKAGES)
        ? rewritePackages(cached.get().body(), baseUrl)
        : cached.get().body();
    return ComposerResponses.json(objectMapper, body, cached.get().lastModified(), headOnly);
  }

  private MavenResponse dist(RepositoryRuntime runtime, ComposerPath path, boolean headOnly) {
    String publicPath = normalizePath(path.rawPath());
    // A Nexus proxy-cache migration copies the public Composer dist path and blob, but Nexus
    // does not have kkrepo's private _composer/routes records. Serve an already cached dist
    // directly so migrated proxy content stays usable without contacting either upstream.
    if (assets.find(runtime, publicPath).isPresent()) {
      return assets.serve(runtime, publicPath, headOnly);
    }
    String routeKey = ComposerResponses.sha256(publicPath).substring(0, 40);
    Route route = readRoute(runtime, routeKey);
    MavenResponse response = rawProxy.getAssetFromUrl(runtime, publicPath, route.remoteUrl(), headOnly);
    if (route.shasum() != null && !route.shasum().isBlank()) {
      AssetRecord asset = assets.find(runtime, publicPath)
          .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(publicPath));
      AssetBlobRecord blob = assets.blob(asset, publicPath);
      if (!route.shasum().equalsIgnoreCase(blob.sha1())) {
        if (response.hasBody()) {
          try {
            response.body().close();
          } catch (IOException ignored) {
          }
        }
        assets.delete(runtime, publicPath);
        throw new MavenExceptions.BadUpstreamException(
            "Composer dist SHA-1 mismatch for " + route.packageName() + " " + route.version());
      }
    }
    return response;
  }

  private Optional<CachedJson> loadOrFetch(
      RepositoryRuntime runtime,
      String localPath,
      String remotePath) {
    String cachePath = stripQuery(localPath);
    Optional<AssetRecord> cached = assets.find(runtime, cachePath);
    Instant now = Instant.now();
    if (cached.isPresent() && fresh(cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now)) {
      return Optional.of(parseCached(runtime, cachePath, cached.get()));
    }
    if (negativeCache.isNotFoundCached(runtime, cachePath)) return Optional.empty();
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      return cached.map(asset -> parseCached(runtime, cachePath, asset));
    }

    String etag = null;
    Instant lastModified = null;
    if (cached.isPresent() && cached.get().assetBlobId() != null) {
      AssetBlobRecord blob = assets.blob(cached.get(), cachePath);
      etag = string(blob.attributes().get("remoteEtag"));
      lastModified = instant(blob.attributes().get("remoteLastModified"));
    }
    String url = remoteUrl(runtime, remotePath);
    HttpRemoteFetcher.Request request = new HttpRemoteFetcher.Request(
        url, etag, lastModified, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(request, cachePath, result -> {
        if (result.status() == 304 && cached.isPresent()) {
          assetDao.touchAssetLastUpdated(cached.get().id(), now);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, cachePath);
          return Optional.of(parseCached(runtime, cachePath, cached.get()).withLastModified(now));
        }
        if (result.status() >= 200 && result.status() < 300) {
          byte[] bytes = readMetadata(result.body());
          parseJson(bytes, cachePath);
          Map<String, Object> attrs = new LinkedHashMap<>();
          if (result.etag() != null) attrs.put("remoteEtag", result.etag());
          if (result.lastModified() != null) attrs.put("remoteLastModified", result.lastModified().toString());
          attrs.put("remoteUrl", url);
          assets.storeBytes(runtime, cachePath, bytes, ComposerResponses.JSON, attrs);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, cachePath);
          AssetRecord stored = assets.find(runtime, cachePath)
              .orElseThrow(() -> new IllegalStateException("Composer metadata was not persisted: " + cachePath));
          return Optional.of(new CachedJson(parseJson(bytes, cachePath), stored.lastUpdatedAt()));
        }
        if (result.status() == 404 || result.status() == 410 || result.status() == 451) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) return Optional.of(parseCached(runtime, cachePath, cached.get()));
          negativeCache.rememberNotFound(runtime, cachePath);
          return Optional.empty();
        }
        return upstreamFailure(runtime, cachePath, cached, "Upstream returned " + result.status(), now);
      });
    } catch (IOException e) {
      return upstreamFailure(runtime, cachePath, cached, "Upstream IO error: " + e.getMessage(), now);
    }
  }

  private Optional<CachedJson> upstreamFailure(
      RepositoryRuntime runtime,
      String path,
      Optional<AssetRecord> cached,
      String message,
      Instant now) {
    proxyStateDao.recordFailure(runtime.id(), runtime.autoBlockOrDefault() ? 30 : 0, message, now);
    if (cached.isPresent()) return Optional.of(parseCached(runtime, path, cached.get()));
    throw new MavenExceptions.BadUpstreamException(message);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> rewritePackageMetadata(
      RepositoryRuntime runtime,
      Map<String, Object> body,
      String requestedName,
      String baseUrl) {
    Object packagesValue = body.get("packages");
    if (!(packagesValue instanceof Map<?, ?> packages) || packages.size() != 1
        || !packages.containsKey(requestedName)) {
      throw new MavenExceptions.BadUpstreamException(
          "Composer metadata does not contain only requested package " + requestedName);
    }
    Object versionsValue = packages.get(requestedName);
    if (!(versionsValue instanceof List<?> versions)) {
      throw new MavenExceptions.BadUpstreamException("Invalid Composer versions for " + requestedName);
    }
    List<Map<String, Object>> rewritten = new ArrayList<>(versions.size());
    for (Object item : versions) {
      if (!(item instanceof Map<?, ?> map)) continue;
      Map<String, Object> version = stringMap(map);
      Object distValue = version.get("dist");
      if (distValue instanceof Map<?, ?> distMap) {
        Map<String, Object> dist = stringMap(distMap);
        String remoteUrl = string(dist.get("url"));
        if (remoteUrl != null) {
          String packageVersion = string(version.get("version"));
          String publicPath = ComposerPaths.componentDist(
              requestedName, packageVersion, string(dist.get("type")));
          String token = ComposerResponses.sha256(publicPath).substring(0, 40);
          String fileName = publicPath.substring(publicPath.lastIndexOf('/') + 1);
          Route route = new Route(
              remoteUrl, requestedName, packageVersion, fileName, string(dist.get("shasum")));
          storeRoute(runtime, token, route);
          dist.put("url", baseUrl + "/" + publicPath);
          version.put("dist", dist);
        }
      }
      rewritten.add(version);
    }
    Map<String, Object> result = new LinkedHashMap<>(body);
    result.put("packages", Map.of(requestedName, rewritten));
    return result;
  }

  private Map<String, Object> rewritePackages(Map<String, Object> body, String baseUrl) {
    String metadataUrl = string(body.get("metadata-url"));
    if (metadataUrl == null || !metadataUrl.contains("%package%")) {
      throw new MavenExceptions.BadUpstreamException(
          "Composer proxy requires an upstream Composer v2 metadata-url");
    }
    Map<String, Object> result = new LinkedHashMap<>(body);
    result.put("packages", List.of());
    result.put("metadata-url", baseUrl + "/p2/%package%.json");
    if (body.containsKey("providers-api")) {
      result.put("providers-api", baseUrl + "/providers/%package%.json");
    }
    if (body.containsKey("list")) result.put("list", baseUrl + "/packages/list.json");
    result.remove("provider-includes");
    result.remove("providers-url");
    return result;
  }

  private void storeRoute(RepositoryRuntime runtime, String token, Route route) {
    String path = ROUTE_PREFIX + token + ".json";
    try {
      assets.storeBytes(runtime, path, objectMapper.writeValueAsBytes(route), ComposerResponses.JSON,
          Map.of("composerRoute", true));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to persist Composer dist route", e);
    }
  }

  private Route readRoute(RepositoryRuntime runtime, String token) {
    try {
      return objectMapper.readValue(assets.readBytes(runtime, ROUTE_PREFIX + token + ".json"), Route.class);
    } catch (IOException e) {
      throw new MavenExceptions.MavenNotFoundException("Unknown Composer dist route: " + token);
    }
  }

  private CachedJson parseCached(RepositoryRuntime runtime, String path, AssetRecord asset) {
    return new CachedJson(parseJson(assets.readBytes(runtime, path), path), asset.lastUpdatedAt());
  }

  private Map<String, Object> parseJson(byte[] bytes, String path) {
    try {
      return objectMapper.readValue(bytes, new TypeReference<>() {});
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid Composer JSON at " + path, e);
    }
  }

  private static byte[] readMetadata(java.io.InputStream body) throws IOException {
    byte[] bytes = body.readNBytes(MAX_METADATA_BYTES + 1);
    if (bytes.length > MAX_METADATA_BYTES) {
      throw new IOException("Composer metadata exceeds " + MAX_METADATA_BYTES + " bytes");
    }
    return bytes;
  }

  private static boolean fresh(AssetRecord asset, int ttlMinutes, Instant now) {
    if (asset.lastUpdatedAt() == null) return false;
    if (ttlMinutes < 0) return true;
    return asset.lastUpdatedAt().plusSeconds(ttlMinutes * 60L).isAfter(now);
  }

  private static String queryFilter(String filter) {
    if (filter == null || filter.isBlank()) return "";
    return "?filter=" + java.net.URLEncoder.encode(filter, StandardCharsets.UTF_8);
  }

  private String metadataUrl(RepositoryRuntime runtime, String packageName, boolean dev) {
    CachedJson root = loadOrFetch(runtime, ComposerPaths.PACKAGES, ComposerPaths.PACKAGES)
        .orElseThrow(() -> new MavenExceptions.BadUpstreamException(
            "Composer proxy upstream has no packages.json"));
    String template = string(root.body().get("metadata-url"));
    if (template == null || !template.contains("%package%")) {
      throw new MavenExceptions.BadUpstreamException(
          "Composer proxy requires an upstream Composer v2 metadata-url");
    }
    return resolveMetadataUrl(runtime, template, packageName, dev);
  }

  static String resolveMetadataUrl(
      RepositoryRuntime runtime,
      String template,
      String packageName,
      boolean dev) {
    String coordinate = packageName + (dev ? "~dev" : "");
    try {
      URI resolved = RemoteUrlBuilder.repositoryBase(runtime.proxyRemoteUrl())
          .resolve(template.replace("%package%", coordinate));
      String scheme = resolved.getScheme();
      if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
        throw new IllegalArgumentException("unsupported metadata URL scheme");
      }
      return resolved.toString();
    } catch (RuntimeException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid Composer metadata-url", e);
    }
  }

  static String remoteUrl(RepositoryRuntime runtime, String remotePath) {
    URI candidate = URI.create(remotePath);
    if (candidate.isAbsolute()) {
      String scheme = candidate.getScheme();
      if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
        throw new MavenExceptions.BadUpstreamException("Unsupported Composer upstream URL");
      }
      return candidate.toString();
    }
    int query = remotePath.indexOf('?');
    if (query < 0) return RemoteUrlBuilder.repositoryPathString(runtime.proxyRemoteUrl(), remotePath);
    return RemoteUrlBuilder.repositoryPathWithQueryString(
        runtime.proxyRemoteUrl(), remotePath.substring(0, query), remotePath.substring(query + 1));
  }

  private static String listCachePath(String filter) {
    String key = filter == null || filter.isBlank() ? "all" : ComposerResponses.sha256(filter).substring(0, 24);
    return "_composer/lists/" + key + ".json";
  }

  private static String stripQuery(String path) {
    int query = path.indexOf('?');
    return query < 0 ? path : path.substring(0, query);
  }

  private static String normalizePath(String path) {
    String value = stripQuery(path == null ? "" : path.trim());
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static Instant instant(Object value) {
    try {
      return value == null ? null : Instant.parse(value.toString());
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Map<String, Object> stringMap(Map<?, ?> map) {
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> {
      if (key != null) result.put(key.toString(), value);
    });
    return result;
  }

  private static void ensureProxy(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.COMPOSER || !runtime.isProxy()) {
      throw new MavenExceptions.MethodNotAllowed("Operation is only valid on proxy Composer repositories");
    }
  }

  record Route(String remoteUrl, String packageName, String version, String fileName, String shasum) {
  }

  private record CachedJson(Map<String, Object> body, Instant lastModified) {
    CachedJson withLastModified(Instant value) {
      return new CachedJson(body, value);
    }
  }
}
