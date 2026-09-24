package com.github.klboke.kkrepo.server.nuget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
      String path, String repositoryBaseUrl, boolean groupRequest, boolean headOnly) {
    JsonNode resources = resources(proxy, mapper, runtime);
    if (path.startsWith(FLAT)) {
      String endpoint = resourceUrl(resources, "PackageBaseAddress", List.of("/3.0.0"));
      String remoteUrl = appendPath(endpoint, path.substring(FLAT.length()));
      int query = path.indexOf('?');
      String assetPath = query < 0 ? path : path.substring(0, query);
      if (assetPath.endsWith("/index.json")) {
        return proxy.getMetadataFromUrlHidden(runtime, cacheKey("versions", remoteUrl), remoteUrl, headOnly);
      }
      // Preserve canonical asset paths, Browse visibility and download-policy checks for package bodies.
      String cacheSourceUrl = appendPath(endpoint, assetPath.substring(FLAT.length()));
      return proxy.getAssetFromUrl(runtime, assetPath, remoteUrl, cacheSourceUrl, headOnly);
    }
    String endpoint = resourceUrl(resources, "RegistrationsBaseUrl", REGISTRATION_VERSIONS);
    String flatEndpoint = resourceUrl(resources, "PackageBaseAddress", List.of("/3.0.0"));
    String remoteUrl = appendPath(endpoint, path.substring(registrationPrefix(path).length()));
    // Registration bodies contain package links. Refresh them when either resource moves,
    // even when discovery expires before an otherwise fresh registration cache entry.
    MavenResponse response = proxy.getMetadataFromUrlHidden(
        runtime, cacheKey("registration", remoteUrl + "\n" + flatEndpoint), remoteUrl, false);
    JsonNode document = readJson(mapper, response, MAX_REGISTRATION_BYTES, "registration");
    String base = repositoryBaseUrl.endsWith("/") ? repositoryBaseUrl : repositoryBaseUrl + "/";
    rewriteLinks(document, endpoint, flatEndpoint, base, groupRequest ? runtime.id() : null);
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
    StringBuilder encoded = new StringBuilder();
    for (String segment : suffix.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        throw new MavenExceptions.MavenNotFoundException("Invalid NuGet resource path");
      }
      if (!encoded.isEmpty()) encoded.append('/');
      encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
    }
    int query = endpoint.indexOf('?');
    String base = query < 0 ? endpoint : endpoint.substring(0, query);
    return base + (base.endsWith("/") ? "" : "/") + encoded
        + (uri.getRawQuery() == null ? (extraQuery.isEmpty() ? "" : "?" + extraQuery)
            : "?" + uri.getRawQuery() + (extraQuery.isEmpty() ? "" : "&" + extraQuery));
  }

  private static void rewriteLinks(JsonNode node, String registration, String flat, String base, Long sourceMember) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      for (String field : List.of("@id", "parent", "registration", "packageContent")) {
        JsonNode value = object.get(field);
        if (value != null && value.isTextual()) {
          String rewritten = localLink(value.asText(), registration, base + REGISTRATION, sourceMember);
          rewritten = localLink(rewritten, flat, base + FLAT, sourceMember);
          object.put(field, rewritten);
        }
      }
    }
    if (node.isContainerNode()) {
      for (JsonNode child : node) rewriteLinks(child, registration, flat, base, sourceMember);
    }
  }

  private static String localLink(String value, String endpoint, String localBase, Long sourceMember) {
    URI source = URI.create(endpoint);
    int query = endpoint.indexOf('?');
    String root = query < 0 ? endpoint : endpoint.substring(0, query);
    if (!root.endsWith("/")) root += "/";
    if (!value.startsWith(root)) return value;
    String suffix = value.substring(root.length());
    // Resource query credentials are applied server-side, never copied into client-facing links.
    int suffixQuery = suffix.indexOf('?');
    if (suffixQuery >= 0 && source.getRawQuery() != null) {
      var resourceKeys = Arrays.stream(source.getRawQuery().split("&"))
          .map(pair -> pair.split("=", 2)[0]).collect(Collectors.toSet());
      int fragmentIndex = suffix.indexOf('#', suffixQuery);
      String fragment = fragmentIndex < 0 ? "" : suffix.substring(fragmentIndex);
      String linkQuery = suffix.substring(suffixQuery + 1, fragmentIndex < 0 ? suffix.length() : fragmentIndex);
      String remaining = Arrays.stream(linkQuery.split("&"))
          .filter(pair -> !resourceKeys.contains(pair.split("=", 2)[0])).collect(Collectors.joining("&"));
      suffix = suffix.substring(0, suffixQuery) + (remaining.isEmpty() ? "" : "?" + remaining) + fragment;
    }
    // Query-bearing links can carry feed-specific credentials. Keep the group URL for
    // authorization, but route them only to their originating member on every replica.
    // Ordinary links retain normal group lookup/merge behavior.
    int fragmentIndex = suffix.indexOf('#');
    String fragment = fragmentIndex < 0 ? "" : suffix.substring(fragmentIndex);
    String target = fragmentIndex < 0 ? suffix : suffix.substring(0, fragmentIndex);
    if (sourceMember != null && target.contains("?")) {
      target += "&" + SOURCE_MEMBER_QUERY + "=" + sourceMember;
    }
    return localBase + target + fragment;
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

  private static JsonNode resources(RawProxyService proxy, ObjectMapper mapper, RepositoryRuntime runtime) {
    String indexUrl = serviceIndexUrl(runtime.proxyRemoteUrl());
    // Discovery and protocol metadata use the existing durable cache (DB + blob store).
    // Each replica can rebuild discovery, metadata TTL controls refresh, and URL-derived keys
    // isolate repository reconfiguration and obsolete negative entries from guessed /query URLs.
    MavenResponse response = proxy.getMetadataFromUrlHidden(
        runtime, cacheKey("index", indexUrl), indexUrl, false);
    JsonNode index = readJson(mapper, response, MAX_INDEX_BYTES, "service index");
    if (!index.path("resources").isArray()) {
      throw new MavenExceptions.BadUpstreamException("NuGet service index has no resources array");
    }
    return index.path("resources");
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

  private static String serviceIndexUrl(String remoteUrl) {
    String url = requireHttpUrl(remoteUrl == null ? "" : remoteUrl.trim());
    URI uri = URI.create(url);
    String path = uri.getPath();
    if (path != null && path.endsWith(".json")) return url;
    // Preserve Nexus-style repository-root configuration as well as explicit index URLs.
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
