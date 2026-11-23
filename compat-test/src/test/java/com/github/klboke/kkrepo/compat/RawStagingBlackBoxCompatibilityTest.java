package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Black-box staging lifecycle against a running kkrepo instance.
 *
 * <p>Required environment: {@code KKREPO_COMPAT_BASE_URL}, credentials, and
 * {@code KKREPO_COMPAT_STAGING_REPOSITORY} pointing at a hosted Raw repository.
 */
class RawStagingBlackBoxCompatibilityTest {
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void stagePromoteAndReadRawContentWhenConfigured() throws Exception {
    String baseUrl = System.getenv("KKREPO_COMPAT_BASE_URL");
    String repository = System.getenv("KKREPO_COMPAT_STAGING_REPOSITORY");
    assumeTrue(baseUrl != null && !baseUrl.isBlank() && repository != null && !repository.isBlank(),
        "Set KKREPO_COMPAT_BASE_URL and KKREPO_COMPAT_STAGING_REPOSITORY to run staging black-box tests");
    Endpoint endpoint = new Endpoint(
        baseUrl,
        env("KKREPO_COMPAT_USERNAME", "admin"),
        env("KKREPO_COMPAT_PASSWORD", "12345678"));
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String name = "compat-staging-" + suffix;
    String path = "staging-compat/" + suffix + ".txt";
    byte[] body = ("staged-" + suffix).getBytes(StandardCharsets.UTF_8);

    Exchange created = endpoint.send("POST", "/internal/staging/sessions",
        JSON.writeValueAsBytes(Map.of("name", name, "repositories", java.util.List.of(repository))),
        Map.of("Content-Type", "application/json"));
    assertEquals(200, created.status(), created.bodyText());
    String sessionId = JSON.readTree(created.body()).path("session").path("id").asText();
    assertTrue(!sessionId.isBlank());

    try {
      Exchange stagedPut = endpoint.send("PUT", "/repository/" + repository + "/" + path, body,
          Map.of("Content-Type", "text/plain", "X-KkRepo-Staging", sessionId));
      assertEquals(201, stagedPut.status(), stagedPut.bodyText());

      Exchange stagedGet = endpoint.send("GET", "/repository/" + repository + "/" + path,
          null, Map.of("X-KkRepo-Staging", sessionId));
      assertEquals(200, stagedGet.status(), stagedGet.bodyText());
      assertTrue(java.util.Arrays.equals(body, stagedGet.body()));

      Exchange preflight = endpoint.send("POST",
          "/internal/staging/sessions/" + sessionId + "/preflight", null, Map.of());
      assertEquals(200, preflight.status(), preflight.bodyText());
      JsonNode preflightJson = JSON.readTree(preflight.body());
      assertTrue(preflightJson.path("promotable").asBoolean(), preflight.bodyText());

      Exchange promote = endpoint.send("POST",
          "/internal/staging/sessions/" + sessionId + "/promote", null, Map.of());
      assertEquals(200, promote.status(), promote.bodyText());
      JsonNode promoteJson = JSON.readTree(promote.body());
      assertEquals("SUCCEEDED", promoteJson.path("state").asText(), promote.bodyText());

      Exchange liveGet = endpoint.send("GET", "/repository/" + repository + "/" + path,
          null, Map.of());
      assertEquals(200, liveGet.status(), liveGet.bodyText());
      assertTrue(java.util.Arrays.equals(body, liveGet.body()));
    } finally {
      endpoint.send("DELETE", "/repository/" + repository + "/" + path, null, Map.of());
    }
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private record Endpoint(String baseUrl, String username, String password) {
    Exchange send(String method, String path, byte[] body, Map<String, String> headers)
        throws Exception {
      HttpRequest.Builder request = HttpRequest.newBuilder(
              URI.create(baseUrl + path))
          .timeout(Duration.ofSeconds(30))
          .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
              (username + ":" + password).getBytes(StandardCharsets.UTF_8)));
      headers.forEach(request::header);
      if (body == null) {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        request.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
      }
      HttpResponse<byte[]> response = HTTP.send(
          request.build(), HttpResponse.BodyHandlers.ofByteArray());
      return new Exchange(response.statusCode(), response.body());
    }
  }

  private record Exchange(int status, byte[] body) {
    String bodyText() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }
}
