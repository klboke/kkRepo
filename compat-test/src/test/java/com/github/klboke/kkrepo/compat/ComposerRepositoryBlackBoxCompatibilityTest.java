package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComposerRepositoryBlackBoxCompatibilityTest {
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  @Test
  void proxyV2MetadataMatchesNexusWhenConfigured() throws Exception {
    assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("COMPOSER_COMPAT_ENABLED", "false")),
        "Set COMPOSER_COMPAT_ENABLED=true to run Composer proxy compatibility");
    String nexus = env("COMPOSER_NEXUS_PROXY_URL", "http://127.0.0.1:28090/repository/composer-proxy");
    String candidate = env("COMPOSER_KKREPO_PROXY_URL", "http://127.0.0.1:18090/repository/composer-proxy");
    String packageName = env("COMPOSER_COMPAT_PACKAGE", "monolog/monolog");
    Endpoint nexusEndpoint = new Endpoint(
        nexus,
        env("NEXUS_COMPAT_USERNAME", "admin"),
        env("NEXUS_COMPAT_PASSWORD", "Admin1234"));
    Endpoint candidateEndpoint = new Endpoint(
        candidate,
        env("KKREPO_COMPAT_USERNAME", "admin"),
        env("KKREPO_COMPAT_PASSWORD", "12345678"));

    Exchange nexusRoot = get(nexusEndpoint, "packages.json");
    assertEquals(200, nexusRoot.status(),
        "Nexus reference must expose Composer proxy support when Composer compatibility is enabled");
    Exchange candidateRoot = get(candidateEndpoint, "packages.json");
    assertEquals(nexusRoot.status(), candidateRoot.status(), "packages.json status");
    assertEquals(200, candidateRoot.status());
    assertJson(nexusRoot, "Nexus packages.json");
    assertJson(candidateRoot, "kkrepo packages.json");
    assertTrue(json(candidateRoot).get("metadata-url").toString().contains("/p2/%package%.json"));

    Exchange nexusPackage = get(nexusEndpoint, "p2/" + packageName + ".json");
    Exchange candidatePackage = get(candidateEndpoint, "p2/" + packageName + ".json");
    assertEquals(nexusPackage.status(), candidatePackage.status(), "p2 package status");
    assertEquals(200, candidatePackage.status());
    assertJson(nexusPackage, "Nexus p2 metadata");
    assertJson(candidatePackage, "kkrepo p2 metadata");
    List<?> nexusVersions = versions(json(nexusPackage), packageName);
    List<?> versions = versions(json(candidatePackage), packageName);
    assertFalse(versions.isEmpty(), "candidate should expose Composer versions");
    Map<?, ?> candidateVersion = firstDistVersion(versions);
    Map<?, ?> dist = (Map<?, ?>) candidateVersion.get("dist");
    String candidateDistUrl = dist.get("url").toString();
    String candidateDistPath = URI.create(candidateDistUrl).getPath();
    String candidatePrefix = URI.create(candidate).getPath() + "/";
    assertTrue(candidateDistPath.startsWith(candidatePrefix), "dist URL must remain behind kkrepo");
    String relativeDistPath = candidateDistPath.substring(candidatePrefix.length());
    String[] packageParts = packageName.split("/", -1);
    String version = candidateVersion.get("version").toString();
    assertEquals(
        packageName + "/" + version + "/" + packageParts[0] + "-" + packageParts[1] + "-" + version
            + "." + dist.get("type"),
        relativeDistPath,
        "Nexus-compatible dist path");
    Map<?, ?> nexusVersion = version(nexusVersions, version);
    Map<?, ?> nexusDist = (Map<?, ?>) nexusVersion.get("dist");
    String nexusDistPath = URI.create(nexusDist.get("url").toString()).getPath();
    String nexusPrefix = URI.create(nexus).getPath() + "/";
    assertEquals(nexusDistPath.substring(nexusPrefix.length()), relativeDistPath,
        "Nexus and kkrepo dist paths");

    Exchange nexusConditional = conditionalGet(nexusEndpoint, "p2/" + packageName + ".json", nexusPackage);
    Exchange candidateConditional = conditionalGet(
        candidateEndpoint, "p2/" + packageName + ".json", candidatePackage);
    assertEquals(nexusConditional.status(), candidateConditional.status(), "conditional p2 status");
    assertEquals(304, candidateConditional.status(), "conditional p2 should be not modified");
    assertEquals(0, candidateConditional.body().length, "304 response body");

    Exchange candidateDist = getAbsolute(candidateEndpoint, candidateDistUrl);
    assertEquals(200, candidateDist.status(), "candidate dist status");
    assertTrue(candidateDist.body().length > 0, "candidate dist body");

    String missing = "kkrepo-compat/not-found-" + System.nanoTime();
    assertEquals(404, get(nexusEndpoint, "p2/" + missing + ".json").status());
    assertEquals(404, get(candidateEndpoint, "p2/" + missing + ".json").status());
  }

  private static Map<?, ?> firstDistVersion(List<?> versions) {
    for (Object version : versions) {
      if (version instanceof Map<?, ?> map && map.get("dist") instanceof Map<?, ?> dist
          && dist.get("url") != null) {
        return map;
      }
    }
    throw new AssertionError("Composer metadata has no dist version");
  }

  private static Map<?, ?> version(List<?> versions, String expectedVersion) {
    for (Object item : versions) {
      if (item instanceof Map<?, ?> map && expectedVersion.equals(String.valueOf(map.get("version")))) {
        return map;
      }
    }
    throw new AssertionError("Nexus metadata lacks candidate version " + expectedVersion);
  }

  @SuppressWarnings("unchecked")
  private static List<?> versions(Map<String, Object> metadata, String packageName) {
    return (List<?>) ((Map<String, Object>) metadata.get("packages")).get(packageName);
  }

  private static Map<String, Object> json(Exchange exchange) throws Exception {
    return JSON.readValue(exchange.body(), MAP);
  }

  private static Exchange get(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(endpoint.baseUrl() + "/" + path));
  }

  private static Exchange getAbsolute(Endpoint endpoint, String url) throws Exception {
    return send(endpoint.request(url));
  }

  private static Exchange conditionalGet(Endpoint endpoint, String path, Exchange initial) throws Exception {
    HttpRequest.Builder request = endpoint.request(endpoint.baseUrl() + "/" + path);
    String lastModified = initial.header("last-modified");
    String etag = initial.header("etag");
    if (!lastModified.isBlank()) request.header("If-Modified-Since", lastModified);
    else if (!etag.isBlank()) request.header("If-None-Match", etag);
    else throw new AssertionError("Composer metadata has no Last-Modified or ETag validator");
    return send(request);
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        request.timeout(Duration.ofSeconds(60)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
    Map<String, String> headers = new LinkedHashMap<>();
    response.headers().map().forEach((name, values) -> {
      if (!values.isEmpty()) headers.put(name.toLowerCase(), values.getFirst());
    });
    return new Exchange(response.statusCode(), response.body(), headers);
  }

  private static void assertJson(Exchange exchange, String label) {
    assertTrue(exchange.header("content-type").toLowerCase().contains("json"),
        label + " Content-Type=" + exchange.header("content-type"));
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.replaceAll("/+$", "");
  }

  private record Endpoint(String baseUrl, String username, String password) {
    HttpRequest.Builder request(String url) {
      String credentials = username + ":" + password;
      String authorization = "Basic " + Base64.getEncoder().encodeToString(
          credentials.getBytes(StandardCharsets.UTF_8));
      return HttpRequest.newBuilder(URI.create(url)).header("Authorization", authorization);
    }
  }

  private record Exchange(int status, byte[] body, Map<String, String> headers) {
    String header(String name) {
      return headers.getOrDefault(name.toLowerCase(), "");
    }
  }
}
