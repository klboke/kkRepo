package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

final class DownloadOffloadSmokeChecks {
  private static final String SETTINGS = "/internal/download-offload";
  private static final String ARTIFACT = "/repository/smoke-hosted/com/acme/smoke/1.0/smoke-1.0.jar";
  private static final String AUTHORIZATION = "Basic " + Base64.getEncoder().encodeToString(
      "admin:SmokeAdmin1234!".getBytes(StandardCharsets.UTF_8));

  static void exercise(ConfigurableApplicationContext first, ConfigurableApplicationContext second) throws Exception {
    var json = new ObjectMapper();
    try (var client = HttpClient.newHttpClient()) {
      var settings = send(client, request(first, SETTINGS).GET());
      assertEquals(200, settings.statusCode(), settings.body());
      var initial = json.readTree(settings.body());
      assertFalse(initial.path("enabled").asBoolean());
      var store = initial.path("stores").valueStream()
          .filter(candidate -> "smoke-file".equals(candidate.path("name").asText()))
          .findFirst().orElseThrow();
      long storeId = store.path("id").asLong();
      var update = json.createObjectNode()
          .put("revision", initial.path("revision").asLong())
          .put("enabled", true)
          .put("method", "nginx")
          .put("customHeader", "X-KkRepo-File")
          .put("customTarget", "internal-uri");
      update.putArray("storeIds").add(storeId);
      var save = request(first, SETTINGS)
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(update)));
      var saved = send(client, save);
      assertEquals(200, saved.statusCode(), saved.body());
      assertEquals(409, send(client, save).statusCode());

      var fromSecondReplica = send(client, request(second, SETTINGS).GET());
      assertEquals(200, fromSecondReplica.statusCode(), fromSecondReplica.body());
      assertEquals(json.readTree(saved.body()), json.readTree(fromSecondReplica.body()));
      var handoff = send(client, request(second, ARTIFACT)
          .header("X-KkRepo-File-Offload", "nginx").GET());
      assertEquals(200, handoff.statusCode());
      assertTrue(handoff.headers().firstValue("X-Accel-Redirect").orElseThrow()
          .startsWith("/_kkrepo_file_offload/" + storeId + "/repositories/smoke-hosted/"));
      assertEquals("", handoff.body());
      var ordinary = send(client, request(second, ARTIFACT).GET());
      assertEquals(200, ordinary.statusCode());
      assertTrue(ordinary.headers().firstValue("X-Accel-Redirect").isEmpty());
      assertEquals("dual-database-smoke", ordinary.body());
    }
  }

  private static HttpRequest.Builder request(ConfigurableApplicationContext context, String path) {
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(15))
        .header("Authorization", AUTHORIZATION);
  }

  private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
