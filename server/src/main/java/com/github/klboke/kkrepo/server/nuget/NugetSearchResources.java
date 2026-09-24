package com.github.klboke.kkrepo.server.nuget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HexFormat;
import java.util.List;

/** NuGet V3 search resources are absolute URLs, not paths relative to the service index. */
final class NugetSearchResources {
  private static final int MAX_INDEX_BYTES = 1024 * 1024;
  private static final List<String> VERSIONS = List.of("/3.5.0", "/3.0.0", "/3.0.0-rc", "/3.0.0-beta", "");

  private NugetSearchResources() {
  }

  static MavenResponse get(
      RawProxyService proxy, ObjectMapper mapper, RepositoryRuntime runtime,
      String path, boolean headOnly) {
    int separator = path.indexOf('?');
    String operation = separator < 0 ? path : path.substring(0, separator);
    String query = separator < 0 ? "" : path.substring(separator + 1);
    String type = "query".equals(operation) ? "SearchQueryService" : "SearchAutocompleteService";
    String indexUrl = serviceIndexUrl(runtime.proxyRemoteUrl());
    // Both discovery and search use the existing durable metadata cache (DB + blob store).
    // Each replica can rebuild discovery, metadata TTL controls refresh, and URL-derived keys
    // isolate repository reconfiguration and obsolete negative entries from guessed /query URLs.
    MavenResponse response = proxy.getMetadataFromUrlHidden(
        runtime, cacheKey("index", indexUrl), indexUrl, false);
    JsonNode index;
    try (InputStream body = response.body()) {
      if (response.status() != 200 || body == null) {
        throw new MavenExceptions.BadUpstreamException("NuGet service index is unavailable");
      }
      byte[] bytes = body.readNBytes(MAX_INDEX_BYTES + 1);
      if (bytes.length > MAX_INDEX_BYTES) {
        throw new MavenExceptions.BadUpstreamException("NuGet service index is too large");
      }
      index = mapper.readTree(bytes);
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Invalid NuGet service index");
    }
    if (index == null || !index.path("resources").isArray()) {
      throw new MavenExceptions.BadUpstreamException("NuGet service index has no resources array");
    }
    String endpoint = resourceUrl(index.path("resources"), type);
    String remoteUrl = endpoint + (query.isEmpty() ? "" :
        (URI.create(endpoint).getRawQuery() == null ? "?" : "&") + query);
    // HttpRemoteFetcher applies outbound validation and sends repository credentials only to
    // the configured upstream origin. Never copy service-index query credentials to another host.
    return proxy.getMetadataFromUrlHidden(
        runtime, cacheKey(operation, remoteUrl), remoteUrl, headOnly);
  }

  private static String resourceUrl(JsonNode resources, String type) {
    for (String version : VERSIONS) {
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
