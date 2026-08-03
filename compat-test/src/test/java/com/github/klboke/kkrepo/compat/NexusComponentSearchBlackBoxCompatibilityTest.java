package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Nexus component-search contracts used by the company package lookup client. */
class NexusComponentSearchBlackBoxCompatibilityTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .build();

  @Test
  void missingMavenKeywordReturnsTheSameEmptyPageShape() throws Exception {
    Endpoint reference = Endpoint.reference();
    Endpoint candidate = Endpoint.candidate();
    String keyword = "zzzzkkrepo" + UUID.randomUUID().toString().replace("-", "");
    String path = "/service/rest/v1/search?q=" + query(keyword) + "&format=maven2";

    Exchange referenceResponse = send(reference.request(path));
    Exchange candidateResponse = send(candidate.request(path));

    assertEquals(referenceResponse.status(), candidateResponse.status());
    assertEquals(200, candidateResponse.status());
    assertEmptyPage(referenceResponse.body());
    assertEmptyPage(candidateResponse.body());
  }

  @Test
  void configuredMavenKeywordReturnsNexusComponentAndAssetShapes() throws Exception {
    String keyword = CompatDefaults.setting(
        "compat.componentSearch.query", "COMPAT_COMPONENT_SEARCH_QUERY").orElse(null);
    assumeTrue(keyword != null, "Set COMPAT_COMPONENT_SEARCH_QUERY to compare a migrated Maven component");
    String path = "/service/rest/v1/search?q=" + query(keyword) + "&format=maven2";

    JsonNode reference = json(send(Endpoint.reference().request(path)).body());
    JsonNode candidate = json(send(Endpoint.candidate().request(path)).body());

    assertPageShape(reference);
    assertPageShape(candidate);
    assertFalse(reference.path("items").isEmpty(), "Nexus fixture query must return a component");
    assertFalse(candidate.path("items").isEmpty(), "kkRepo fixture query must return a component");
    assertComponentShape(reference.path("items").get(0));
    assertComponentShape(candidate.path("items").get(0));
  }

  @Test
  void invalidContinuationAndSha1ParameterMatchNexus() throws Exception {
    Endpoint reference = Endpoint.reference();
    Endpoint candidate = Endpoint.candidate();

    String invalidTokenPath =
        "/service/rest/v1/search?q=idc-component&format=maven2"
            + "&continuationToken=invalid-token";
    Exchange referenceInvalid = send(reference.request(invalidTokenPath));
    Exchange candidateInvalid = send(candidate.request(invalidTokenPath));
    assertEquals(referenceInvalid.status(), candidateInvalid.status(),
        "invalid continuation status");
    assertEquals(500, candidateInvalid.status());
    assertTrue(referenceInvalid.contentType().startsWith("text/plain"));
    assertTrue(candidateInvalid.contentType().startsWith("text/plain"));

    String sha1Path = "/service/rest/v1/search?sha1=abc";
    Exchange referenceSha1 = send(reference.request(sha1Path));
    Exchange candidateSha1 = send(candidate.request(sha1Path));
    assertEquals(referenceSha1.status(), candidateSha1.status(), "short SHA-1 status");
    assertEquals(200, candidateSha1.status());
    assertEmptyPage(referenceSha1.body());
    assertEmptyPage(candidateSha1.body());
  }

  private static void assertEmptyPage(byte[] body) throws Exception {
    JsonNode page = json(body);
    assertPageShape(page);
    assertTrue(page.path("items").isEmpty());
    assertTrue(page.path("continuationToken").isNull());
  }

  private static void assertPageShape(JsonNode page) {
    assertTrue(page.path("items").isArray());
    assertTrue(page.has("continuationToken"));
  }

  private static void assertComponentShape(JsonNode component) {
    assertFalse(component.path("id").asText().isBlank());
    assertFalse(component.path("repository").asText().isBlank());
    assertEquals("maven2", component.path("format").asText());
    assertFalse(component.path("name").asText().isBlank());
    assertTrue(component.path("assets").isArray());
    assertFalse(component.path("assets").isEmpty());
    JsonNode asset = component.path("assets").get(0);
    assertFalse(asset.path("id").asText().isBlank());
    assertFalse(asset.path("path").asText().isBlank());
    assertTrue(asset.path("checksum").isObject());
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        request.timeout(Duration.ofSeconds(60)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(
        response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
        response.body());
  }

  private static JsonNode json(byte[] body) throws Exception {
    return MAPPER.readTree(body);
  }

  private static String query(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private record Endpoint(String baseUrl, String authorization) {
    static Endpoint reference() {
      return new Endpoint(
          CompatDefaults.nexusBaseUrl().orElseThrow(),
          basic(CompatDefaults.nexusUsername().orElseThrow(),
              CompatDefaults.nexusPassword().orElseThrow()));
    }

    static Endpoint candidate() {
      return new Endpoint(
          CompatDefaults.nexusPlusBaseUrl().orElseThrow(),
          basic(CompatDefaults.nexusPlusUsername().orElseThrow(),
              CompatDefaults.nexusPlusPassword().orElseThrow()));
    }

    HttpRequest.Builder request(String path) {
      return HttpRequest.newBuilder(URI.create(baseUrl + path))
          .header("Authorization", authorization)
          .header("Accept", "application/json");
    }

    private static String basic(String username, String password) {
      return "Basic " + Base64.getEncoder().encodeToString(
          (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
  }

  private record Exchange(int status, String contentType, byte[] body) {
  }
}
