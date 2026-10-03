package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Nexus 3.94 hosted Batch/basic contract. Each run owns its repositories and fixture bytes. */
class GitLfsRepositoryBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String LFS = "application/vnd.git-lfs+json";
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20)).build();

  @Test
  void hostedBatchAndBasicTransfersMatchNexus() throws Exception {
    assumeTrue(CompatDefaults.nexusBaseUrl().isPresent()
        && CompatDefaults.nexusPlusBaseUrl().isPresent(), "Configure both compatibility endpoints");
    String suffix = UUID.randomUUID().toString().substring(0, 12);
    Endpoint nexus = new Endpoint(CompatDefaults.nexusBaseUrl().orElseThrow(),
        CompatDefaults.nexusUsername().orElse("admin"), CompatDefaults.nexusPassword().orElse("admin123"),
        "gitlfs-reference-" + suffix, false);
    Endpoint candidate = new Endpoint(CompatDefaults.nexusPlusBaseUrl().orElseThrow(),
        CompatDefaults.nexusPlusUsername().orElse("admin"), CompatDefaults.nexusPlusPassword().orElse("admin123"),
        "gitlfs-candidate-" + suffix, true);
    byte[] content = ("Git LFS compatibility " + suffix + "\n").getBytes(StandardCharsets.UTF_8);
    String oid = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    try {
      for (Endpoint endpoint : List.of(nexus, candidate)) {
        if (endpoint.candidate()) {
          assertEquals(201, endpoint.admin("POST", "/internal/repositories", Map.of(
              "name", endpoint.repo(), "recipe", "gitlfs-hosted", "online", true,
              "blobStoreName", "default", "strictContentTypeValidation", false,
              "hosted", Map.of("writePolicy", "ALLOW"))).statusCode());
        } else {
          assertEquals(201, endpoint.admin("POST", "/service/rest/v1/repositories/gitlfs/hosted", Map.of(
              "name", endpoint.repo(), "online", true, "storage", Map.of(
                  "blobStoreName", "default", "strictContentTypeValidation", false, "writePolicy", "allow"))).statusCode());
        }
      }
      JsonNode reference = roundTrip(nexus, oid, content);
      JsonNode actual = roundTrip(candidate, oid, content);
      assertEquals(reference, actual, "Repeated-upload Batch semantics");
      for (Endpoint endpoint : List.of(nexus, candidate)) {
        var batch = endpoint.batch("download", List.of(
            Map.of("oid", oid, "size", content.length), Map.of("oid", "0".repeat(64), "size", 1)));
        assertEquals(200, batch.statusCode());
        JsonNode objects = JSON.readTree(batch.body()).path("objects");
        assertTrue(objects.get(0).path("actions").has("download"));
        assertEquals(404, objects.get(1).path("error").path("code").asInt());
        assertFalse(objects.get(1).has("actions"));
        assertEquals(422, endpoint.send("POST", endpoint.root() + "/info/lfs/objects/batch",
            JSON.writeValueAsBytes(Map.of("operation", "upload", "transfers", List.of("tus"),
                "objects", List.of(Map.of("oid", oid, "size", content.length)))), LFS, Map.of()).statusCode());
      }
      // Deliberate integrity hardening: Nexus 3.94 accepts bad bytes then fails verify with 500.
      String wrongOid = "f".repeat(64);
      var upload = JSON.readTree(candidate.batch("upload",
          List.of(Map.of("oid", wrongOid, "size", content.length))).body());
      URI uploadUri = URI.create(upload.path("objects").get(0).path("actions").path("upload").path("href").asText());
      assertEquals(422, candidate.send("PUT", uploadUri.toString(), content,
          "application/octet-stream", actionHeaders(upload.path("objects").get(0).path("actions").path("upload"))).statusCode());
      assertEquals(404, JSON.readTree(candidate.batch("download",
          List.of(Map.of("oid", wrongOid, "size", content.length))).body())
          .path("objects").get(0).path("error").path("code").asInt());
    } finally {
      for (Endpoint endpoint : List.of(candidate, nexus)) {
        endpoint.admin("DELETE", (endpoint.candidate() ? "/internal/repositories/" : "/service/rest/v1/repositories/") + endpoint.repo(), null);
      }
    }
  }

  private JsonNode roundTrip(Endpoint endpoint, String oid, byte[] content) throws Exception {
    var request = List.of(Map.of("oid", oid, "size", content.length));
    var response = endpoint.batch("upload", request);
    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("content-type").orElse("").startsWith(LFS));
    JsonNode batch = JSON.readTree(response.body());
    assertEquals("basic", batch.path("transfer").asText());
    JsonNode actions = batch.path("objects").get(0).path("actions");
    URI upload = URI.create(actions.path("upload").path("href").asText());
    assertEquals("/repository/" + endpoint.repo() + "/" + oid, upload.getPath());
    assertEquals(200, endpoint.send("PUT", upload.toString(), content, "application/octet-stream", actionHeaders(actions.path("upload"))).statusCode());
    URI verify = URI.create(actions.path("verify").path("href").asText());
    var verified = endpoint.send("POST", verify.toString(), JSON.writeValueAsBytes(request.getFirst()), LFS, Map.of());
    assertEquals(200, verified.statusCode());
    assertEquals(oid, JSON.readTree(verified.body()).path("oid").asText());
    assertEquals(content.length, JSON.readTree(verified.body()).path("size").asLong());
    JsonNode downloadBatch = JSON.readTree(endpoint.batch("download", request).body());
    URI download = URI.create(downloadBatch.path("objects").get(0).path("actions").path("download").path("href").asText());
    var downloaded = endpoint.send("GET", download.toString(), null, LFS, Map.of());
    assertEquals(200, downloaded.statusCode());
    assertArrayEquals(content, downloaded.body());
    var range = endpoint.send("GET", download.toString(), null, LFS, Map.of("Range", "bytes=1-4"));
    assertEquals(206, range.statusCode());
    assertEquals("bytes 1-4/" + content.length, range.headers().firstValue("content-range").orElseThrow());
    assertArrayEquals(java.util.Arrays.copyOfRange(content, 1, 5), range.body());
    var repeated = endpoint.batch("upload", request);
    assertEquals(200, repeated.statusCode());
    JsonNode result = JSON.readTree(repeated.body());
    assertFalse(result.path("objects").get(0).has("actions"));
    return result;
  }

  private static Map<String, String> actionHeaders(JsonNode action) {
    Map<String, String> headers = new java.util.LinkedHashMap<>();
    action.path("header").fields().forEachRemaining(e -> {
      if (!"Authorization".equalsIgnoreCase(e.getKey())) headers.put(e.getKey(), e.getValue().asText());
    });
    return headers;
  }

  private record Endpoint(String base, String username, String password, String repo, boolean candidate) {
    String root() { return base.replaceAll("/+$", "") + "/repository/" + repo; }
    HttpResponse<byte[]> batch(String operation, List<?> objects) throws Exception {
      return send("POST", root() + "/info/lfs/objects/batch",
          JSON.writeValueAsBytes(Map.of("operation", operation, "objects", objects)), LFS, Map.of());
    }
    HttpResponse<byte[]> admin(String method, String path, Object body) throws Exception {
      return send(method, base.replaceAll("/+$", "") + path,
          body == null ? null : JSON.writeValueAsBytes(body), "application/json", Map.of());
    }
    HttpResponse<byte[]> send(String method, String url, byte[] body, String type, Map<String, String> headers) throws Exception {
      URI uri = URI.create(url);
      assertEquals(URI.create(base).getAuthority(), uri.getAuthority(), "Actions must stay on the test server");
      var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
          .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
              (username + ":" + password).getBytes(StandardCharsets.UTF_8)))
          .header("Content-Type", type).header("Accept", type)
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
      headers.forEach(builder::header);
      return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
  }
}
