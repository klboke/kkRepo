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
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/** NuGet V3 resources are absolute URLs, not paths relative to the service index. */
final class NugetUpstreamResources {
  static final String SOURCE_MEMBER_QUERY = "_kkrepoNugetSource";
  private static final String RESOURCE_QUERY = "_kkrepoNugetQuery";
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
      String remoteUrl = packageUrl(endpoint, path, FLAT, runtime);
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
    String remoteUrl = packageUrl(endpoint, path, registrationPrefix, runtime);
    // Registration bodies contain package links. Refresh them when either resource moves,
    // even when discovery expires before an otherwise fresh registration cache entry.
    JsonNode document = registrationDocument(proxy, mapper, runtime, remoteUrl, endpoint, flatEndpoint, "document");
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

  /** Request-local limits; all reusable metadata still lives in the shared database/blob cache. */
  static final class RegistrationBudget {
    private int documents;
    private long bytes;
    private final int maxDocuments;
    private final long maxBytes;
    RegistrationBudget() { this(512, MAX_REGISTRATION_BYTES); }
    RegistrationBudget(int maxDocuments, long maxBytes) { this.maxDocuments = maxDocuments; this.maxBytes = maxBytes; }
    void next() {
      if (++documents > maxDocuments) throw new RegistrationLimitException();
    }
    void consume(JsonNode document) {
      bytes += document.toString().getBytes(StandardCharsets.UTF_8).length;
      if (bytes > maxBytes) throw new RegistrationLimitException();
    }
  }

  static final class RegistrationLimitException extends MavenExceptions.BadUpstreamException {
    RegistrationLimitException() { super("NuGet group registration exceeds metadata limits"); }
  }

  static List<JsonNode> registrationLeaves(
      RawProxyService proxy, ObjectMapper mapper, RepositoryRuntime runtime, String indexPath,
      String repositoryBaseUrl, long groupId, RegistrationBudget budget) {
    budget.next();
    JsonNode resources = resources(proxy, mapper, runtime);
    String prefix = registrationPrefix(indexPath);
    String endpoint = resourceUrl(resources, "RegistrationsBaseUrl",
        REGISTRATION.equals(prefix) ? LEGACY_REGISTRATION_VERSIONS : REGISTRATION_VERSIONS);
    String flat = resourceUrl(resources, "PackageBaseAddress", List.of("/3.0.0"));
    JsonNode index = registrationDocument(proxy, mapper, runtime, appendPath(endpoint, indexPath.substring(prefix.length())), endpoint, flat, "index");
    budget.consume(index);
    if (!index.path("items").isArray()) throw new MavenExceptions.BadUpstreamException("Invalid NuGet registration index");
    List<JsonNode> leaves = new ArrayList<>();
    Set<String> pages = new HashSet<>();
    String base = repositoryBaseUrl.endsWith("/") ? repositoryBaseUrl : repositoryBaseUrl + "/";
    for (JsonNode page : index.path("items")) {
      JsonNode document = page;
      if (!page.hasNonNull("items")) {
        String url = requireHttpUrl(page.path("@id").asText().split("#", 2)[0]);
        // Apply resource-owned query parameters through the same path as a direct request.
        String local = localLink(url, endpoint, "", prefix, null, runtime);
        if (local.startsWith(prefix)) url = packageUrl(endpoint, local.split("#", 2)[0], prefix, runtime);
        if (!pages.add(url)) continue;
        budget.next();
        document = registrationDocument(proxy, mapper, runtime, url, endpoint, flat, "page");
        budget.consume(document);
      }
      if (!document.path("items").isArray()) throw new MavenExceptions.BadUpstreamException("Invalid NuGet registration page");
      for (JsonNode leaf : document.path("items")) {
        JsonNode rewritten = leaf.deepCopy();
        rewriteLinks(rewritten, endpoint, flat, base, prefix, groupId, runtime);
        budget.consume(rewritten);
        leaves.add(rewritten);
      }
    }
    return leaves;
  }

  private static JsonNode registrationDocument(
      RawProxyService proxy, ObjectMapper mapper, RepositoryRuntime runtime,
      String url, String registration, String flat, String kind) {
    MavenResponse response = proxy.getMetadataFromUrlHidden(runtime,
        cacheKey("registration", url + "\n" + registration + "\n" + flat), url, false,
        "nuget-registration-" + kind + "-v2", body -> {
          ValidatedJson validated = validatedJson(mapper, body, MAX_REGISTRATION_BYTES, "registration");
          JsonNode document = validated.document();
          if (!kind.equals("document")) {
            if (!document.path("items").isArray()) throw new MavenExceptions.BadUpstreamException("Invalid NuGet registration " + kind);
            for (JsonNode item : document.path("items")) {
              if (kind.equals("page")) requireRegistrationLeaf(item);
              else if (item.path("items").isArray()) item.path("items").forEach(NugetUpstreamResources::requireRegistrationLeaf);
              else if (item.hasNonNull("items")) throw new MavenExceptions.BadUpstreamException("Invalid NuGet registration page");
              else requireHttpUrl(item.path("@id").asText().split("#", 2)[0]);
            }
          }
          // Exercise link validation before cache publication too. Only the original bytes
          // are stored; per-request group URLs and routing proofs are generated when served.
          rewriteLinks(document, registration, flat, "", REGISTRATION, null, runtime);
          return new ByteArrayInputStream(validated.bytes());
        });
    return readJson(mapper, response, MAX_REGISTRATION_BYTES, "registration");
  }

  private static void requireRegistrationLeaf(JsonNode leaf) {
    if (!leaf.isObject() || !leaf.path("catalogEntry").isObject()
        || !leaf.path("catalogEntry").path("version").isTextual()
        || leaf.path("catalogEntry").path("version").asText().isBlank()
        || !leaf.path("packageContent").isTextual()) {
      throw new MavenExceptions.BadUpstreamException("Invalid NuGet registration leaf");
    }
  }

  static String registrationPrefix(String path) {
    if (path.startsWith(REGISTRATION)) return REGISTRATION;
    String semver2 = "v3/registration5-semver2/";
    return path.startsWith(semver2) ? semver2 : null;
  }

  private static String packageUrl(String endpoint, String path, String prefix, RepositoryRuntime runtime) {
    int separator = path.indexOf('?');
    if (separator >= 0) {
      List<String> pairs = new ArrayList<>(Arrays.asList(path.substring(separator + 1).split("&", -1)));
      for (int i = pairs.size() - 1; i >= 0; i--) {
        String[] pair = pairs.get(i).split("=", 2);
        if (!pair[0].equals(RESOURCE_QUERY) || pair.length != 2) continue;
        pairs.remove(i);
        String publicPath = NugetPathParser.normalize(path.substring(0, separator))
            + (pairs.isEmpty() ? "" : "?" + String.join("&", pairs));
        String restored = NugetResourceLinkToken.openQuery(runtime.id(),
            NugetResourceLinkToken.identity(runtime.proxyRemoteUrl(), endpoint), publicPath, pair[1]);
        if (restored != null) {
          return appendPath(endpoint.split("\\?", 2)[0], path.substring(prefix.length(), separator)) + "?" + restored;
        }
        break; // An unrecognized same-named parameter is ordinary upstream data.
      }
    }
    return appendPath(endpoint, path.substring(prefix.length()));
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
    String restoredQuery = null;
    // Resource query credentials are applied server-side, never copied into client-facing links.
    if (query != null && source.getRawQuery() != null) {
      String originalQuery = query;
      var resourceKeys = Arrays.stream(source.getRawQuery().split("&", -1))
          .map(NugetUpstreamResources::queryName).collect(Collectors.toSet());
      var linkKeys = Arrays.stream(query.split("&", -1)).map(NugetUpstreamResources::queryName).collect(Collectors.toSet());
      query = Arrays.stream(query.split("&", -1))
          .filter(pair -> !resourceKeys.contains(queryName(pair))).collect(Collectors.joining("&"));
      String missing = Arrays.stream(source.getRawQuery().split("&", -1))
          .filter(pair -> !linkKeys.contains(queryName(pair))).collect(Collectors.joining("&"));
      String desired = (missing.isEmpty() ? "" : missing + "&") + originalQuery;
      String reconstructed = source.getRawQuery() + (query.isEmpty() ? "" : "&" + query);
      if (!desired.equals(reconstructed)) restoredQuery = desired;
    }
    String target = prefix + suffix + (query == null || query.isEmpty() ? "" : "?" + query);
    if (restoredQuery != null) {
      String publicPath = NugetPathParser.normalize(prefix + suffix) + (query.isEmpty() ? "" : "?" + query);
      target += (query.isEmpty() ? "?" : "&") + RESOURCE_QUERY + "="
          + NugetResourceLinkToken.sealQuery(runtime.id(), NugetResourceLinkToken.identity(runtime.proxyRemoteUrl(), endpoint),
              publicPath, restoredQuery);
      query = target.substring(target.indexOf('?') + 1);
    }
    boolean hasQuery = query != null && !query.isEmpty();
    if (groupId != null && (hasQuery || prefix.equals(FLAT))) {
      // Package links retain their selected member even without a query. A different
      // member may serve bytes despite having no usable registration for that version.
      String requestPath = NugetPathParser.normalize(prefix + suffix) + (hasQuery ? "?" + query : "");
      target += (hasQuery ? "&" : "?") + SOURCE_MEMBER_QUERY + "=" + NugetResourceLinkToken.issue(groupId, runtime.id(), requestPath,
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

  private record ValidatedJson(JsonNode document, byte[] bytes) {}

  private static ValidatedJson validatedJson(ObjectMapper mapper, InputStream body, int limit, String kind) {
    try (body) {
      if (body == null) throw new MavenExceptions.BadUpstreamException("Invalid NuGet " + kind);
      byte[] bytes = body.readNBytes(limit + 1);
      if (bytes.length > limit) throw new MavenExceptions.BadUpstreamException("NuGet " + kind + " is too large");
      return new ValidatedJson(readJson(mapper, MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
          "application/json", null, null), limit, kind), bytes);
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid NuGet " + kind);
    }
  }

  private static InputStream validateServiceIndex(ObjectMapper mapper, InputStream body, boolean rootFallback) {
    try {
      ValidatedJson validated = validatedJson(mapper, body, MAX_INDEX_BYTES, "service index");
      if (!validated.document().path("resources").isArray()) throw new InvalidServiceIndex();
      return new ByteArrayInputStream(validated.bytes());
    } catch (MavenExceptions.BadUpstreamException invalid) {
      // A repository root may legitimately serve HTML. This is a discovery miss,
      // not a broken resource: do not auto-block before trying its index.json.
      if (rootFallback) throw new MavenExceptions.MavenNotFoundException("No service index at repository root");
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
    if (rootFallback) {
      String fallback = repositoryRootIndexUrl(indexUrl);
      if (proxy.hasValidatedMetadataFromUrlHidden(runtime, cacheKey("index", fallback), fallback, "nuget-service-index-v1")) {
        return resources(proxy, mapper, runtime, fallback, false);
      }
    }
    MavenResponse response;
    try {
      response = proxy.getMetadataFromUrlHidden(runtime, cacheKey("index", indexUrl), indexUrl, false,
          "nuget-service-index-v1", body -> validateServiceIndex(mapper, body, rootFallback));
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
