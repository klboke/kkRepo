package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
      assertSuccess(send("POST", "/service/rest/v1/blobstores/file",
          "{\"name\":\"" + name + "\",\"path\":\"" + name + "\"}"));
      storeCreated = true;
      assertSuccess(send("POST", "/service/rest/v1/repositories/raw/hosted", """
          {"name":"%s","online":true,"storage":{"blobStoreName":"%s",
          "strictContentTypeValidation":false,"writePolicy":"ALLOW"}}
          """.formatted(name, name)));
      repositoryCreated = true;

      HttpResponse<String> inUse = send("DELETE", "/service/rest/v1/blobstores/" + name, null);
      assertTrue(inUse.statusCode() >= 400 && inUse.statusCode() < 500,
          () -> "Nexus unexpectedly deleted an in-use store: " + inUse.statusCode());

      assertSuccess(send("DELETE", "/service/rest/v1/repositories/" + name, null));
      repositoryCreated = false;
      assertSuccess(send("DELETE", "/service/rest/v1/blobstores/" + name, null));
      storeCreated = false;
      assertEquals(404, send("GET", "/service/rest/v1/blobstores/file/" + name, null).statusCode());
    } finally {
      if (repositoryCreated) send("DELETE", "/service/rest/v1/repositories/" + name, null);
      if (storeCreated) send("DELETE", "/service/rest/v1/blobstores/" + name, null);
    }
  }

  private static void assertSuccess(HttpResponse<String> response) {
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
        () -> response.statusCode() + " " + response.body());
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
