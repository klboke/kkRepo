package com.github.klboke.kkrepo.server.nuget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.nuget.NugetPath;
import com.github.klboke.kkrepo.protocol.nuget.NugetPathParser;
import com.github.klboke.kkrepo.protocol.nuget.NugetPaths;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

@Service
public class NugetService {
  private static final String NUPKG_CONTENT_TYPE = "application/zip";
  private static final String NUSPEC_CONTENT_TYPE = "application/xml";

  private final RawHostedService hosted;
  private final RawProxyService proxy;
  private final AssetDao assetDao;
  private final ObjectMapper objectMapper;
  private final NugetPathParser parser = new NugetPathParser();

  public NugetService(
      RawHostedService hosted,
      RawProxyService proxy,
      AssetDao assetDao,
      ObjectMapper objectMapper) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.assetDao = assetDao;
    this.objectMapper = objectMapper;
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      String rawPath,
      String repositoryBaseUrl,
      HttpServletRequest request,
      boolean headOnly) {
    NugetPath path = parser.parse(rawPath);
    return switch (path.kind()) {
      case SERVICE_INDEX -> serviceIndex(repositoryBaseUrl, headOnly);
      case QUERY -> query(runtime, request, repositoryBaseUrl, headOnly);
      case AUTOCOMPLETE -> autocomplete(runtime, request, headOnly);
      case FLAT_CONTAINER_VERSION_INDEX -> versionIndex(runtime, path.packageId(), headOnly);
      case REGISTRATION_INDEX -> registrationIndex(runtime, path.packageId(), repositoryBaseUrl, headOnly);
      case FLAT_CONTAINER_PACKAGE, FLAT_CONTAINER_NUSPEC, RAW ->
          dispatchRawGet(runtime, path.rawPath(), headOnly);
      case PACKAGE_PUBLISH, PACKAGE_DELETE -> throw new MavenExceptions.MethodNotAllowed(
          "NuGet package publish requires PUT/DELETE");
    };
  }

  public MavenResponse putPackage(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      String contentType,
      String createdBy,
      String createdByIp) {
    ensureHosted(runtime);
    NugetPath path = parser.parse(rawPath);
    if (path.kind() != NugetPath.Kind.PACKAGE_PUBLISH) {
      throw new MavenExceptions.MethodNotAllowed("Unsupported NuGet PUT path: " + rawPath);
    }
    Path temp = null;
    try {
      temp = Files.createTempFile("kkrepo-nuget-", ".nupkg");
      try (OutputStream out = Files.newOutputStream(temp)) {
        body.transferTo(out);
      }
      NugetPackageMetadata metadata = readPackageMetadata(temp);
      String packagePath = NugetPaths.flatContainerPackage(metadata.id(), metadata.version());
      MavenResponse response;
      try (InputStream storedBody = Files.newInputStream(temp)) {
        response = hosted.put(runtime, packagePath, storedBody,
            NUPKG_CONTENT_TYPE, createdBy, createdByIp);
      }
      hosted.putGenerated(runtime, NugetPaths.flatContainerNuspec(metadata.id(), metadata.version()),
          new ByteArrayInputStream(metadata.nuspec()), NUSPEC_CONTENT_TYPE, createdBy, createdByIp);
      return response;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to store NuGet package", e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // best effort cleanup
        }
      }
    }
  }

  public MavenResponse deletePackage(RepositoryRuntime runtime, String rawPath) {
    ensureHosted(runtime);
    NugetPath path = parser.parse(rawPath);
    if (path.kind() == NugetPath.Kind.PACKAGE_DELETE) {
      int deleted = 0;
      deleted += statusDeleted(hosted.delete(runtime, NugetPaths.flatContainerPackage(path.packageId(), path.version())));
      deleted += statusDeleted(hosted.delete(runtime, NugetPaths.flatContainerNuspec(path.packageId(), path.version())));
      return MavenResponse.noBody(deleted == 0 ? 404 : 204);
    }
    if (path.kind() == NugetPath.Kind.FLAT_CONTAINER_PACKAGE
        || path.kind() == NugetPath.Kind.FLAT_CONTAINER_NUSPEC
        || path.kind() == NugetPath.Kind.RAW) {
      return hosted.delete(runtime, path.rawPath());
    }
    throw new MavenExceptions.MethodNotAllowed("Unsupported NuGet DELETE path: " + rawPath);
  }

  private MavenResponse dispatchRawGet(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.get(runtime, rawPath, headOnly);
      case PROXY -> proxyGet(runtime, rawPath, headOnly);
      case GROUP -> firstWin(runtime, rawPath, headOnly);
    };
  }

  private MavenResponse proxyGet(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    return proxy.getAssetFromUrl(runtime, rawPath, remoteUrlForPath(runtime, rawPath), headOnly);
  }

  private MavenResponse firstWin(RepositoryRuntime group, String rawPath, boolean headOnly) {
    if (group.members().isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException(rawPath);
    }
    for (RepositoryRuntime member : group.members()) {
      try {
        return switch (member.type()) {
          case HOSTED -> hosted.get(member, rawPath, headOnly);
          case PROXY -> proxyGet(member, rawPath, headOnly);
          case GROUP -> firstWin(member, rawPath, headOnly);
        };
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // try next member
      } catch (MavenExceptions.BadUpstreamException e) {
        // Nexus group repositories only return successful member responses; keep probing.
      } catch (MavenExceptions.MethodNotAllowed ignored) {
        // skip incompatible member
      }
    }
    throw new MavenExceptions.MavenNotFoundException(rawPath);
  }

  private MavenResponse serviceIndex(String repositoryBaseUrl, boolean headOnly) {
    String base = repositoryBaseUrl.endsWith("/") ? repositoryBaseUrl : repositoryBaseUrl + "/";
    List<Map<String, Object>> resources = List.of(
        resource(base + "v3-flatcontainer/", "PackageBaseAddress/3.0.0"),
        resource(base + "query", "SearchQueryService/3.0.0-rc"),
        resource(base + "query", "SearchQueryService/3.0.0-beta"),
        resource(base + "query", "SearchQueryService/3.0.0"),
        resource(base + "autocomplete", "SearchAutocompleteService/3.0.0-rc"),
        resource(base + "autocomplete", "SearchAutocompleteService/3.0.0-beta"),
        resource(base + "autocomplete", "SearchAutocompleteService/3.0.0"),
        resource(base + "v3/registration5-semver1/", "RegistrationsBaseUrl/3.0.0"),
        resource(base + "v3/registration5-semver1/", "RegistrationsBaseUrl/3.4.0"),
        resource(base + "v3/registration5-semver1/", "RegistrationsBaseUrl/3.6.0"),
        resource(base + NugetPaths.PACKAGE_PUBLISH, "PackagePublish/2.0.0"));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", "3.0.0");
    body.put("resources", resources);
    return json(body, headOnly);
  }

  private MavenResponse query(
      RepositoryRuntime runtime,
      HttpServletRequest request,
      String repositoryBaseUrl,
      boolean headOnly) {
    if (runtime.type() == RepositoryType.PROXY) {
      return proxyGet(runtime, queryStringPath("query", request), headOnly);
    }
    String q = lowerParam(request, "q");
    int skip = parseNonNegativeInt(request == null ? null : request.getParameter("skip"), 0);
    int take = parsePositiveInt(request == null ? null : request.getParameter("take"), 20);
    if (take > 1000) take = 1000;
    List<PackageSummary> all = runtime.type() == RepositoryType.GROUP
        ? groupPackageSummaries(runtime, request, q, repositoryBaseUrl)
        : packageSummaries(runtime, q, repositoryBaseUrl);
    int from = Math.min(skip, all.size());
    int to = Math.min(from + take, all.size());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("totalHits", all.size());
    body.put("data", all.subList(from, to).stream().map(PackageSummary::asSearchResult).toList());
    return json(body, headOnly);
  }

  private MavenResponse autocomplete(
      RepositoryRuntime runtime,
      HttpServletRequest request,
      boolean headOnly) {
    if (runtime.type() == RepositoryType.PROXY) {
      return proxyGet(runtime, queryStringPath("autocomplete", request), headOnly);
    }
    String q = lowerParam(request, "q");
    int skip = parseNonNegativeInt(request == null ? null : request.getParameter("skip"), 0);
    int take = parsePositiveInt(request == null ? null : request.getParameter("take"), 20);
    if (take > 1000) take = 1000;
    List<String> ids = runtime.type() == RepositoryType.GROUP
        ? groupPackageIds(runtime, request, q)
        : packageIds(runtime, q);
    int from = Math.min(skip, ids.size());
    int to = Math.min(from + take, ids.size());
    return json(Map.of("totalHits", ids.size(), "data", ids.subList(from, to)), headOnly);
  }

  private MavenResponse versionIndex(RepositoryRuntime runtime, String packageId, boolean headOnly) {
    if (runtime.type() == RepositoryType.PROXY) {
      return proxyGet(runtime, NugetPaths.flatContainerVersionIndex(packageId), headOnly);
    }
    List<String> versions = versions(runtime, packageId);
    return json(Map.of("versions", versions), headOnly);
  }

  private MavenResponse registrationIndex(
      RepositoryRuntime runtime,
      String packageId,
      String repositoryBaseUrl,
      boolean headOnly) {
    if (runtime.type() == RepositoryType.PROXY) {
      return proxyGet(runtime, NugetPaths.registrationIndex(packageId), headOnly);
    }
    String normalizedId = NugetPaths.normalizePackageId(packageId);
    String base = repositoryBaseUrl.endsWith("/") ? repositoryBaseUrl : repositoryBaseUrl + "/";
    List<Map<String, Object>> items = new ArrayList<>();
    for (String version : versions(runtime, packageId)) {
      Map<String, Object> catalogEntry = new LinkedHashMap<>();
      catalogEntry.put("@id", base + "v3/registration5-semver1/" + normalizedId + "/" + version + ".json");
      catalogEntry.put("id", packageId);
      catalogEntry.put("version", version);
      catalogEntry.put("listed", true);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("@id", base + "v3/registration5-semver1/" + normalizedId + "/" + version + ".json");
      item.put("catalogEntry", catalogEntry);
      item.put("packageContent", base + NugetPaths.flatContainerPackage(packageId, version));
      items.add(item);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("@id", base + NugetPaths.registrationIndex(packageId));
    body.put("count", items.size());
    body.put("items", List.of(Map.of("count", items.size(), "items", items)));
    return json(body, headOnly);
  }

  private List<String> versions(RepositoryRuntime runtime, String packageId) {
    return switch (runtime.type()) {
      case HOSTED, PROXY -> localVersions(runtime, packageId);
      case GROUP -> groupVersions(runtime, packageId);
    };
  }

  private List<String> packageIds(RepositoryRuntime runtime, String query) {
    TreeSet<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    if (runtime.type() == RepositoryType.GROUP) {
      for (RepositoryRuntime member : runtime.members()) {
        try {
          ids.addAll(packageIds(member, query));
        } catch (MavenExceptions.MavenNotFoundException ignored) {
          // skip empty member
        }
      }
      return List.copyOf(ids);
    }
    ids.addAll(localPackageVersionsById(runtime, query).keySet());
    return List.copyOf(ids);
  }

  private List<PackageSummary> packageSummaries(
      RepositoryRuntime runtime,
      String query,
      String repositoryBaseUrl) {
    String base = repositoryBaseUrl.endsWith("/") ? repositoryBaseUrl : repositoryBaseUrl + "/";
    List<PackageSummary> summaries = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : localPackageVersionsById(runtime, query).entrySet()) {
      String packageId = entry.getKey();
      List<String> versions = entry.getValue();
      if (versions.isEmpty()) continue;
      String latest = versions.getLast();
      summaries.add(new PackageSummary(packageId, latest, versions, base));
    }
    return summaries;
  }

  private List<String> groupVersions(RepositoryRuntime group, String packageId) {
    TreeSet<String> versions = new TreeSet<>(NugetService::compareVersions);
    MavenExceptions.BadUpstreamException lastUpstream = null;
    for (RepositoryRuntime member : group.members()) {
      try {
        if (member.type() == RepositoryType.PROXY) {
          versions.addAll(readVersionsFromResponse(
              proxyGet(member, NugetPaths.flatContainerVersionIndex(packageId), false)));
        } else {
          versions.addAll(versions(member, packageId));
        }
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // absent packages are normal in a group
      } catch (MavenExceptions.BadUpstreamException e) {
        lastUpstream = e;
      }
    }
    if (versions.isEmpty() && lastUpstream != null) throw lastUpstream;
    return List.copyOf(versions);
  }

  private List<String> groupPackageIds(RepositoryRuntime group, HttpServletRequest request, String query) {
    TreeSet<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    MavenExceptions.BadUpstreamException lastUpstream = null;
    for (RepositoryRuntime member : group.members()) {
      try {
        if (member.type() == RepositoryType.PROXY) {
          ids.addAll(readAutocompleteIds(
              proxyGet(member, queryStringPath("autocomplete", request), false)));
        } else if (member.type() == RepositoryType.GROUP) {
          ids.addAll(groupPackageIds(member, request, query));
        } else {
          ids.addAll(packageIds(member, query));
        }
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // absent search data is normal in a group
      } catch (MavenExceptions.BadUpstreamException e) {
        lastUpstream = e;
      }
    }
    if (ids.isEmpty() && lastUpstream != null) throw lastUpstream;
    if (query == null || query.isBlank()) return List.copyOf(ids);
    return ids.stream().filter(id -> matchesQuery(id, query)).toList();
  }

  private List<PackageSummary> groupPackageSummaries(
      RepositoryRuntime group,
      HttpServletRequest request,
      String query,
      String repositoryBaseUrl) {
    Map<String, PackageSummary> byId = new LinkedHashMap<>();
    MavenExceptions.BadUpstreamException lastUpstream = null;
    for (RepositoryRuntime member : group.members()) {
      try {
        List<PackageSummary> summaries = switch (member.type()) {
          case PROXY -> readSearchSummaries(
              proxyGet(member, queryStringPath("query", request), false), repositoryBaseUrl);
          case GROUP -> groupPackageSummaries(member, request, query, repositoryBaseUrl);
          case HOSTED -> packageSummaries(member, query, repositoryBaseUrl);
        };
        for (PackageSummary summary : summaries) {
          byId.putIfAbsent(summary.id().toLowerCase(Locale.ROOT), summary);
        }
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // absent search data is normal in a group
      } catch (MavenExceptions.BadUpstreamException e) {
        lastUpstream = e;
      }
    }
    if (byId.isEmpty() && lastUpstream != null) throw lastUpstream;
    return byId.values().stream()
        .filter(summary -> matchesQuery(summary.id(), query))
        .sorted((left, right) -> left.id().compareToIgnoreCase(right.id()))
        .toList();
  }

  private List<String> readVersionsFromResponse(MavenResponse response) {
    if (!response.hasBody()) {
      return List.of();
    }
    try (InputStream body = response.body()) {
      JsonNode root = objectMapper.readTree(body);
      JsonNode rawVersions = root.get("versions");
      if (rawVersions == null || !rawVersions.isArray()) {
        return List.of();
      }
      List<String> versions = new ArrayList<>();
      rawVersions.forEach(v -> {
        if (v.isTextual() && !v.asText().isBlank()) versions.add(v.asText());
      });
      return versions;
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid NuGet version index", e);
    }
  }

  private List<String> readAutocompleteIds(MavenResponse response) {
    if (!response.hasBody()) {
      return List.of();
    }
    try (InputStream body = response.body()) {
      JsonNode root = objectMapper.readTree(body);
      JsonNode data = root.get("data");
      if (data == null || !data.isArray()) return List.of();
      List<String> ids = new ArrayList<>();
      data.forEach(id -> {
        if (id.isTextual() && !id.asText().isBlank()) ids.add(id.asText());
      });
      return ids;
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid NuGet autocomplete response", e);
    }
  }

  private List<PackageSummary> readSearchSummaries(MavenResponse response, String repositoryBaseUrl) {
    if (!response.hasBody()) {
      return List.of();
    }
    String base = repositoryBaseUrl.endsWith("/") ? repositoryBaseUrl : repositoryBaseUrl + "/";
    try (InputStream body = response.body()) {
      JsonNode root = objectMapper.readTree(body);
      JsonNode data = root.get("data");
      if (data == null || !data.isArray()) return List.of();
      List<PackageSummary> summaries = new ArrayList<>();
      data.forEach(item -> {
        JsonNode idNode = item.get("id");
        if (idNode == null || !idNode.isTextual() || idNode.asText().isBlank()) return;
        String id = idNode.asText();
        List<String> versions = new ArrayList<>();
        JsonNode rawVersions = item.get("versions");
        if (rawVersions != null && rawVersions.isArray()) {
          rawVersions.forEach(versionNode -> {
            JsonNode value = versionNode.get("version");
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
              versions.add(value.asText());
            }
          });
        }
        JsonNode latest = item.get("version");
        if (versions.isEmpty() && latest != null && latest.isTextual() && !latest.asText().isBlank()) {
          versions.add(latest.asText());
        }
        if (!versions.isEmpty()) {
          summaries.add(new PackageSummary(id, versions.getLast(), versions, base));
        }
      });
      return summaries;
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid NuGet search response", e);
    }
  }

  private List<String> localVersions(RepositoryRuntime runtime, String packageId) {
    String prefix = "v3-flatcontainer/" + NugetPaths.normalizePackageId(packageId) + "/";
    TreeSet<String> versions = new TreeSet<>(NugetService::compareVersions);
    assetDao.listAssetsByPrefix(runtime.id(), prefix).stream()
        .map(AssetRecord::path)
        .filter(path -> path.endsWith(".nupkg"))
        .map(path -> versionFromFlatPackagePath(prefix, path))
        .filter(version -> version != null && !version.isBlank())
        .forEach(versions::add);
    return List.copyOf(versions);
  }

  private Map<String, List<String>> localPackageVersionsById(RepositoryRuntime runtime, String query) {
    Map<String, TreeSet<String>> versionsById = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (AssetRecord asset : assetDao.listAssetsByPrefix(runtime.id(), "v3-flatcontainer/")) {
      String packageId = packageIdFromFlatPackagePath(asset.path());
      if (packageId == null || !matchesQuery(packageId, query)) {
        continue;
      }
      String prefix = "v3-flatcontainer/" + NugetPaths.normalizePackageId(packageId) + "/";
      String version = versionFromFlatPackagePath(prefix, asset.path());
      if (version == null || version.isBlank()) {
        continue;
      }
      versionsById.computeIfAbsent(packageId, ignored -> new TreeSet<>(NugetService::compareVersions))
          .add(version);
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    versionsById.forEach((id, versions) -> result.put(id, List.copyOf(versions)));
    return result;
  }

  private MavenResponse json(Object value, boolean headOnly) {
    byte[] bytes;
    try {
      bytes = objectMapper.writeValueAsBytes(value);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to serialize NuGet response", e);
    }
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, MediaType.APPLICATION_JSON_VALUE, null, null);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
        MediaType.APPLICATION_JSON_VALUE, null, null);
  }

  private static Map<String, Object> resource(String id, String type) {
    Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("@id", id);
    resource.put("@type", type);
    return resource;
  }

  private static String versionFromFlatPackagePath(String prefix, String path) {
    if (!path.startsWith(prefix)) return null;
    String rest = path.substring(prefix.length());
    int slash = rest.indexOf('/');
    if (slash <= 0) return null;
    return rest.substring(0, slash).toLowerCase(Locale.ROOT);
  }

  private static String packageIdFromFlatPackagePath(String path) {
    String prefix = "v3-flatcontainer/";
    if (path == null || !path.startsWith(prefix) || !path.endsWith(".nupkg")) return null;
    String rest = path.substring(prefix.length());
    int slash = rest.indexOf('/');
    if (slash <= 0) return null;
    return rest.substring(0, slash).toLowerCase(Locale.ROOT);
  }

  private static int compareVersions(String left, String right) {
    return left.compareToIgnoreCase(right);
  }

  static String remoteUrlForPath(RepositoryRuntime runtime, String path) {
    String base = runtime.proxyRemoteUrl();
    if (base == null || base.isBlank()) {
      throw new IllegalStateException("NuGet proxy " + runtime.name() + " has no remote URL configured");
    }
    String pathOnly = path == null ? "" : path;
    String pathQuery = "";
    int pathQueryIndex = pathOnly.indexOf('?');
    if (pathQueryIndex >= 0) {
      pathQuery = pathOnly.substring(pathQueryIndex + 1);
      pathOnly = pathOnly.substring(0, pathQueryIndex);
    }
    String trimmed = base.trim();
    int query = trimmed.indexOf('?');
    String suffix = query < 0 ? "" : trimmed.substring(query);
    if (query >= 0) trimmed = trimmed.substring(0, query);
    if (trimmed.endsWith("/v3/index.json")) {
      trimmed = trimmed.substring(0, trimmed.length() - "/v3/index.json".length());
    } else if (trimmed.endsWith("/index.json")) {
      trimmed = trimmed.substring(0, trimmed.length() - "/index.json".length());
    }
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed + "/" + encodePath(pathOnly) + querySuffix(pathQuery, suffix);
  }

  private static String querySuffix(String pathQuery, String baseQuerySuffix) {
    boolean hasPathQuery = pathQuery != null && !pathQuery.isBlank();
    boolean hasBaseQuery = baseQuerySuffix != null && !baseQuerySuffix.isBlank();
    if (!hasPathQuery) return hasBaseQuery ? baseQuerySuffix : "";
    if (!hasBaseQuery) return "?" + pathQuery;
    return "?" + pathQuery + "&" + baseQuerySuffix.substring(1);
  }

  private static String encodePath(String path) {
    String[] segments = path.split("/", -1);
    StringBuilder out = new StringBuilder(path.length() + 16);
    for (int i = 0; i < segments.length; i++) {
      if (i > 0) out.append('/');
      out.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
    }
    return out.toString();
  }

  private static int statusDeleted(MavenResponse response) {
    return response.status() == 204 ? 1 : 0;
  }

  private static String lowerParam(HttpServletRequest request, String name) {
    String value = request == null ? null : request.getParameter(name);
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean matchesQuery(String packageId, String query) {
    return query == null || query.isBlank() || packageId.toLowerCase(Locale.ROOT).contains(query);
  }

  private static int parsePositiveInt(String raw, int fallback) {
    int value = parseNonNegativeInt(raw, fallback);
    return value <= 0 ? fallback : value;
  }

  private static int parseNonNegativeInt(String raw, int fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return Math.max(0, Integer.parseInt(raw));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String queryStringPath(String path, HttpServletRequest request) {
    String query = request == null ? null : request.getQueryString();
    if ((query == null || query.isBlank()) && request != null && !request.getParameterMap().isEmpty()) {
      StringBuilder rebuilt = new StringBuilder();
      request.getParameterMap().forEach((name, values) -> {
        if (values == null || values.length == 0) {
          appendQueryPair(rebuilt, name, "");
          return;
        }
        for (String value : values) {
          appendQueryPair(rebuilt, name, value);
        }
      });
      query = rebuilt.toString();
    }
    return query == null || query.isBlank() ? path : path + "?" + query;
  }

  private static void appendQueryPair(StringBuilder out, String name, String value) {
    if (!out.isEmpty()) out.append('&');
    out.append(URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
    out.append('=');
    out.append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20"));
  }

  private static NugetPackageMetadata readPackageMetadata(Path nupkg) throws IOException {
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(nupkg))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (!entry.isDirectory() && name.toLowerCase(Locale.ROOT).endsWith(".nuspec")) {
          byte[] nuspec = readAll(zip);
          NugetPackageCoordinate coordinate = parseNuspec(new ByteArrayInputStream(nuspec));
          return new NugetPackageMetadata(coordinate.id(), coordinate.version(), nuspec);
        }
      }
    }
    throw new MavenExceptions.MethodNotAllowed("NuGet package is missing a .nuspec manifest");
  }

  private static byte[] readAll(InputStream input) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    input.transferTo(out);
    return out.toByteArray();
  }

  private static NugetPackageCoordinate parseNuspec(InputStream xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      Element metadata = (Element) factory.newDocumentBuilder()
          .parse(xml)
          .getElementsByTagName("metadata")
          .item(0);
      if (metadata == null) {
        throw new MavenExceptions.MethodNotAllowed("NuGet .nuspec is missing metadata");
      }
      String id = text(metadata, "id");
      String version = text(metadata, "version");
      if (id == null || version == null) {
        throw new MavenExceptions.MethodNotAllowed("NuGet .nuspec is missing id or version");
      }
      return new NugetPackageCoordinate(id, version);
    } catch (MavenExceptions.MethodNotAllowed e) {
      throw e;
    } catch (Exception e) {
      throw new MavenExceptions.MethodNotAllowed("Invalid NuGet .nuspec manifest");
    }
  }

  private static String text(Element parent, String tag) {
    var nodes = parent.getElementsByTagName(tag);
    if (nodes.getLength() == 0 || nodes.item(0) == null) return null;
    String value = nodes.item(0).getTextContent();
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static void ensureHosted(RepositoryRuntime runtime) {
    if (runtime.type() != RepositoryType.HOSTED) {
      throw new MavenExceptions.MethodNotAllowed("Operation is only valid on hosted NuGet repositories");
    }
  }

  private record NugetPackageCoordinate(String id, String version) {
  }

  private record NugetPackageMetadata(String id, String version, byte[] nuspec) {
  }

  private record PackageSummary(String id, String latestVersion, List<String> versions, String base) {
    Map<String, Object> asSearchResult() {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("@id", base + NugetPaths.registrationIndex(id));
      body.put("@type", "Package");
      body.put("registration", base + NugetPaths.registrationIndex(id));
      body.put("id", id);
      body.put("version", latestVersion);
      body.put("description", "");
      body.put("summary", "");
      body.put("title", id);
      body.put("totalDownloads", 0);
      body.put("verified", false);
      body.put("versions", versions.stream()
          .map(version -> Map.<String, Object>of(
              "version", version,
              "downloads", 0,
              "@id", base + "v3/registration5-semver1/" + NugetPaths.normalizePackageId(id) + "/" + version + ".json"))
          .toList());
      return body;
    }
  }
}
