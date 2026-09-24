package com.github.klboke.kkrepo.server.nuget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.core.http.UriPathDecoder;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.protocol.nuget.NugetPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/** NuGet V3 resources are absolute URLs, not paths relative to the service index. */
final class NugetUpstreamResources {
  static final String SOURCE_MEMBER_QUERY = "_kkrepoNugetSource";
  private static final int MAX_INDEX_BYTES = 1024 * 1024;
  private static final int MAX_REGISTRATION_BYTES = 32 * 1024 * 1024;
  private static final String FLAT = "v3-flatcontainer/";
  private static final String REGISTRATION = "v3/registration5-semver1/";
  private static final List<String> REGISTRATION_VERSIONS =
      List.of("/3.6.0", "/3.4.0", "/3.0.0", "/3.0.0-rc", "/3.0.0-beta", "");
  private static final List<String> LEGACY_REGISTRATION_VERSIONS =
      List.of("/3.4.0", "/3.0.0", "/3.0.0-rc", "/3.0.0-beta", "");
  private static final List<String> VERSIONS = List.of("/3.5.0", "/3.0.0", "/3.0.0-rc", "/3.0.0-beta", "");

  private NugetUpstreamResources() {
  }

  static MavenResponse get(
      RawProxyService proxy, ObjectMapper mapper, RepositoryRuntime runtime,
      String path, boolean headOnly) {
    int separator = path.indexOf('?');
    String operation = separator < 0 ? path : path.substring(0, separator);
    String query = separator < 0 ? "" : path.substring(separator + 1);
    String type = "query".equals(operation) ? "SearchQueryService" : "SearchAutocompleteService";
    JsonNode resources = resources(proxy, mapper, runtime);
    String endpoint = resourceUrl(resources, type, VERSIONS);
    rejectQueryOverrides(endpoint, query);
    String remoteUrl = endpoint + (query.isEmpty() ? "" :
        (URI.create(endpoint).getRawQuery() == null ? "?" : "&") + query);
    // The shared fetcher validates destinations and only sends credentials to the configured origin.
    return proxy.getMetadataFromUrlHidden(
        runtime, cacheKey(operation, remoteUrl), remoteUrl, headOnly);
  }

  static boolean isPackagePath(String path) {
    return path.startsWith(FLAT) || registrationPrefix(path) != null;
  }

  static MavenResponse getPackage(
      RawProxyService proxy, ObjectMapper mapper, RepositoryRuntime runtime,
      String path, String repositoryBaseUrl, Long groupId, boolean headOnly) {
    return getPackage(proxy, mapper, runtime, path, repositoryBaseUrl, groupId, headOnly, null);
  }

  static MavenResponse getPackage(
      RawProxyService proxy, ObjectMapper mapper, RepositoryRuntime runtime,
      String path, String repositoryBaseUrl, Long groupId, boolean headOnly, String sourceToken) {
    JsonNode resources;
    try {
      resources = resources(proxy, mapper, runtime);
    } catch (MavenExceptions.BadUpstreamException failure) {
      if (sourceToken != null || path.contains("?")) throw failure;
      String canonical = path.split("\\?", 2)[0];
      if (canonical.startsWith(FLAT)
          && (canonical.endsWith(".nupkg") || canonical.endsWith(".nuspec") || canonical.endsWith("/index.json"))) {
        return proxy.getLegacyNugetAsset(runtime, canonical, headOnly).orElseThrow(() -> failure);
      }
      throw failure;
    }
    if (path.startsWith(FLAT)) {
      String endpoint = resourceUrl(resources, "PackageBaseAddress", List.of("/3.0.0"));
      NugetResourceLinkToken.requireResource(sourceToken, NugetResourceLinkToken.identity(runtime.proxyRemoteUrl(), endpoint));
      String remoteUrl = appendPath(endpoint, path.substring(FLAT.length()));
      int query = path.indexOf('?');
      String assetPath = query < 0 ? path : path.substring(0, query);
      if (assetPath.endsWith("/index.json")) {
        try {
          return proxy.getMetadataFromUrlHidden(runtime, cacheKey("versions", remoteUrl), remoteUrl, headOnly);
        } catch (MavenExceptions.BadUpstreamException failure) {
          // Before discovery, version indexes lived at their canonical visible path.
          // Only an outage can use that unverified entry; 404/410 remain authoritative.
          // Legacy bytes cannot establish the identity of a query-selected representation.
          if (sourceToken != null || query >= 0) throw failure;
          return proxy.getLegacyNugetAsset(runtime, assetPath, headOnly).orElseThrow(() -> failure);
        }
      }
      // Preserve canonical asset paths, Browse visibility and download-policy checks for package bodies.
      // RawProxyService binds cache content to the complete URL, including opaque queries.
      return proxy.getAssetFromUrl(runtime, assetPath, remoteUrl, headOnly);
    }
    String registrationPrefix = registrationPrefix(path);
    String endpoint = resourceUrl(resources, "RegistrationsBaseUrl",
        REGISTRATION.equals(registrationPrefix) ? LEGACY_REGISTRATION_VERSIONS : REGISTRATION_VERSIONS);
    String flatEndpoint = resourceUrl(resources, "PackageBaseAddress", List.of("/3.0.0"));
    NugetResourceLinkToken.requireResource(sourceToken, NugetResourceLinkToken.identity(runtime.proxyRemoteUrl(), endpoint));
    String remoteUrl = appendPath(endpoint, path.substring(registrationPrefix.length()));
    // Registration bodies contain package links. Refresh them when either resource moves,
    // even when discovery expires before an otherwise fresh registration cache entry.
    MavenResponse response = proxy.getMetadataFromUrlHidden(
        runtime, cacheKey("registration", remoteUrl + "\n" + flatEndpoint), remoteUrl, false);
    JsonNode document = readJson(mapper, response, MAX_REGISTRATION_BYTES, "registration");
    String base = repositoryBaseUrl.endsWith("/") ? repositoryBaseUrl : repositoryBaseUrl + "/";
    rewriteLinks(document, endpoint, flatEndpoint, base, registrationPrefix, groupId, runtime);
    try {
      byte[] bytes = mapper.writeValueAsBytes(document);
      return headOnly ? MavenResponse.noBody(200, bytes.length, "application/json", null, null)
          : MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/json", null, null);
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid NuGet registration");
    }
  }

  static String registrationPrefix(String path) {
    if (path.startsWith(REGISTRATION)) return REGISTRATION;
    String semver2 = "v3/registration5-semver2/";
    return path.startsWith(semver2) ? semver2 : null;
  }

  private static String appendPath(String endpoint, String suffix) {
    URI uri = URI.create(endpoint);
    int suffixQuery = suffix.indexOf('?');
    String extraQuery = suffixQuery < 0 ? "" : suffix.substring(suffixQuery + 1);
    if (suffixQuery >= 0) suffix = suffix.substring(0, suffixQuery);
    rejectQueryOverrides(endpoint, extraQuery);
    for (String segment : suffix.split("/", -1)) {
      String decoded;
      try {
        decoded = UriPathDecoder.decodeComponent(segment);
      } catch (IllegalArgumentException invalid) {
        throw new MavenExceptions.MavenNotFoundException("Invalid NuGet resource path");
      }
      if (Arrays.stream(decoded.replace('\\', '/').split("/", -1))
          .anyMatch(part -> part.equals(".") || part.equals(".."))
          || decoded.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
        throw new MavenExceptions.MavenNotFoundException("Invalid NuGet resource path");
      }
    }
    // The NuGet servlet boundary supplies a raw path. Preserve valid escapes instead
    // of encoding '%' a second time; encoded separators stay inside their segment.
    // Encode in path context: a suffix's leading '/' or ':' cannot change the authority
    // because it is appended to an absolute resource URL, never URI.resolve'd on its own.
    String prefix = "resource/";
    String encoded = RemoteUrlBuilder.encodePath(prefix + suffix).substring(prefix.length());
    int query = endpoint.indexOf('?');
    String base = query < 0 ? endpoint : endpoint.substring(0, query);
    return base + (base.endsWith("/") ? "" : "/") + encoded
        + (uri.getRawQuery() == null ? (extraQuery.isEmpty() ? "" : "?" + extraQuery)
            : "?" + uri.getRawQuery() + (extraQuery.isEmpty() ? "" : "&" + extraQuery));
  }

  private static void rewriteLinks(JsonNode node, String registration, String flat, String base, String registrationPrefix, Long groupId, RepositoryRuntime runtime) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      for (String field : List.of("@id", "parent", "registration", "packageContent")) {
        JsonNode value = object.get(field);
        if (value != null && value.isTextual()) {
          boolean packageContent = field.equals("packageContent");
          object.put(field, localLink(value.asText(), packageContent ? flat : registration,
              base, packageContent ? FLAT : registrationPrefix, groupId, runtime));
        }
      }
    }
    if (node.isContainerNode()) {
      for (JsonNode child : node) rewriteLinks(child, registration, flat, base, registrationPrefix, groupId, runtime);
    }
  }

  private static String localLink(
      String value, String endpoint, String base, String prefix, Long groupId, RepositoryRuntime runtime) {
    URI source = URI.create(endpoint);
    URI link;
    try {
      link = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid NuGet registration link");
    }
    if (link.getHost() == null || !dnsHost(source).equalsIgnoreCase(dnsHost(link))
        || !source.getScheme().equalsIgnoreCase(link.getScheme()) || effectivePort(source) != effectivePort(link)) return value;
    if (link.getRawUserInfo() != null) throw new MavenExceptions.BadUpstreamException("Invalid NuGet registration link");
    String root = normalizedPath(source);
    if (!root.endsWith("/")) root += "/";
    String linkPath = normalizedPath(link);
    if (!linkPath.startsWith(root)) return value;
    String suffix = linkPath.substring(root.length());
    String query = link.getRawQuery();
    // Resource query credentials are applied server-side, never copied into client-facing links.
    if (query != null && source.getRawQuery() != null) {
      var resourceKeys = Arrays.stream(source.getRawQuery().split("&", -1))
          .map(NugetUpstreamResources::queryName).collect(Collectors.toSet());
      query = Arrays.stream(query.split("&", -1))
          .filter(pair -> !resourceKeys.contains(queryName(pair))).collect(Collectors.joining("&"));
    }
    String target = prefix + suffix + (query == null || query.isEmpty() ? "" : "?" + query);
    if (groupId != null && query != null && !query.isEmpty()) {
      // Bind to the same raw path representation supplied by the NuGet servlet boundary.
      String requestPath = NugetPathParser.normalize(prefix + suffix) + "?" + query;
      target += "&" + SOURCE_MEMBER_QUERY + "=" + NugetResourceLinkToken.issue(groupId, runtime.id(), requestPath,
          NugetResourceLinkToken.identity(runtime.proxyRemoteUrl(), endpoint));
    }
    return base + target + (link.getRawFragment() == null ? "" : "#" + link.getRawFragment());
  }

  private static String dnsHost(URI uri) {
    String host = uri.getHost();
    // A terminal root label is the absolute spelling of the same DNS name (RFC 1034).
    return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
  }

  private static int effectivePort(URI uri) {
    return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static String normalizedPath(URI uri) {
    String raw = URI.create(uri.toASCIIString()).getRawPath();
    StringBuilder path = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      if (ch == '%' && i + 2 < raw.length()) {
        char decoded = (char) Integer.parseInt(raw.substring(i + 1, i + 3), 16);
        if ((decoded >= 'a' && decoded <= 'z') || (decoded >= 'A' && decoded <= 'Z')
            || (decoded >= '0' && decoded <= '9') || "-._~".indexOf(decoded) >= 0) {
          path.append(decoded);
        } else {
          path.append('%').append(raw.substring(i + 1, i + 3).toUpperCase(java.util.Locale.ROOT));
        }
        i += 2;
      } else {
        path.append(ch);
      }
    }
    if (path.isEmpty()) return "/";
    // RFC 3986 dot-segment removal. URI.normalize() also collapses empty segments,
    // which are significant in the opaque page/leaf URLs advertised by NuGet feeds.
    String[] segments = path.substring(1).split("/", -1);
    List<String> normalized = new ArrayList<>();
    for (int i = 0; i < segments.length; i++) {
      String segment = segments[i];
      if (segment.equals("..")) {
        if (!normalized.isEmpty()) normalized.removeLast();
      } else if (!segment.equals(".")) {
        normalized.add(segment);
      }
      if (i == segments.length - 1 && (segment.equals(".") || segment.equals(".."))) {
        normalized.add("");
      }
    }
    return "/" + String.join("/", normalized);
  }

  private static void rejectQueryOverrides(String endpoint, String query) {
    String owned = URI.create(endpoint).getRawQuery();
    if (owned == null || query.isEmpty()) return;
    var keys = Arrays.stream(owned.split("&", -1)).map(NugetUpstreamResources::queryName).collect(Collectors.toSet());
    if (Arrays.stream(query.split("&", -1)).map(NugetUpstreamResources::queryName).anyMatch(keys::contains)) {
      throw new MavenExceptions.BadRequestException("NuGet request cannot override resource query parameters");
    }
  }

  private static String queryName(String pair) {
    try {
      return URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid NuGet resource query");
    }
  }

  private static JsonNode readJson(ObjectMapper mapper, MavenResponse response, int maxBytes, String kind) {
    try (InputStream body = response.body()) {
      if (response.status() != 200 || body == null) {
        throw new MavenExceptions.BadUpstreamException("NuGet " + kind + " is unavailable");
      }
      // Registration 3.4/3.6 resources may always be gzipped. The durable raw cache
      // preserves the bytes, so inspect the signature even on a cache hit.
      PushbackInputStream checked = new PushbackInputStream(body, 2);
      byte[] signature = checked.readNBytes(2);
      checked.unread(signature);
      byte[] bytes;
      try (InputStream decoded = signature.length == 2 && signature[0] == (byte) 0x1f
          && signature[1] == (byte) 0x8b ? new GZIPInputStream(checked) : checked) {
        bytes = decoded.readNBytes(maxBytes + 1);
      }
      if (bytes.length > maxBytes) throw new MavenExceptions.BadUpstreamException("NuGet " + kind + " is too large");
      JsonNode document = mapper.readTree(bytes);
      if (document == null || !document.isObject()) throw new MavenExceptions.BadUpstreamException("Invalid NuGet " + kind);
      return document;
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid NuGet " + kind);
    }
  }

  private static final class InvalidServiceIndex extends MavenExceptions.BadUpstreamException {
    InvalidServiceIndex() { super("Invalid NuGet service index"); }
  }

  private static InputStream validateServiceIndex(ObjectMapper mapper, InputStream body) {
    try (body) {
      if (body == null) throw new InvalidServiceIndex();
      byte[] bytes = body.readNBytes(MAX_INDEX_BYTES + 1);
      if (bytes.length > MAX_INDEX_BYTES) throw new InvalidServiceIndex();
      JsonNode index = readJson(mapper, MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
          "application/json", null, null), MAX_INDEX_BYTES, "service index");
      if (!index.path("resources").isArray()) throw new InvalidServiceIndex();
      return new ByteArrayInputStream(bytes);
    } catch (IOException | MavenExceptions.BadUpstreamException invalid) {
      throw new InvalidServiceIndex();
    }
  }

  private static JsonNode resources(RawProxyService proxy, ObjectMapper mapper, RepositoryRuntime runtime) {
    String configured = runtime.proxyRemoteUrl();
    String indexUrl = requireHttpUrl(configured == null ? "" : configured.trim());
    return resources(proxy, mapper, runtime, indexUrl, true);
  }

  private static JsonNode resources(
      RawProxyService proxy, ObjectMapper mapper, RepositoryRuntime runtime, String indexUrl, boolean allowRootFallback) {
    // Discovery and protocol metadata use the existing durable cache (DB + blob store).
    // Each replica can rebuild discovery, metadata TTL controls refresh, and URL-derived keys
    // isolate repository reconfiguration and obsolete negative entries from guessed /query URLs.
    boolean rootFallback = allowRootFallback && !URI.create(indexUrl).getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".json");
    MavenResponse response;
    try {
      response = proxy.getMetadataFromUrlHidden(runtime, cacheKey("index", indexUrl), indexUrl, false,
          "nuget-service-index-v1", body -> validateServiceIndex(mapper, body));
    } catch (MavenExceptions.MavenNotFoundException | InvalidServiceIndex failure) {
      if (!rootFallback) throw failure;
      return resources(proxy, mapper, runtime, repositoryRootIndexUrl(indexUrl), false);
    }
    try {
      JsonNode index = readJson(mapper, response, MAX_INDEX_BYTES, "service index");
      if (!index.path("resources").isArray()) {
        throw new MavenExceptions.BadUpstreamException("NuGet service index has no resources array");
      }
      return index.path("resources");
    } catch (MavenExceptions.BadUpstreamException failure) {
      // Try the configured URL verbatim first, including extensionless service indexes.
      // Retain legacy repository-root configuration only after a 404 or non-index body;
      // authentication, policy and transport failures from the fetcher are not retried here.
      if (!rootFallback) throw failure;
      return resources(proxy, mapper, runtime, repositoryRootIndexUrl(indexUrl), false);
    }
  }

  private static String resourceUrl(JsonNode resources, String type, List<String> versions) {
    for (String version : versions) {
      for (JsonNode resource : resources) {
        if ((type + version).equals(resource.path("@type").asText())) {
          return requireHttpUrl(resource.path("@id").asText());
        }
      }
    }
    throw new MavenExceptions.BadUpstreamException("NuGet upstream does not advertise " + type);
  }

  private static String repositoryRootIndexUrl(String url) {
    int queryIndex = url.indexOf('?');
    String base = queryIndex < 0 ? url : url.substring(0, queryIndex);
    return base + (base.endsWith("/") ? "" : "/") + "index.json"
        + (queryIndex < 0 ? "" : url.substring(queryIndex));
  }

  private static String requireHttpUrl(String value) {
    try {
      URI uri = URI.create(value);
      if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null) {
        return value;
      }
    } catch (IllegalArgumentException ignored) {
      // Do not include upstream URLs in errors: they may contain credentials in query strings.
    }
    throw new MavenExceptions.BadUpstreamException("Invalid NuGet resource URL");
  }

  private static String cacheKey(String kind, String url) {
    return "_nuget/" + kind + "/" + HexFormat.of().formatHex(PersistenceHashes.pathHash(url));
  }
}
