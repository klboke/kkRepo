package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** Enable and verify the option on candidate fixtures before the existing Nexus contract probes. */
final class ProxyStreamingCompatibility {
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final ObjectMapper JSON = new ObjectMapper();

  static void enable(HttpRequest.Builder candidateArtifact) throws Exception {
    HttpRequest request = candidateArtifact.copy().GET().build();
    String uri = request.uri().toString();
    int repository = uri.indexOf("/repository/");
    if (repository < 0) throw new IllegalArgumentException("Expected candidate repository URL");
    String name = uri.substring(repository + "/repository/".length()).split("/", 2)[0];
    enable(candidateArtifact, uri.substring(0, repository), name, new HashSet<>());
  }

  private static void enable(HttpRequest.Builder template, String base, String name, Set<String> visited)
      throws Exception {
    if (!visited.add(name)) return;
    var admin = template.copy().uri(URI.create(base + "/internal/repositories/" + name))
        .timeout(Duration.ofSeconds(30));
    var config = read(admin);
    if ("group".equalsIgnoreCase(config.path("type").asText())) {
      for (JsonNode member : config.path("group").path("memberNames")) {
        enable(template, base, member.asText(), visited);
      }
    } else if ("proxy".equalsIgnoreCase(config.path("type").asText())
        && !config.path("proxy").path("streamingEnabled").asBoolean()) {
      var updated = HTTP.send(admin.copy().setHeader("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString("{\"proxy\":{\"streamingEnabled\":true}}"))
          .build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, updated.statusCode(), "enable streaming on " + name + ": " + updated.body());
      assertTrue(read(admin).path("proxy").path("streamingEnabled").asBoolean(), name);
    }
  }

  private static JsonNode read(HttpRequest.Builder admin) throws Exception {
    var response = HTTP.send(admin.copy().GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }
}
