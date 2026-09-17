package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenMetadataXml;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses disposable repositories and a controlled redirect, without public upstream dependencies. */
class MavenGroupFailureCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
  private static final String BASE = "com/github/klboke/compat/fallback/";
  private static final String POM_PATH = BASE + "1.0/fallback-1.0.pom";
  private static final byte[] POM = bytes("""
      <project><modelVersion>4.0.0</modelVersion><groupId>com.github.klboke.compat</groupId>
      <artifactId>fallback</artifactId><version>1.0</version></project>
      """);
  private static final byte[] METADATA = bytes("""
      <metadata><groupId>com.github.klboke.compat</groupId><artifactId>fallback</artifactId>
      <versioning><latest>1.0</latest><release>1.0</release><versions><version>1.0</version></versions>
      <lastUpdated>20260101000000</lastUpdated></versioning></metadata>
      """);

  @ParameterizedTest
  @ValueSource(ints = {303, 404, 400, 500})
  void failedProxyDoesNotHideLaterHostedContentLikeNexus(int upstreamStatus) throws Exception {
    assumeTrue(Boolean.parseBoolean(CompatDefaults.setting("compat.write.enabled", "COMPAT_WRITE_ENABLED")
        .orElse("false")), "Set COMPAT_WRITE_ENABLED=true on disposable compatibility instances");
    String host = CompatDefaults.setting("compat.mavenGroup.upstreamHost", "MAVEN_GROUP_COMPAT_UPSTREAM_HOST")
        .orElse("host.docker.internal");
    AtomicInteger redirectedRequests = new AtomicInteger();
    HttpServer target = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
    HttpServer upstream = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
    target.createContext("/", exchange -> {
      redirectedRequests.incrementAndGet();
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
    });
    upstream.createContext("/", exchange -> {
      if (upstreamStatus == 303) {
        // A different port is a different origin, even with the same hostname.
        exchange.getResponseHeaders().set("Location", "http://" + host + ":"
            + target.getAddress().getPort() + exchange.getRequestURI().getRawPath());
      }
      exchange.sendResponseHeaders(upstreamStatus, -1);
      exchange.close();
    });
    target.start();
    upstream.start();
    try {
      String remote = "http://" + host + ":" + upstream.getAddress().getPort() + "/";
      Endpoint nexus = new Endpoint(CompatDefaults.nexusBaseUrl().orElseThrow(),
          CompatDefaults.nexusUsername().orElseThrow(), CompatDefaults.nexusPassword().orElseThrow(), true);
      Endpoint candidate = new Endpoint(CompatDefaults.nexusPlusBaseUrl().orElseThrow(),
          CompatDefaults.nexusPlusUsername().orElseThrow(), CompatDefaults.nexusPlusPassword().orElseThrow(), false);
      try (var fixtureAccess = nexus.allowFixtureHost(host)) {
        exercise(nexus, remote, upstreamStatus);
        if (upstreamStatus == 303) {
          assertTrue(redirectedRequests.get() > 0, "Nexus must actually follow the fixture redirect");
        }
      }
      int beforeCandidate = redirectedRequests.get();
      exercise(candidate, remote, upstreamStatus);
      assertEquals(beforeCandidate, redirectedRequests.get(), "kkRepo must not contact the rejected target");
    } finally {
      upstream.stop(0);
      target.stop(0);
    }
  }

  private static void exercise(Endpoint endpoint, String remote, int upstreamStatus) throws Exception {
    String prefix = "compat-fallback-" + UUID.randomUUID().toString().substring(0, 8);
    String hosted = prefix + "-hosted";
    String proxy = prefix + "-proxy";
    String group = prefix + "-group";
    List<String> created = new ArrayList<>();
    try {
      endpoint.create(hosted, "hosted", remote, List.of());
      created.add(hosted);
      endpoint.create(proxy, "proxy", remote, List.of());
      created.add(proxy);
      endpoint.create(group, "group", remote, List.of(proxy, hosted));
      created.add(group);
      byte[] sha1 = bytes(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(POM)));
      for (var asset : Map.of(POM_PATH, POM, POM_PATH + ".sha1", sha1,
          BASE + "maven-metadata.xml", METADATA).entrySet()) {
        assertEquals(201, endpoint.content(hosted, asset.getKey(), "PUT", asset.getValue()).statusCode());
      }
      var direct = endpoint.content(proxy, POM_PATH, "GET", null);
      if (upstreamStatus == 303 || upstreamStatus == 404) {
        assertEquals(endpoint.nexus || upstreamStatus == 404 ? 404 : 400,
            direct.statusCode(), "direct proxy diagnostic status");
      } else {
        assertTrue(direct.statusCode() >= 400, "fixture proxy must fail before testing group fallback");
      }
      if (!endpoint.nexus && upstreamStatus == 303) {
        assertTrue(new String(direct.body(), StandardCharsets.UTF_8)
            .contains("remote redirect URL host is not allowed:"));
      }
      assertArrayEquals(POM, endpoint.content(hosted, POM_PATH, "GET", null).body());
      // HEAD first: GET can populate the group cache and otherwise hide a HEAD fanout regression.
      for (String method : List.of("HEAD", "GET")) {
        for (var asset : Map.of(POM_PATH, POM, POM_PATH + ".sha1", sha1).entrySet()) {
          var response = endpoint.content(group, asset.getKey(), method, null);
          assertEquals(200, response.statusCode(), endpoint.base + " group " + method);
          assertEquals(asset.getValue().length,
              response.headers().firstValueAsLong("Content-Length").orElseThrow());
          assertArrayEquals(method.equals("GET") ? asset.getValue() : new byte[0], response.body());
        }
        assertEquals(404, endpoint.content(group, BASE + "2.0/fallback-2.0.pom", method, null).statusCode());
      }
      var metadataHead = endpoint.content(group, BASE + "maven-metadata.xml", "HEAD", null);
      assertEquals(200, metadataHead.statusCode());
      var metadata = endpoint.content(group, BASE + "maven-metadata.xml", "GET", null);
      assertEquals(200, metadata.statusCode());
      var parsed = MavenMetadataXml.read(metadata.body());
      assertEquals(List.of("1.0"), parsed.versions);
      assertEquals("1.0", parsed.latest);
      assertEquals("1.0", parsed.release);
      System.out.printf("Maven group fallback: %s upstream=%d directProxy=%d artifact/sha1 HEAD+GET=200 "
          + "metadata HEAD+GET=200 missing HEAD+GET=404%n",
          endpoint.nexus ? "Nexus" : "kkRepo", upstreamStatus, direct.statusCode());
    } finally {
      assertAll(created.reversed().stream().map(name -> () -> endpoint.delete(name)));
    }
  }

  private record Endpoint(String base, String username, String password, boolean nexus) {
    AutoCloseable allowFixtureHost(String host) throws Exception {
      String path = "/service/rest/v1/security/ssrf-protection";
      var response = send(path, "GET", null);
      if (response.statusCode() == 404) return () -> {}; // Older Nexus has no configuration guard.
      assertEquals(200, response.statusCode());
      var config = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(response.body());
      String field = host.matches("[0-9.]+") ? "allowedIPs" : "allowedDomains";
      var allowed = config.withArray(field);
      for (var entry : allowed) {
        if (host.equals(entry.asText())) return () -> {};
      }
      allowed.add(host);
      assertEquals(200, send(path, "PUT", JSON.writeValueAsBytes(config)).statusCode());
      return () -> {
        // Remove only this test's entry; preserve unrelated settings or allowlist changes.
        var latest = send(path, "GET", null);
        assertEquals(200, latest.statusCode());
        var restored = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(latest.body());
        var entries = restored.withArray(field);
        for (int i = entries.size() - 1; i >= 0; i--) {
          if (host.equals(entries.get(i).asText())) entries.remove(i);
        }
        assertEquals(200, send(path, "PUT", JSON.writeValueAsBytes(restored)).statusCode());
      };
    }

    String catalog() {
      return nexus ? "/service/rest/v1/repositories" : "/internal/repositories";
    }

    void delete(String name) throws Exception {
      if (!nexus) {
        // kkRepo requires empty repositories. Explicit source also removes the group's own cache.
        var empty = send("/internal/browse/" + name + "?path=" + BASE + "&source=" + name, "DELETE", null);
        assertTrue(empty.statusCode() == 200 || empty.statusCode() == 404,
            () -> "empty " + name + ": " + new String(empty.body(), StandardCharsets.UTF_8));
      }
      var response = send(catalog() + "/" + name, "DELETE", null);
      assertEquals(204, response.statusCode(),
          () -> "delete " + name + ": " + new String(response.body(), StandardCharsets.UTF_8));
    }

    void create(String name, String type, String remote, List<String> members) throws Exception {
      var payload = new java.util.LinkedHashMap<String, Object>();
      payload.put("name", name);
      payload.put("online", true);
      if (nexus) {
        payload.put("storage", Map.of("blobStoreName", "default", "strictContentTypeValidation", true,
            "writePolicy", "ALLOW"));
        payload.put("maven", Map.of("versionPolicy", "RELEASE", "layoutPolicy", "PERMISSIVE"));
      } else {
        payload.put("recipe", "maven2-" + type);
        payload.put("blobStoreName", "default");
        payload.put("strictContentTypeValidation", true);
        if (type.equals("hosted")) {
          payload.put("hosted", Map.of("writePolicy", "ALLOW", "versionPolicy", "RELEASE",
              "layoutPolicy", "PERMISSIVE"));
        }
      }
      if (type.equals("proxy")) {
        payload.put("proxy", Map.of("remoteUrl", remote, "autoBlock", false));
        if (nexus) {
          payload.put("proxy", Map.of("remoteUrl", remote, "contentMaxAge", 0, "metadataMaxAge", 0));
          payload.put("negativeCache", Map.of("enabled", false, "timeToLive", 1));
          payload.put("httpClient", Map.of("blocked", false, "autoBlock", false));
        }
      } else if (type.equals("group")) {
        payload.put("group", Map.of("memberNames", members));
      }
      var response = send(catalog() + (nexus ? "/maven/" + type : ""), "POST", JSON.writeValueAsBytes(payload));
      assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
          () -> "create " + name + ": " + response.statusCode() + " " + new String(response.body(), StandardCharsets.UTF_8));
    }

    HttpResponse<byte[]> content(String repo, String path, String method, byte[] body) throws Exception {
      return send("/repository/" + repo + "/" + path, method, body);
    }

    HttpResponse<byte[]> send(String path, String method, byte[] body) throws Exception {
      String auth = Base64.getEncoder().encodeToString(bytes(username + ":" + password));
      return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
          .header("Authorization", "Basic " + auth)
          .header("Content-Type", path.startsWith("/repository/") ? "application/xml" : "application/json")
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
          .build(), HttpResponse.BodyHandlers.ofByteArray());
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
