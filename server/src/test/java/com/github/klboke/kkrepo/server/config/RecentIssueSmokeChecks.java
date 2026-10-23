package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.ProxySettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Exercises the actual servlet/filter, protocol, database and Blob paths for #428/#429/#431/#432. */
final class RecentIssueSmokeChecks {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String DESCRIPTION = "Ansible collection for core Windows plugins.\n";

  static void exercise(ConfigurableApplicationContext context) throws Exception {
    String base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    RepositoryService repositories = context.getBean(RepositoryService.class);
    repositories.create(new CreateCommand("issue-raw", "raw-hosted", true, "smoke-file", true,
        new HostedSettings("ALLOW", null, null), null, null, null, null, null));
    assertEquals("/r/issue-raw/", repositories.get("issue-raw").url());
    assertEquals(201, request(base, "PUT", "/r/issue-raw/file.txt", "payload", true).statusCode());
    assertEquals("payload", request(base, "GET", "/r/issue-raw/file.txt", null, true).body());
    assertEquals(401, request(base, "GET", "/r/issue-raw/file.txt", null, false).statusCode());
    assertEquals(404, request(base, "GET", "/repository/issue-raw/file.txt", null, true).statusCode());
    var detail = request(base, "GET", "/internal/browse/issue-raw/attributes?path=file.txt", null, true);
    assertEquals(200, detail.statusCode(), detail.body());
    var metadata = JSON.readTree(detail.body());
    assertEquals("/r/issue-raw/file.txt", metadata.path("downloadUrl").asText());
    assertEquals("203.0.113.45", metadata.path("uploaderIp").asText());
    var listing = request(base, "GET", "/internal/browse/issue-raw", null, true);
    assertTrue(listing.body().contains("/r/issue-raw/file.txt"), listing.body());

    HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      byte[] body;
      int status = 200;
      if (path.equals("/FreeBSD:14:amd64/quarterly/meta.txz")) {
        body = "freebsd-catalogue".getBytes(StandardCharsets.UTF_8);
      } else if (path.equals("/") || path.equals("/api/")) {
        body = JSON.writeValueAsBytes(Map.of("available_versions", Map.of("v3", "/api/v3/")));
      } else if (path.contains("/versions/1.0.0/")) {
        body = JSON.writeValueAsBytes(Map.of("namespace", Map.of("name", "ansible"),
            "collection", Map.of("name", "windows"), "version", "1.0.0",
            "download_url", "/api/v3/artifacts/ansible-windows-1.0.0.tar.gz",
            "artifact", Map.of("filename", "ansible-windows-1.0.0.tar.gz", "sha256", "a".repeat(64)),
            "metadata", Map.of("description", DESCRIPTION)));
      } else {
        status = 404;
        body = new byte[0];
      }
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, body.length);
      try (var out = exchange.getResponseBody()) { out.write(body); }
    });
    upstream.start();
    try {
      String remote = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/";
      for (String format : new String[] {"raw", "ansiblegalaxy"}) {
        repositories.create(new CreateCommand("issue-" + format + "-proxy", format + "-proxy", true,
            "smoke-file", true, null, new ProxySettings(remote, 1440, 1440, true), null, null, null, null));
      }
      var proxy = request(base, "GET", "/r/issue-raw-proxy/FreeBSD:14:amd64/quarterly/meta.txz", null, true);
      assertEquals(200, proxy.statusCode(), proxy.body());
      assertEquals("freebsd-catalogue", proxy.body());
      var proxyDetail = JSON.readTree(request(base, "GET",
          "/internal/browse/issue-raw-proxy/attributes?path=FreeBSD:14:amd64/quarterly/meta.txz", null, true).body());
      assertEquals("203.0.113.45", proxyDetail.path("uploaderIp").asText());
      var galaxy = request(base, "GET",
          "/r/issue-ansiblegalaxy-proxy/api/v3/collections/ansible/windows/versions/1.0.0/", null, true);
      assertEquals(200, galaxy.statusCode(), galaxy.body());
      var version = JSON.readTree(galaxy.body());
      assertEquals(DESCRIPTION, version.path("metadata").path("description").asText());
      assertTrue(version.path("download_url").asText().contains("/r/issue-ansiblegalaxy-proxy/"), galaxy.body());
    } finally {
      upstream.stop(0);
    }
  }

  private static HttpResponse<String> request(String base, String method, String path,
      String body, boolean authenticated) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
        .header("X-Forwarded-For", "198.51.100.1, 203.0.113.45");
    if (authenticated) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
        "admin:SmokeAdmin1234!".getBytes(StandardCharsets.UTF_8)));
    return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }
}
