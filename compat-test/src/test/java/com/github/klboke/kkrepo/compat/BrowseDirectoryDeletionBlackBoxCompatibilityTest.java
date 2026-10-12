package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Follows the actual Nexus Browse folder action, not a guessed repository DELETE protocol. */
class BrowseDirectoryDeletionBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

  @Test void confirmedHostedFolderDeletionRetainsSiblingDirectories() throws Exception {
    String nexus = System.getenv("NEXUS_COMPAT_BASE_URL");
    String candidate = System.getenv("KKREPO_COMPAT_BASE_URL");
    assumeTrue(nexus != null && candidate != null
        && Boolean.parseBoolean(System.getenv("COMPAT_WRITE_ENABLED")),
        "Set writable Nexus/kkRepo compatibility endpoints");
    String repository = "compat-directory-" + UUID.randomUUID().toString().substring(0, 8);
    Endpoint reference = new Endpoint(nexus, env("NEXUS_COMPAT_USERNAME", "admin"), env("NEXUS_COMPAT_PASSWORD", "Admin1234"));
    Endpoint actual = new Endpoint(candidate, env("KKREPO_COMPAT_USERNAME", "admin"), env("KKREPO_COMPAT_PASSWORD", "12345678"));
    try {
      assertSuccess(reference.request("POST", "/service/rest/v1/repositories/raw/hosted", JSON.writeValueAsString(Map.of(
          "name", repository, "online", true, "storage", Map.of("blobStoreName", "default", "strictContentTypeValidation", false, "writePolicy", "ALLOW")))));
      assertSuccess(actual.request("POST", "/internal/repositories", JSON.writeValueAsString(Map.of(
          "name", repository, "recipe", "raw-hosted", "online", true, "blobStoreName", "default", "strictContentTypeValidation", false,
          "hosted", Map.of("writePolicy", "ALLOW")))));
      for (Endpoint endpoint : List.of(reference, actual)) {
        for (String path : List.of("releases/a.txt", "releases/nested/b.txt", "releases-old/keep.txt", "other/keep.txt")) {
          assertEquals(201, endpoint.request("PUT", "/repository/" + repository + "/" + path, "bytes:" + path).statusCode());
        }
      }
      String route = "/internal/browse/" + repository + "/directory-deletion";
      var previewResponse = actual.request("GET", route + "?path=releases", null);
      assertEquals(200, previewResponse.statusCode(), previewResponse.body());
      JsonNode preview = JSON.readTree(previewResponse.body());
      assertEquals(2, preview.path("assetCount").asInt());
      String token = preview.path("selectionToken").asText();
      var rejected = actual.request("POST", route, JSON.writeValueAsString(Map.of(
          "path", "releases", "confirmationPath", "wrong", "selectionToken", token)));
      assertEquals(400, rejected.statusCode());
      assertEquals(200, actual.request("GET", "/repository/" + repository + "/releases/a.txt", null).statusCode());
      var nexusDelete = reference.request("POST", "/service/extdirect", JSON.writeValueAsString(Map.of(
          "action", "coreui_Component", "method", "deleteFolder", "data", List.of("releases", repository), "type", "rpc", "tid", 1)));
      assertEquals(200, nexusDelete.statusCode(), nexusDelete.body());
      JsonNode rpc = JSON.readTree(nexusDelete.body());
      if (rpc.isArray()) rpc = rpc.get(0);
      assertTrue(rpc.path("result").path("success").asBoolean(), nexusDelete.body());
      assertEquals(200, actual.request("POST", route, JSON.writeValueAsString(Map.of(
          "path", "releases", "confirmationPath", "releases", "selectionToken", token))).statusCode());
      for (Endpoint endpoint : List.of(reference, actual)) {
        for (String path : List.of("releases/a.txt", "releases/nested/b.txt")) {
          awaitMissing(endpoint, "/repository/" + repository + "/" + path);
        }
        for (String path : List.of("releases-old/keep.txt", "other/keep.txt")) {
          var response = endpoint.request("GET", "/repository/" + repository + "/" + path, null);
          assertEquals(200, response.statusCode());
          assertEquals("bytes:" + path, response.body());
        }
      }
    } finally {
      reference.request("DELETE", "/service/rest/v1/repositories/" + repository, null);
      actual.request("DELETE", "/internal/repositories/" + repository, null);
    }
  }

  private static void awaitMissing(Endpoint endpoint, String path) throws Exception {
    for (int i = 0; i < 60; i++) {
      var response = endpoint.request("GET", path, null);
      if (response.statusCode() == 404) return;
      Thread.sleep(500);
    }
    fail("Deleted folder remains downloadable: " + path);
  }

  private static String env(String key, String fallback) {
    String value = System.getenv(key); return value == null || value.isBlank() ? fallback : value;
  }

  private static void assertSuccess(HttpResponse<String> response) {
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.statusCode() + ": " + response.body());
  }

  private record Endpoint(String base, String username, String password) {
    HttpResponse<String> request(String method, String path, String body) throws Exception {
      var request = HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + path))
          .timeout(Duration.ofSeconds(30))
          .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)))
          .header("Content-Type", "PUT".equals(method) ? "application/octet-stream" : "application/json")
          .header("Accept", "application/json")
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
      return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
