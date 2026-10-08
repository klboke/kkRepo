package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Isolated write contract for Nexus blob-store deletion. */
@EnabledIfEnvironmentVariable(named = "COMPAT_WRITE_ENABLED", matches = "true")
class BlobStoreDeletionReferenceTest {
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Test
  void nexusDeletesUnusedStoreButRejectsRepositoryReference() throws Exception {
    String name = "delete-compat-" + UUID.randomUUID();
    boolean storeCreated = false;
    boolean repositoryCreated = false;
    try {
      assertEmptyResponse(204, send("POST", "/service/rest/v1/blobstores/file",
          "{\"name\":\"" + name + "\",\"path\":\"" + name + "\"}"));
      storeCreated = true;
      assertEmptyResponse(201, send("POST", "/service/rest/v1/repositories/raw/hosted", """
          {"name":"%s","online":true,"storage":{"blobStoreName":"%s",
          "strictContentTypeValidation":false,"writePolicy":"ALLOW"}}
          """.formatted(name, name)));
      repositoryCreated = true;

      HttpResponse<String> inUse = send("DELETE", "/service/rest/v1/blobstores/" + name, null);
      assertEquals(400, inUse.statusCode(), inUse::body);
      assertEquals("application/json", inUse.headers().firstValue("Content-Type")
          .orElseThrow().split(";", 2)[0].trim());
      var error = new ObjectMapper().readTree(inUse.body());
      assertEquals("*", error.path("id").asText());
      assertEquals("\"BlobStore " + name + " is in use and cannot be deleted\"",
          error.path("message").asText());
      assertEquals(200, send("GET", "/service/rest/v1/blobstores/file/" + name, null).statusCode());

      assertEmptyResponse(204, send("DELETE", "/service/rest/v1/repositories/" + name, null));
      repositoryCreated = false;
      assertEmptyResponse(204, send("DELETE", "/service/rest/v1/blobstores/" + name, null));
      storeCreated = false;
      assertEquals(404, send("GET", "/service/rest/v1/blobstores/file/" + name, null).statusCode());
    } finally {
      if (repositoryCreated) send("DELETE", "/service/rest/v1/repositories/" + name, null);
      if (storeCreated) send("DELETE", "/service/rest/v1/blobstores/" + name, null);
    }
  }

  private static void assertEmptyResponse(int status, HttpResponse<String> response) {
    assertEquals(status, response.statusCode(), response::body);
    assertEquals("", response.body());
  }

  private static HttpResponse<String> send(String method, String path, String body) throws Exception {
    String credentials = CompatDefaults.nexusUsername().orElseThrow() + ":"
        + CompatDefaults.nexusPassword().orElseThrow();
    var request = HttpRequest.newBuilder(URI.create(CompatDefaults.nexusBaseUrl().orElseThrow() + path))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Basic " + Base64.getEncoder()
            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    if (body != null) request.header("Content-Type", "application/json");
    return HTTP.send(request.method(method, body == null
        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
