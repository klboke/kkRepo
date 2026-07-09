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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GoProxyBlackBoxCompatibilityTest {
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void proxyModuleEndpointsMatchNexusWhenConfigured() throws Exception {
    GoCompatConfig config = GoCompatConfig.load();
    assumeTrue(config.referenceReachable(),
        "Reference Nexus is not reachable; start Nexus or override GO_NEXUS_COMPAT_BASE_URL");
    assumeTrue(config.candidateReachable(),
        "kkrepo is not reachable; start it with scripts/dev.sh or override GO_KKREPO_COMPAT_BASE_URL");
    assumeTrue(config.candidateManagementConfigured(),
        "kkrepo repository setup requires GO_KKREPO_COMPAT_USERNAME and GO_KKREPO_COMPAT_PASSWORD");

    ensureReferenceRepository(config);
    ensureCandidateBlobStore(config);
    ensureCandidateRepository(config);

    Endpoint reference = config.referenceEndpoint();
    Endpoint candidate = config.candidateEndpoint();
    for (Probe probe : Probe.defaultProbes()) {
      Exchange referenceExchange = send(reference.request(probe.path(), probe.method()));
      Exchange candidateExchange = send(candidate.request(probe.path(), probe.method()));
      assertSameExchange(probe.label(), referenceExchange, candidateExchange, probe.compareBody());
    }
  }

  @Test
  void groupModuleEndpointsMatchNexusWhenConfigured() throws Exception {
    GoCompatConfig config = GoCompatConfig.load();
    assumeTrue(config.referenceReachable(),
        "Reference Nexus is not reachable; start Nexus or override GO_NEXUS_COMPAT_BASE_URL");
    assumeTrue(config.candidateReachable(),
        "kkrepo is not reachable; start it with scripts/dev.sh or override GO_KKREPO_COMPAT_BASE_URL");
    assumeTrue(config.candidateManagementConfigured(),
        "kkrepo repository setup requires GO_KKREPO_COMPAT_USERNAME and GO_KKREPO_COMPAT_PASSWORD");

    ensureReferenceProxyRepository(config, config.groupMissRepository(), config.missingRemoteUrl());
    ensureReferenceProxyRepository(config, config.groupHitRepository(), config.remoteUrl());
    ensureReferenceGroupRepository(config);
    ensureCandidateBlobStore(config);
    ensureCandidateProxyRepository(config, config.groupMissRepository(), config.missingRemoteUrl());
    ensureCandidateProxyRepository(config, config.groupHitRepository(), config.remoteUrl());
    ensureCandidateGroupRepository(config);

    Endpoint reference = config.referenceGroupEndpoint();
    Endpoint candidate = config.candidateGroupEndpoint();
    for (Probe probe : Probe.defaultProbes()) {
      Exchange referenceExchange = send(reference.request(probe.path(), probe.method()));
      Exchange candidateExchange = send(candidate.request(probe.path(), probe.method()));
      assertSameExchange("group " + probe.label(), referenceExchange, candidateExchange, probe.compareBody());
    }
  }

  private static void ensureReferenceRepository(GoCompatConfig config) throws Exception {
    ensureReferenceProxyRepository(config, config.nexusRepository(), config.remoteUrl());
  }

  private static void ensureReferenceProxyRepository(
      GoCompatConfig config,
      String repository,
      String remoteUrl) throws Exception {
    URI getUri = URI.create(config.nexusBaseUrl()
        + "/service/rest/v1/repositories/go/proxy/" + repository);
    Exchange get = send(config.nexusAdminRequest(getUri).GET());
    assertTrue(get.status() == 200 || get.status() == 404,
        "reference go proxy repository lookup status=" + get.status()
            + " body=" + new String(get.body(), StandardCharsets.UTF_8));
    String body = """
        {
          "name": "%s",
          "online": true,
          "storage": {
            "blobStoreName": "default",
            "strictContentTypeValidation": true
          },
          "proxy": {
            "remoteUrl": "%s",
            "contentMaxAge": 1440,
            "metadataMaxAge": 1440
          },
          "negativeCache": {
            "enabled": true,
            "timeToLive": 1440
          },
          "httpClient": {
            "blocked": false,
            "autoBlock": true
          }
        }
        """.formatted(repository, remoteUrl);
    String path = get.status() == 200
        ? "/service/rest/v1/repositories/go/proxy/" + repository
        : "/service/rest/v1/repositories/go/proxy";
    HttpRequest.Builder request = config.nexusAdminRequest(URI.create(config.nexusBaseUrl() + path))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30));
    Exchange saved = send(get.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(saved.status() >= 200 && saved.status() < 300,
        "save reference go proxy repository status=" + saved.status()
            + " body=" + new String(saved.body(), StandardCharsets.UTF_8));
  }

  private static void ensureReferenceGroupRepository(GoCompatConfig config) throws Exception {
    URI getUri = URI.create(config.nexusBaseUrl()
        + "/service/rest/v1/repositories/go/group/" + config.groupRepository());
    Exchange get = send(config.nexusAdminRequest(getUri).GET());
    assertTrue(get.status() == 200 || get.status() == 404,
        "reference go group repository lookup status=" + get.status()
            + " body=" + new String(get.body(), StandardCharsets.UTF_8));
    String body = """
        {
          "name": "%s",
          "online": true,
          "storage": {
            "blobStoreName": "default",
            "strictContentTypeValidation": true
          },
          "group": {
            "memberNames": ["%s", "%s"]
          }
        }
        """.formatted(
        config.groupRepository(),
        config.groupMissRepository(),
        config.groupHitRepository());
    String path = get.status() == 200
        ? "/service/rest/v1/repositories/go/group/" + config.groupRepository()
        : "/service/rest/v1/repositories/go/group";
    HttpRequest.Builder request = config.nexusAdminRequest(URI.create(config.nexusBaseUrl() + path))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30));
    Exchange saved = send(get.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(saved.status() >= 200 && saved.status() < 300,
        "save reference go group repository status=" + saved.status()
            + " body=" + new String(saved.body(), StandardCharsets.UTF_8));
  }

  private static void ensureCandidateBlobStore(GoCompatConfig config) throws Exception {
    String body = """
        {
          "name": "%s",
          "engine": "%s",
          "endpoint": "%s",
          "region": "%s",
          "bucket": "%s",
          "prefix": "%s",
          "accessKey": "%s",
          "secretKey": "%s",
          "pathStyleAccess": true
        }
        """.formatted(
        config.blobStoreName(),
        config.blobStoreEngine(),
        config.blobStoreEndpoint(),
        config.blobStoreRegion(),
        config.blobStoreBucket(),
        config.blobStorePrefix(),
        config.blobStoreAccessKey(),
        config.blobStoreSecretKey());
    Exchange created = send(config.nexusPlusAdminRequest(URI.create(
            config.nexusPlusBaseUrl() + "/internal/blob-stores"))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(created.status() == 200 || created.status() == 409,
        "ensure kkrepo blob store status=" + created.status()
            + " body=" + new String(created.body(), StandardCharsets.UTF_8));
  }

  private static void ensureCandidateRepository(GoCompatConfig config) throws Exception {
    ensureCandidateProxyRepository(config, config.nexusPlusRepository(), config.remoteUrl());
  }

  private static void ensureCandidateProxyRepository(
      GoCompatConfig config,
      String repository,
      String remoteUrl) throws Exception {
    Exchange get = send(config.nexusPlusAdminRequest(URI.create(
            config.nexusPlusBaseUrl() + "/internal/repositories/" + repository))
        .timeout(Duration.ofSeconds(30))
        .GET());
    assertTrue(get.status() == 200 || get.status() == 404,
        "kkrepo go proxy repository lookup status=" + get.status()
            + " body=" + new String(get.body(), StandardCharsets.UTF_8));
    String body = """
        {
          "name": "%s",
          "recipe": "go-proxy",
          "online": true,
          "blobStoreName": "%s",
          "strictContentTypeValidation": true,
          "proxy": {
            "remoteUrl": "%s",
            "contentMaxAgeMinutes": 1440,
            "metadataMaxAgeMinutes": 1440,
            "autoBlock": true
          }
        }
        """.formatted(repository, config.blobStoreName(), remoteUrl);
    String path = get.status() == 200
        ? "/internal/repositories/" + repository
        : "/internal/repositories";
    HttpRequest.Builder request = config.nexusPlusAdminRequest(URI.create(config.nexusPlusBaseUrl() + path))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json");
    Exchange saved = send(get.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(saved.status() >= 200 && saved.status() < 300,
        "save kkrepo go proxy repository status=" + saved.status()
            + " body=" + new String(saved.body(), StandardCharsets.UTF_8));
  }

  private static void ensureCandidateGroupRepository(GoCompatConfig config) throws Exception {
    Exchange get = send(config.nexusPlusAdminRequest(URI.create(
            config.nexusPlusBaseUrl() + "/internal/repositories/" + config.groupRepository()))
        .timeout(Duration.ofSeconds(30))
        .GET());
    assertTrue(get.status() == 200 || get.status() == 404,
        "kkrepo go group repository lookup status=" + get.status()
            + " body=" + new String(get.body(), StandardCharsets.UTF_8));
    String body = """
        {
          "name": "%s",
          "recipe": "go-group",
          "online": true,
          "blobStoreName": "%s",
          "strictContentTypeValidation": true,
          "group": {
            "memberNames": ["%s", "%s"]
          }
        }
        """.formatted(
        config.groupRepository(),
        config.blobStoreName(),
        config.groupMissRepository(),
        config.groupHitRepository());
    String path = get.status() == 200
        ? "/internal/repositories/" + config.groupRepository()
        : "/internal/repositories";
    HttpRequest.Builder request = config.nexusPlusAdminRequest(URI.create(config.nexusPlusBaseUrl() + path))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json");
    Exchange saved = send(get.status() == 200
        ? request.PUT(HttpRequest.BodyPublishers.ofString(body))
        : request.POST(HttpRequest.BodyPublishers.ofString(body)));
    assertTrue(saved.status() >= 200 && saved.status() < 300,
        "save kkrepo go group repository status=" + saved.status()
            + " body=" + new String(saved.body(), StandardCharsets.UTF_8));
  }

  private static Exchange send(HttpRequest.Builder builder) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        builder.header("User-Agent", "kkrepo-go-compat-test/1")
            .timeout(Duration.ofSeconds(60))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(
        response.statusCode(),
        response.body(),
        response.headers().firstValue("content-type"),
        response.headers().firstValue("content-length"),
        response.headers().firstValue("etag"),
        response.headers().firstValue("last-modified"));
  }

  private static void assertSameExchange(
      String label, Exchange reference, Exchange candidate, boolean compareBody) {
    assertEquals(reference.status(), candidate.status(), label + " status");
    assertEquals(reference.contentType().orElse(null), candidate.contentType().orElse(null),
        label + " Content-Type");
    if (compareBody) {
      assertArrayEquals(reference.body(), candidate.body(), label + " body");
    }
    if (reference.contentLength().isPresent() && candidate.contentLength().isPresent()) {
      assertEquals(reference.contentLength().get(), candidate.contentLength().get(),
          label + " Content-Length");
    }
    assertEquals(reference.lastModified().isPresent(), candidate.lastModified().isPresent(),
        label + " Last-Modified presence");
  }

  private record Probe(String method, String path, String label, boolean compareBody) {
    static List<Probe> defaultProbes() {
      return List.of(
          new Probe("GET", "rsc.io/quote/@v/list", "list GET", true),
          new Probe("GET", "rsc.io/quote/@latest", "latest GET", true),
          new Probe("GET", "rsc.io/quote/@v/v1.5.2.info", "info GET", true),
          new Probe("GET", "rsc.io/quote/@v/v1.5.2.mod", "mod GET", true),
          new Probe("GET", "rsc.io/quote/@v/v1.5.2.zip", "zip GET", true),
          new Probe("HEAD", "rsc.io/quote/@v/v1.5.2.mod", "mod HEAD", false),
          new Probe("HEAD", "rsc.io/quote/@v/v1.5.2.zip", "zip HEAD", false));
    }
  }

  private record Exchange(
      int status,
      byte[] body,
      Optional<String> contentType,
      Optional<String> contentLength,
      Optional<String> etag,
      Optional<String> lastModified) {}

  private record Endpoint(
      String baseUrl,
      String repository,
      Optional<String> username,
      Optional<String> password) {
    HttpRequest.Builder request(String path, String method) {
      URI uri = URI.create(baseUrl + "/repository/" + repository + "/" + path);
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
      if (username.isPresent() && password.isPresent()) {
        String token = Base64.getEncoder().encodeToString(
            (username.get() + ":" + password.get()).getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + token);
      }
      return "HEAD".equals(method)
          ? builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
          : builder.GET();
    }
  }

  private record GoCompatConfig(
      String nexusBaseUrl,
      String nexusRepository,
      String nexusUsername,
      String nexusPassword,
      String nexusPlusBaseUrl,
      Optional<String> nexusPlusUsername,
      Optional<String> nexusPlusPassword,
      String nexusPlusRepository,
      String groupRepository,
      String groupMissRepository,
      String groupHitRepository,
      String remoteUrl,
      String missingRemoteUrl,
      String blobStoreName,
      String blobStoreEngine,
      String blobStoreEndpoint,
      String blobStoreRegion,
      String blobStoreBucket,
      String blobStorePrefix,
      String blobStoreAccessKey,
      String blobStoreSecretKey) {
    static GoCompatConfig load() {
      return new GoCompatConfig(
          stripTrailingSlash(setting("compat.go.nexus.baseUrl", "GO_NEXUS_COMPAT_BASE_URL")
              .orElse(CompatDefaults.NEXUS_BASE_URL)),
          setting("compat.go.nexus.repository", "GO_NEXUS_COMPAT_REPOSITORY")
              .orElse("go-proxy-compat"),
          setting("compat.go.nexus.username", "GO_NEXUS_COMPAT_USERNAME").orElse(CompatDefaults.NEXUS_USERNAME),
          setting("compat.go.nexus.password", "GO_NEXUS_COMPAT_PASSWORD").orElse(CompatDefaults.NEXUS_PASSWORD),
          stripTrailingSlash(setting("compat.go.nexusPlus.baseUrl", "GO_KKREPO_COMPAT_BASE_URL")
              .orElse(CompatDefaults.KKREPO_BASE_URL)),
          setting("compat.go.nexusPlus.username", "GO_KKREPO_COMPAT_USERNAME")
              .or(() -> setting("compat.nexusPlus.username", "KKREPO_COMPAT_USERNAME"))
              .or(CompatDefaults::nexusPlusUsername),
          setting("compat.go.nexusPlus.password", "GO_KKREPO_COMPAT_PASSWORD")
              .or(() -> setting("compat.nexusPlus.password", "KKREPO_COMPAT_PASSWORD"))
              .or(CompatDefaults::nexusPlusPassword),
          setting("compat.go.nexusPlus.repository", "GO_KKREPO_COMPAT_REPOSITORY")
              .orElse("go-proxy-compat"),
          setting("compat.go.group.repository", "GO_GROUP_COMPAT_REPOSITORY")
              .orElse("go-group-compat"),
          setting("compat.go.group.missRepository", "GO_GROUP_COMPAT_MISS_REPOSITORY")
              .orElse("go-group-compat-miss"),
          setting("compat.go.group.hitRepository", "GO_GROUP_COMPAT_HIT_REPOSITORY")
              .orElse("go-group-compat-hit"),
          stripTrailingSlash(setting("compat.go.remoteUrl", "GO_COMPAT_REMOTE_URL")
              .orElse("https://proxy.golang.org")),
          stripTrailingSlash(setting("compat.go.group.missingRemoteUrl", "GO_GROUP_COMPAT_MISSING_REMOTE_URL")
              .orElse("https://example.com")),
          setting("compat.go.nexusPlus.blobStoreName", "GO_KKREPO_BLOB_STORE")
              .orElse("default"),
          setting("compat.go.nexusPlus.blobStoreEngine", "GO_KKREPO_BLOB_ENGINE")
              .orElse("aws-s3"),
          setting("compat.go.nexusPlus.blobStoreEndpoint", "GO_KKREPO_BLOB_ENDPOINT")
              .orElse("http://127.0.0.1:9000"),
          setting("compat.go.nexusPlus.blobStoreRegion", "GO_KKREPO_BLOB_REGION")
              .orElse("cn-hangzhou"),
          setting("compat.go.nexusPlus.blobStoreBucket", "GO_KKREPO_BLOB_BUCKET")
              .orElse("kkrepo"),
          setting("compat.go.nexusPlus.blobStorePrefix", "GO_KKREPO_BLOB_PREFIX")
              .orElse(""),
          setting("compat.go.nexusPlus.blobStoreAccessKey", "GO_KKREPO_BLOB_ACCESS_KEY")
              .orElse("minioadmin"),
          setting("compat.go.nexusPlus.blobStoreSecretKey", "GO_KKREPO_BLOB_SECRET_KEY")
              .orElse("minioadmin"));
    }

    Endpoint referenceEndpoint() {
      return new Endpoint(nexusBaseUrl, nexusRepository,
          Optional.of(nexusUsername), Optional.of(nexusPassword));
    }

    Endpoint candidateEndpoint() {
      return new Endpoint(nexusPlusBaseUrl, nexusPlusRepository, Optional.empty(), Optional.empty());
    }

    Endpoint referenceGroupEndpoint() {
      return new Endpoint(nexusBaseUrl, groupRepository,
          Optional.of(nexusUsername), Optional.of(nexusPassword));
    }

    Endpoint candidateGroupEndpoint() {
      return new Endpoint(nexusPlusBaseUrl, groupRepository, Optional.empty(), Optional.empty());
    }

    boolean candidateManagementConfigured() {
      return nexusPlusUsername.isPresent() && nexusPlusPassword.isPresent();
    }

    HttpRequest.Builder nexusAdminRequest(URI uri) {
      String token = Base64.getEncoder().encodeToString(
          (nexusUsername + ":" + nexusPassword).getBytes(StandardCharsets.UTF_8));
      return HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(30))
          .header("Authorization", "Basic " + token);
    }

    HttpRequest.Builder nexusPlusAdminRequest(URI uri) {
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
      if (nexusPlusUsername.isPresent() && nexusPlusPassword.isPresent()) {
        String token = Base64.getEncoder().encodeToString(
            (nexusPlusUsername.get() + ":" + nexusPlusPassword.get()).getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + token);
      }
      return builder;
    }

    boolean referenceReachable() {
      try {
        Exchange status = send(nexusAdminRequest(URI.create(nexusBaseUrl + "/service/rest/v1/status")).GET());
        return status.status() >= 200 && status.status() < 300;
      } catch (Exception e) {
        return false;
      }
    }

    boolean candidateReachable() {
      try {
        Exchange status = send(HttpRequest.newBuilder(URI.create(nexusPlusBaseUrl + "/")).GET());
        return status.status() >= 200 && status.status() < 400;
      } catch (Exception e) {
        return false;
      }
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

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
