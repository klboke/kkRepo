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
