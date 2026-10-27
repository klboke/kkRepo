package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RawRepositoryBlackBoxCompatibilityTest {
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void hostedProxyAndGroupRoundTripMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run raw black-box compatibility");

    if (config.setupEnabled()) {
      ensureNexusRepositories(config);
      ensureKkRepoRepositories(config);
    }

    Fixture fixture = Fixture.create();
    try {
      Exchange referencePut = put(config.nexusHosted(), fixture.path(), fixture.body(), "text/plain");
      Exchange candidatePut = put(config.nexusPlusHosted(), fixture.path(), fixture.body(), "text/plain");
      assertSameStatus("hosted PUT", referencePut, candidatePut);
      assertEquals(201, referencePut.status(), "reference hosted PUT status");
      assertEquals(201, candidatePut.status(), "kkrepo hosted PUT status");

      assertBodyMatches("hosted GET",
          get(config.nexusHosted(), fixture.path()),
          get(config.nexusPlusHosted(), fixture.path()),
          fixture.body());
      assertSameStatus("hosted HEAD",
          head(config.nexusHosted(), fixture.path()),
          head(config.nexusPlusHosted(), fixture.path()));

      put(config.nexusHosted(), fixture.indexPath(), fixture.indexBody(), "text/html");
      put(config.nexusPlusHosted(), fixture.indexPath(), fixture.indexBody(), "text/html");
      assertBodyMatches("hosted directory index",
          get(config.nexusHosted(), fixture.indexDirectory()),
          get(config.nexusPlusHosted(), fixture.indexDirectory()),
          fixture.indexBody());

      assertBodyMatches("group hosted first-match",
          get(config.nexusGroup(), fixture.path()),
          get(config.nexusPlusGroup(), fixture.path()),
          fixture.body());

      Exchange referenceProxy = get(config.nexusProxy(), config.proxyProbePath());
      Exchange candidateProxy = get(config.nexusPlusProxy(), config.proxyProbePath());
      assertSameStatus("proxy GET", referenceProxy, candidateProxy);
      assert2xx("reference proxy GET", referenceProxy);
      assert2xx("kkrepo proxy GET", candidateProxy);
      assertArrayEquals(referenceProxy.body(), candidateProxy.body(), "proxy GET body");
      assertSameStatus("proxy HEAD",
          head(config.nexusProxy(), config.proxyProbePath()),
          head(config.nexusPlusProxy(), config.proxyProbePath()));

      Exchange referenceDelete = delete(config.nexusHosted(), fixture.path());
      Exchange candidateDelete = delete(config.nexusPlusHosted(), fixture.path());
      assertSameStatus("hosted DELETE", referenceDelete, candidateDelete);
      assertEquals(204, referenceDelete.status(), "reference hosted DELETE status");
      assertEquals(204, candidateDelete.status(), "kkrepo hosted DELETE status");
      assertSameStatus("hosted deleted GET",
          get(config.nexusHosted(), fixture.path()),
          get(config.nexusPlusHosted(), fixture.path()));
    } finally {
      delete(config.nexusHosted(), fixture.path());
      delete(config.nexusPlusHosted(), fixture.path());
      delete(config.nexusHosted(), fixture.indexPath());
      delete(config.nexusPlusHosted(), fixture.indexPath());
    }
  }

  @Test
  void proxySupportsFreeBsdAbiPathsWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured() && config.proxyProbePath().startsWith("FreeBSD:"),
        "Set COMPAT_RAW_PROXY_PROBE_PATH to a provisioned FreeBSD:14:amd64/... fixture");
    if (config.setupEnabled()) {
      ensureNexusRepositories(config);
      ensureKkRepoRepositories(config);
    }
    Exchange reference = get(config.nexusProxy(), config.proxyProbePath());
    Exchange candidate = get(config.nexusPlusProxy(), config.proxyProbePath());
    assert2xx("FreeBSD reference proxy", reference);
    assertSameStatus("FreeBSD proxy", reference, candidate);
    assertArrayEquals(reference.body(), candidate.body());
    assertSameStatus("FreeBSD proxy HEAD", head(config.nexusProxy(), config.proxyProbePath()),
        head(config.nexusPlusProxy(), config.proxyProbePath()));
  }

  @Test
  void proxyDirectoryPagesAndIndexFallbackMatchNexusWhenConfigured() throws Exception {
    CompatConfig configured = CompatConfig.load();
    assumeTrue(configured.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL for raw directory compatibility");
    String host = setting("compat.raw.upstreamHost", "RAW_COMPAT_UPSTREAM_HOST")
        .orElse("host.docker.internal");
    var upstream = com.sun.net.httpserver.HttpServer.create(
        new java.net.InetSocketAddress("0.0.0.0", 0), 0);
    byte[] listing = "<html><a href=\"file.txt\">download</a></html>".getBytes(StandardCharsets.UTF_8);
    byte[] index = "<html>Explicit index file</html>".getBytes(StandardCharsets.UTF_8);
    byte[] file = "Raw proxy download\n".getBytes(StandardCharsets.UTF_8);
    Map<String, byte[]> content = Map.of(
        "/archive/", listing,
        "/archive/sub/", listing,
        "/archive/head-first/", listing,
        "/archive/index.html", index,
        "/archive/sub/index.html", index,
        "/archive/static/index.html", index,
        "/archive/legacy/index.htm", index,
        "/archive/file.txt", file,
        "/archive/sub/file.txt", file);
    Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    upstream.createContext("/", exchange -> {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        byte[] body = content.get(path);
        if (body == null) {
          exchange.sendResponseHeaders(404, -1);
        } else {
          exchange.getResponseHeaders().set("Content-Type", path.endsWith(".txt") ? "text/plain" : "text/html");
          exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
          if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
          } else {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
        }
      }
    });
    upstream.start();
    String prefix = "raw-directory-" + UUID.randomUUID().toString().substring(0, 8);
    CompatConfig config = new CompatConfig(configured.nexus(), configured.nexusPlus(), true,
        prefix + "-hosted", prefix + "-proxy", prefix + "-group",
        "http://" + host + ":" + upstream.getAddress().getPort() + "/archive/", "file.txt");
    try (var fixtureAccess = allowDirectoryFixtureHost(config.nexus(), host)) {
      ensureNexusRepositories(config);
      ensureKkRepoRepositories(config);
      for (Endpoint endpoint : List.of(config.nexusProxy(), config.nexusPlusProxy())) {
        for (String path : List.of("", "sub/")) {
          Exchange page = get(endpoint, path);
          assertEquals(200, page.status(), endpoint.name() + " directory " + path);
          assertArrayEquals(listing, page.body(), endpoint.name() + " upstream directory body");
          assertTrue(page.contentType().orElse("").startsWith("text/html"));
          int before = requests.get("/archive/" + path).get();
          assertArrayEquals(listing, get(endpoint, path).body());
          assertEquals(before, requests.get("/archive/" + path).get(), "directory cache hit");
          // Nexus 3.68 only forwards directory GETs. Preserve kkRepo's existing HEAD
          // support while ensuring it describes the same content as GET.
          if (endpoint.name().equals("kkrepo")) assertEquals(200, head(endpoint, path).status());
        }
        // Directory responses must never occupy the explicit index.html cache entry.
        for (String path : List.of("index.html", "sub/index.html", "static/", "legacy/")) {
          Exchange page = get(endpoint, path);
          assertEquals(200, page.status(), endpoint.name() + " index " + path);
          assertArrayEquals(index, page.body());
        }
        if (endpoint.name().equals("kkrepo")) {
          Exchange firstHead = head(endpoint, "head-first/");
          assertEquals(200, firstHead.status());
          assertEquals(0, firstHead.body().length);
          assertArrayEquals(listing, get(endpoint, "head-first/").body(), "HEAD must not cache an empty page");
        }
        for (String path : List.of("file.txt", "sub/file.txt")) {
          assertArrayEquals(file, get(endpoint, path).body(), "relative directory link download");
        }
        assertEquals(404, get(endpoint, "missing/").status());
        assertEquals(400, send(endpoint.raw("/repository/" + endpoint.repository()).GET()).status());
      }
      assertBodyMatches("group root proxy directory", get(config.nexusGroup(), ""),
          get(config.nexusPlusGroup(), ""), listing);
      assertBodyMatches("group nested proxy directory", get(config.nexusGroup(), "sub/"),
          get(config.nexusPlusGroup(), "sub/"), listing);
    } finally {
      upstream.stop(0);
      // kkRepo requires repositories to be empty before deletion. Directory pages use
      // ordinary asset cleanup too, including the root '.' entry.
      for (String path : List.of(".", "index.html", "sub", "static", "legacy", "head-first", "file.txt")) {
        send(config.nexusPlusInternal("/internal/browse/" + config.proxyRepository() + "?path=" + path).DELETE());
      }
      for (String repository : List.of(config.groupRepository(), config.proxyRepository(), config.hostedRepository())) {
        send(config.nexusAdmin("/service/rest/v1/repositories/" + repository).DELETE());
        Exchange deleted = send(config.nexusPlusInternal("/internal/repositories/" + repository).DELETE());
        assertTrue(deleted.status() == 204 || deleted.status() == 404,
            "fixture repository cleanup: " + deleted.bodyAsString());
      }
    }
  }

  private static AutoCloseable allowDirectoryFixtureHost(Endpoint nexus, String host) throws Exception {
    String path = "/service/rest/v1/security/ssrf-protection";
    Exchange response = send(nexus.raw(path).GET());
    if (response.status() == 404) return () -> {}; // Older Nexus has no outbound allowlist.
    assertEquals(200, response.status());
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var config = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(response.body());
    String field = host.matches("[0-9.]+") ? "allowedIPs" : "allowedDomains";
    var allowed = config.withArray(field);
    for (var entry : allowed) if (host.equals(entry.asText())) return () -> {};
    allowed.add(host);
    assertEquals(200, send(nexus.raw(path).header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(config)))).status());
    return () -> {
      Exchange latest = send(nexus.raw(path).GET());
      assertEquals(200, latest.status());
      var restored = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(latest.body());
      var entries = restored.withArray(field);
      for (int index = entries.size() - 1; index >= 0; index--) {
        if (host.equals(entries.get(index).asText())) entries.remove(index);
      }
      assertEquals(200, send(nexus.raw(path).header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(restored)))).status());
    };
  }

  private static Exchange get(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).GET());
  }

  private static Exchange head(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).method("HEAD", HttpRequest.BodyPublishers.noBody()));
  }

  private static Exchange put(Endpoint endpoint, String path, byte[] body, String contentType) throws Exception {
    return send(endpoint.request(path)
        .header("Content-Type", contentType)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
  }

  private static Exchange delete(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).DELETE());
  }

  private static Exchange send(HttpRequest.Builder builder) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        builder.header("User-Agent", "kkrepo-raw-compat-test/1")
            .timeout(Duration.ofSeconds(120))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(
        response.statusCode(),
        response.body(),
        response.headers().firstValue("content-type"),
        response.headers().firstValue("content-length"),
        response.headers().firstValue("content-disposition"));
  }

  private static void assertBodyMatches(String label, Exchange reference, Exchange candidate, byte[] expected) {
    assertSameStatus(label, reference, candidate);
    assert2xx(label + " reference", reference);
    assert2xx(label + " candidate", candidate);
    assertArrayEquals(expected, reference.body(), label + " reference body");
    assertArrayEquals(expected, candidate.body(), label + " candidate body");
    assertEquals(reference.contentDisposition().isPresent(), candidate.contentDisposition().isPresent(),
        label + " Content-Disposition presence");
  }

  private static void assertSameStatus(String label, Exchange reference, Exchange candidate) {
    assertEquals(reference.status(), candidate.status(), label + " status");
  }

  private static void assert2xx(String label, Exchange exchange) {
    assertTrue(exchange.status() >= 200 && exchange.status() < 300,
        label + " expected 2xx but got " + exchange.status()
            + " body=" + new String(exchange.body(), StandardCharsets.UTF_8));
  }

  private static void ensureNexusRepositories(CompatConfig config) throws Exception {
    String repositories = send(config.nexusAdmin("/service/rest/v1/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\" : \"" + config.hostedRepository() + "\"")) {
      assert2xx("create Nexus raw hosted", send(config.nexusAdmin(
          "/service/rest/v1/repositories/raw/hosted")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"allow"},"raw":{"contentDisposition":"ATTACHMENT"}}
              """.formatted(config.hostedRepository())))));
    }
    repositories = send(config.nexusAdmin("/service/rest/v1/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\" : \"" + config.proxyRepository() + "\"")) {
      assert2xx("create Nexus raw proxy", send(config.nexusAdmin(
          "/service/rest/v1/repositories/raw/proxy")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true},"proxy":{"remoteUrl":"%s","contentMaxAge":1440,"metadataMaxAge":1440},"negativeCache":{"enabled":true,"timeToLive":1440},"httpClient":{"blocked":false,"autoBlock":true},"raw":{"contentDisposition":"ATTACHMENT"}}
              """.formatted(config.proxyRepository(), config.remoteUrl())))));
    }
    String groupPayload = """
        {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true},"group":{"memberNames":["%s","%s"]},"raw":{"contentDisposition":"ATTACHMENT"}}
        """.formatted(config.groupRepository(), config.hostedRepository(), config.proxyRepository());
    repositories = send(config.nexusAdmin("/service/rest/v1/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\" : \"" + config.groupRepository() + "\"")) {
      assert2xx("create Nexus raw group", send(config.nexusAdmin(
          "/service/rest/v1/repositories/raw/group")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(groupPayload))));
    } else {
      assert2xx("update Nexus raw group", send(config.nexusAdmin(
          "/service/rest/v1/repositories/raw/group/" + config.groupRepository())
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(groupPayload))));
    }
  }

  private static void ensureKkRepoRepositories(CompatConfig config) throws Exception {
    String repositories = send(config.nexusPlusInternal("/internal/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\":\"" + config.hostedRepository() + "\"")) {
      assert2xx("create kkrepo raw hosted", send(config.nexusPlusInternal("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","recipe":"raw-hosted","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"hosted":{"writePolicy":"ALLOW","versionPolicy":null,"layoutPolicy":null},"raw":{"contentDisposition":"ATTACHMENT"}}
              """.formatted(config.hostedRepository())))));
    }
    repositories = send(config.nexusPlusInternal("/internal/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\":\"" + config.proxyRepository() + "\"")) {
      assert2xx("create kkrepo raw proxy", send(config.nexusPlusInternal("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","recipe":"raw-proxy","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"proxy":{"remoteUrl":"%s","contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":1440,"autoBlock":true},"raw":{"contentDisposition":"ATTACHMENT"}}
              """.formatted(config.proxyRepository(), config.remoteUrl())))));
    }
    String groupPayload = """
        {"name":"%s","recipe":"raw-group","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"group":{"memberNames":["%s","%s"]},"raw":{"contentDisposition":"ATTACHMENT"}}
        """.formatted(config.groupRepository(), config.hostedRepository(), config.proxyRepository());
    repositories = send(config.nexusPlusInternal("/internal/repositories").GET()).bodyAsString();
    if (!repositories.contains("\"name\":\"" + config.groupRepository() + "\"")) {
      assert2xx("create kkrepo raw group", send(config.nexusPlusInternal("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(groupPayload))));
    } else {
      assert2xx("update kkrepo raw group", send(config.nexusPlusInternal(
          "/internal/repositories/" + config.groupRepository())
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(groupPayload))));
    }
  }

  private record Exchange(
      int status,
      byte[] body,
      Optional<String> contentType,
      Optional<String> contentLength,
      Optional<String> contentDisposition) {
    String bodyAsString() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private record Fixture(String path, byte[] body, String indexDirectory, String indexPath, byte[] indexBody) {
    static Fixture create() {
      String stamp = String.valueOf(System.currentTimeMillis());
      String path = "compat/raw/" + stamp + "/fixture.txt";
      byte[] body = ("kkrepo raw compatibility " + stamp + "\n").getBytes(StandardCharsets.UTF_8);
      String indexDirectory = "compat/raw/" + stamp + "/site/";
      String indexPath = indexDirectory + "index.html";
      byte[] indexBody = ("<html><body>raw index " + stamp + "</body></html>\n")
          .getBytes(StandardCharsets.UTF_8);
      return new Fixture(path, body, indexDirectory, indexPath, indexBody);
    }
  }

  private record CompatConfig(
      Endpoint nexus,
      Endpoint nexusPlus,
      boolean setupEnabled,
      String hostedRepository,
      String proxyRepository,
      String groupRepository,
      String remoteUrl,
      String proxyProbePath) {
    static CompatConfig load() {
      Endpoint nexus = new Endpoint(
          "nexus",
          CompatDefaults.nexusBaseUrl(),
          CompatDefaults.nexusUsername(),
          CompatDefaults.nexusPassword(),
          "");
      Endpoint nexusPlus = new Endpoint(
          "kkrepo",
          CompatDefaults.nexusPlusBaseUrl(),
          CompatDefaults.nexusPlusUsername(),
          CompatDefaults.nexusPlusPassword(),
          "");
      boolean setup = Boolean.parseBoolean(
          setting("compat.raw.setup.enabled", "COMPAT_RAW_SETUP_ENABLED").orElse("true"));
      return new CompatConfig(
          nexus,
          nexusPlus,
          setup,
          setting("compat.raw.hostedRepository", "COMPAT_RAW_HOSTED_REPOSITORY").orElse("raw-hosted"),
          setting("compat.raw.proxyRepository", "COMPAT_RAW_PROXY_REPOSITORY").orElse("raw-proxy"),
          setting("compat.raw.groupRepository", "COMPAT_RAW_GROUP_REPOSITORY").orElse("raw-group"),
          stripTrailingSlash(setting("compat.raw.remoteUrl", "COMPAT_RAW_REMOTE_URL")
              .orElse("https://raw.githubusercontent.com/github/gitignore/main")),
          setting("compat.raw.proxyProbePath", "COMPAT_RAW_PROXY_PROBE_PATH").orElse("Java.gitignore"));
    }

    boolean configured() {
      return nexus.baseUrl().isPresent() && nexusPlus.baseUrl().isPresent();
    }

    Endpoint nexusHosted() { return nexus.withRepository(hostedRepository); }
    Endpoint nexusProxy() { return nexus.withRepository(proxyRepository); }
    Endpoint nexusGroup() { return nexus.withRepository(groupRepository); }
    Endpoint nexusPlusHosted() { return nexusPlus.withRepository(hostedRepository); }
    Endpoint nexusPlusProxy() { return nexusPlus.withRepository(proxyRepository); }
    Endpoint nexusPlusGroup() { return nexusPlus.withRepository(groupRepository); }

    HttpRequest.Builder nexusAdmin(String path) {
      return nexus.raw(path);
    }

    HttpRequest.Builder nexusPlusInternal(String path) {
      return nexusPlus.raw(path);
    }
  }

  private record Endpoint(
      String name,
      Optional<String> baseUrl,
      Optional<String> username,
      Optional<String> password,
      String repository) {
    HttpRequest.Builder request(String repositoryPath) {
      String suffix = repositoryPath == null || repositoryPath.isBlank()
          ? ""
          : repositoryPath;
      return raw("/repository/" + repository + "/" + suffix);
    }

    HttpRequest.Builder raw(String path) {
      URI uri = URI.create(baseUrl.orElseThrow() + path);
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
      if (username.isPresent() && password.isPresent()) {
        String token = Base64.getEncoder().encodeToString(
            (username.get() + ":" + password.get()).getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + token);
      }
      return builder;
    }

    Endpoint withRepository(String repository) {
      return new Endpoint(name, baseUrl, username, password, repository);
    }
  }

  private static Optional<String> setting(String property, String env) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      value = System.getenv(env);
    }
    return value == null || value.isBlank()
        ? Optional.empty()
        : Optional.of(value.trim());
  }

  private static Optional<String> urlSetting(String property, String env) {
    return setting(property, env).map(RawRepositoryBlackBoxCompatibilityTest::stripTrailingSlash);
  }

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
