package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Live reference contract, isolated from shared repositories and compact tasks. */
@EnabledIfSystemProperty(named = "compat.storageUsage.enabled", matches = "true")
class BlobStoreUsageReferenceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Test
  void nexusFileInventoryIncludesSoftDeletedBlobs() throws Exception {
    String name = "usage-compat-" + UUID.randomUUID();
    boolean storeCreated = false;
    boolean repositoryCreated = false;
    try {
      send("POST", "/service/rest/v1/blobstores/file",
          "{\"name\":\"" + name + "\",\"path\":\"" + name + "\"}", "application/json");
      storeCreated = true;
      send("POST", "/service/rest/v1/repositories/raw/hosted", """
          {"name":"%s","online":true,"storage":{"blobStoreName":"%s",
          "strictContentTypeValidation":false,"writePolicy":"ALLOW"}}
          """.formatted(name, name), "application/json");
      repositoryCreated = true;
      send("PUT", "/repository/" + name + "/one.txt", "one", "text/plain");
      send("PUT", "/repository/" + name + "/two.txt", "two", "text/plain");
      awaitBlobCount(name, 2);
      assertEquals(6, inventory(name).path("totalSizeInBytes").asLong());
      JsonNode assets = send("GET", "/service/rest/v1/assets?repository=" + name, null, null);
      assertEquals(2, assets.path("items").size());
      send("DELETE", "/service/rest/v1/assets/" + assets.path("items").get(0).path("id").asText(), null, null);
      assertEquals(1, send("GET", "/service/rest/v1/assets?repository=" + name, null, null).path("items").size());
      assertEquals(2, inventory(name).path("blobCount").asLong());
      assertEquals(6, inventory(name).path("totalSizeInBytes").asLong());
    } finally {
      try {
        if (repositoryCreated) send("DELETE", "/service/rest/v1/repositories/" + name, null, null);
      } finally {
        if (storeCreated) send("DELETE", "/service/rest/v1/blobstores/" + name, null, null);
      }
    }
  }

  private void awaitBlobCount(String name, long expected) throws Exception {
    // Nexus flushes blob-store metrics asynchronously; a successful upload need not
    // be visible to the metrics endpoint in the same millisecond.
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    long actual;
    do {
      actual = inventory(name).path("blobCount").asLong();
      if (actual == expected) return;
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);
    assertEquals(expected, actual, "Nexus blob metrics did not converge");
  }

  private JsonNode inventory(String name) throws Exception {
    for (JsonNode store : send("GET", "/service/rest/v1/blobstores", null, null)) {
      if (name.equals(store.path("name").asText())) return store;
    }
    throw new AssertionError("Missing test blob store " + name);
  }

  private JsonNode send(String method, String path, String body, String contentType) throws Exception {
    String credentials = CompatDefaults.nexusUsername().orElseThrow() + ":"
        + CompatDefaults.nexusPassword().orElseThrow();
    var request = HttpRequest.newBuilder(URI.create(CompatDefaults.nexusBaseUrl().orElseThrow() + path))
        .timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    if (contentType != null) request.header("Content-Type", contentType);
    var response = HTTP.send(request.method(method, body == null
        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
        () -> method + " " + path + ": " + response.statusCode() + " " + response.body());
    return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
  }
}
