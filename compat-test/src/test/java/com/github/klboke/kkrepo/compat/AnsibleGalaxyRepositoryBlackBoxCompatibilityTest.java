package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPathParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

/** Nexus 3.93+/Galaxy v3 black-box contract for Ansible collection repositories. */
class AnsibleGalaxyRepositoryBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void generatedFixtureHasCanonicalGalaxyManifestAndFileInventory() throws Exception {
    Fixture fixture = Fixture.create("compat", "fixture", "1.2.3", "primary");

    assertEquals("compat-fixture-1.2.3.tar.gz", fixture.filename());
    assertEquals(64, fixture.sha256().length());
    assertTrue(fixture.archive().length >= 1024, "Nexus enforces a 1 KiB collection minimum");
    Map<String, byte[]> entries = untar(fixture.archive());
    JsonNode manifest = JSON.readTree(entries.get("MANIFEST.json"));
    JsonNode files = JSON.readTree(entries.get("FILES.json"));
    assertEquals("compat", manifest.path("collection_info").path("namespace").asText());
    assertEquals("fixture", manifest.path("collection_info").path("name").asText());
    assertEquals("1.2.3", manifest.path("collection_info").path("version").asText());
    assertEquals(sha256(entries.get("FILES.json")),
        manifest.path("file_manifest_file").path("chksum_sha256").asText());
    assertTrue(files.path("files").toString().contains("README.md"));
  }

  @Test
  void hostedPublishReadDownloadAndHttpContractsMatchNexusWhenConfigured() throws Exception {
    Config config = configured();
    ensureRepositories(config);
    String collection = uniqueCollection("hosted");
    Fixture fixture = Fixture.create("compat", collection, "1.2.3", "hosted");

    assertDiscovery(config.nexusRepository(config.hosted()), config.nexusAuth());
    assertDiscovery(config.candidateRepository(config.hosted()), config.candidateAuth());

    Exchange nexusPublish = publish(
        config.nexusRepository(config.hosted()), config.nexusAuth(), fixture);
    Exchange candidatePublish = publish(
        config.candidateRepository(config.hosted()), config.candidateAuth(), fixture);
    assertEquals(nexusPublish.status(), candidatePublish.status(), "multipart publish status");
    assertEquals(202, candidatePublish.status(), candidatePublish.text());
    waitForTask(config.nexusRepository(config.hosted()), config.nexusAuth(), nexusPublish);
    waitForTask(config.candidateRepository(config.hosted()), config.candidateAuth(), candidatePublish);

    assertCollectionReads(
        config.nexusRepository(config.hosted()), config.nexusAuth(),
        config.candidateRepository(config.hosted()), config.candidateAuth(), fixture);
    assertCollectionReads(
        config.nexusRepository(config.group()), config.nexusAuth(),
        config.candidateRepository(config.group()), config.candidateAuth(), fixture);

    Exchange nexusDuplicate = publish(
        config.nexusRepository(config.hosted()), config.nexusAuth(), fixture);
    Exchange candidateDuplicate = publish(
        config.candidateRepository(config.hosted()), config.candidateAuth(), fixture);
    assertEquals(nexusDuplicate.status(), candidateDuplicate.status(), "immutable duplicate status");
    assertEquals(400, candidateDuplicate.status(), candidateDuplicate.text());

    Exchange nexusMissing = get(
        config.nexusRepository(config.hosted())
            + "/api/v3/collections/compat/missing_collection/",
        config.nexusAuth());
    Exchange candidateMissing = get(
        config.candidateRepository(config.hosted())
            + "/api/v3/collections/compat/missing_collection/",
        config.candidateAuth());
    assertEquals(nexusMissing.status(), candidateMissing.status(), "missing collection status");
    assertEquals(404, candidateMissing.status());

    Exchange nexusGroupPublish = publish(
        config.nexusRepository(config.group()), config.nexusAuth(),
        Fixture.create("compat", uniqueCollection("readonly"), "1.0.0", "group"));
    Exchange candidateGroupPublish = publish(
        config.candidateRepository(config.group()), config.candidateAuth(),
        Fixture.create("compat", uniqueCollection("readonly"), "1.0.0", "group"));
    assertEquals(404, nexusGroupPublish.status());
    assertEquals(nexusGroupPublish.status(), candidateGroupPublish.status(),
        "group publish route status");
  }

  @Test
  void groupKeepsMetadataChecksumAndArtifactOnTheFirstMatchingMember() throws Exception {
    Config config = configured();
    ensureRepositories(config);
    String collection = uniqueCollection("priority");
    Fixture first = Fixture.create("compat", collection, "2.0.0", "first member");
    Fixture second = Fixture.create("compat", collection, "2.0.0", "second member");
    assertFalse(first.sha256().equals(second.sha256()));

    publishAndWait(
        config.nexusRepository(config.hosted()), config.nexusAuth(), first);
    publishAndWait(
        config.nexusRepository(config.secondaryHosted()), config.nexusAuth(), second);
    publishAndWait(
        config.candidateRepository(config.hosted()), config.candidateAuth(), first);
    publishAndWait(
        config.candidateRepository(config.secondaryHosted()), config.candidateAuth(), second);

    for (Endpoint endpoint : List.of(
        new Endpoint(config.nexusRepository(config.group()), config.nexusAuth()),
        new Endpoint(config.candidateRepository(config.group()), config.candidateAuth()))) {
      JsonNode detail = json(get(
          endpoint.base() + "/api/v3/collections/compat/" + collection
              + "/versions/2.0.0/",
          endpoint.authorization()));
      assertEquals(first.sha256(), detail.path("artifact").path("sha256").asText());
      Exchange artifact = get(resolve(endpoint.base(), detail.path("download_url").asText()),
          endpoint.authorization());
      assertEquals(200, artifact.status(), artifact.text());
      assertArrayEquals(first.archive(), artifact.body());
    }
  }

  @Test
  void proxyDiscoveryVersionAndArtifactMatchTheSharedGalaxyUpstreamWhenConfigured()
      throws Exception {
    Config config = configured();
    ensureRepositories(config);
    String path = "api/v3/collections/" + config.proxyNamespace() + "/"
        + config.proxyCollection() + "/versions/" + config.proxyVersion() + "/";

    Exchange nexusDetailExchange = get(
        config.nexusRepository(config.proxy()) + "/" + path, config.nexusAuth());
    Exchange candidateDetailExchange = get(
        config.candidateRepository(config.proxy()) + "/" + path, config.candidateAuth());
    assertEquals(nexusDetailExchange.status(), candidateDetailExchange.status(),
        "proxy version detail status");
    assertEquals(200, candidateDetailExchange.status(), candidateDetailExchange.text());
    JsonNode nexusDetail = json(nexusDetailExchange);
    JsonNode candidateDetail = json(candidateDetailExchange);
    assertVersionIdentity(nexusDetail, config.proxyNamespace(), config.proxyCollection(),
        config.proxyVersion());
    assertVersionIdentity(candidateDetail, config.proxyNamespace(), config.proxyCollection(),
        config.proxyVersion());
    assertEquals(
        nexusDetail.path("artifact").path("sha256").asText(),
        candidateDetail.path("artifact").path("sha256").asText(),
        "proxy artifact SHA-256");

    Exchange nexusArtifact = get(resolve(
        config.nexusRepository(config.proxy()), nexusDetail.path("download_url").asText()),
        config.nexusAuth());
    Exchange candidateArtifact = get(resolve(
        config.candidateRepository(config.proxy()), candidateDetail.path("download_url").asText()),
        config.candidateAuth());
    assertEquals(200, candidateArtifact.status(), candidateArtifact.text());
    assertArrayEquals(nexusArtifact.body(), candidateArtifact.body());
    assertEquals(candidateDetail.path("artifact").path("sha256").asText(),
        sha256(candidateArtifact.body()));
  }

  private static void assertDiscovery(String repository, String authorization) throws Exception {
    JsonNode root = json(get(repository + "/", authorization));
    JsonNode fallback = json(get(repository + "/api/", authorization));
    assertEquals("api/v3/", root.path("available_versions").path("v3").asText());
    assertEquals(root.path("available_versions"), fallback.path("available_versions"));
  }

  private static void assertCollectionReads(
      String nexusRepository,
      String nexusAuth,
      String candidateRepository,
      String candidateAuth,
      Fixture fixture) throws Exception {
    String collectionPath = "api/v3/collections/" + fixture.namespace() + "/"
        + fixture.name() + "/";
    Exchange nexusCollection = get(nexusRepository + "/" + collectionPath, nexusAuth);
    Exchange candidateCollection = get(candidateRepository + "/" + collectionPath, candidateAuth);
    assertEquals(nexusCollection.status(), candidateCollection.status(), "collection status");
    assertEquals(200, candidateCollection.status(), candidateCollection.text());
    assertEquals(fixture.version(),
        json(candidateCollection).path("highest_version").path("version").asText());

    String listPath = collectionPath + "versions/?limit=1&offset=0";
    JsonNode nexusList = json(get(nexusRepository + "/" + listPath, nexusAuth));
    JsonNode candidateList = json(get(candidateRepository + "/" + listPath, candidateAuth));
    assertEquals(nexusList.path("meta").path("count").asInt(),
        candidateList.path("meta").path("count").asInt());
    assertEquals(fixture.version(), candidateList.path("data").path(0).path("version").asText());

    String detailPath = collectionPath + "versions/" + fixture.version() + "/";
    JsonNode nexusDetail = json(get(nexusRepository + "/" + detailPath, nexusAuth));
    JsonNode candidateDetail = json(get(candidateRepository + "/" + detailPath, candidateAuth));
    assertVersionIdentity(nexusDetail, fixture.namespace(), fixture.name(), fixture.version());
    assertVersionIdentity(candidateDetail, fixture.namespace(), fixture.name(), fixture.version());
    assertEquals(fixture.sha256(), nexusDetail.path("artifact").path("sha256").asText());
    assertEquals(fixture.sha256(), candidateDetail.path("artifact").path("sha256").asText());
    assertEquals(nexusDetail.path("metadata").path("dependencies"),
        candidateDetail.path("metadata").path("dependencies"));

    String nexusArtifactUrl = resolve(
        nexusRepository, nexusDetail.path("download_url").asText());
    String candidateArtifactUrl = resolve(
        candidateRepository, candidateDetail.path("download_url").asText());
    Exchange nexusArtifact = get(nexusArtifactUrl, nexusAuth);
    Exchange candidateArtifact = get(candidateArtifactUrl, candidateAuth);
    assertEquals(nexusArtifact.status(), candidateArtifact.status(), "artifact status");
    assertArrayEquals(fixture.archive(), nexusArtifact.body());
    assertArrayEquals(fixture.archive(), candidateArtifact.body());

    Exchange nexusHead = head(nexusArtifactUrl, nexusAuth);
    Exchange candidateHead = head(candidateArtifactUrl, candidateAuth);
    assertEquals(nexusHead.status(), candidateHead.status(), "artifact HEAD status");
    assertEquals(String.valueOf(fixture.archive().length), candidateHead.header("content-length"));
    assertFalse(candidateHead.header("etag").isBlank(), "artifact ETag");
    assertFalse(candidateHead.header("last-modified").isBlank(), "artifact Last-Modified");
    Exchange candidateConditional = send(HttpRequest.newBuilder(URI.create(candidateArtifactUrl))
        .header("Authorization", candidateAuth)
        .header("If-None-Match", candidateHead.header("etag"))
        .GET());
    assertEquals(304, candidateConditional.status(), "artifact conditional GET");
    assertEquals(0, candidateConditional.body().length);

    String longDetail = "api/v3/plugin/ansible/content/published/collections/index/"
        + fixture.namespace() + "/" + fixture.name() + "/versions/" + fixture.version() + "/";
    assertEquals(200, get(candidateRepository + "/" + longDetail, candidateAuth).status());
  }

  private static void assertVersionIdentity(
      JsonNode detail, String namespace, String name, String version) {
    assertEquals(namespace, detail.path("namespace").path("name").asText());
    assertEquals(name, detail.path("collection").path("name").asText());
    assertEquals(version, detail.path("version").asText());
    assertFalse(detail.path("artifact").path("sha256").asText().isBlank());
    assertFalse(detail.path("download_url").asText().isBlank());
  }

  private static void publishAndWait(
      String repository, String authorization, Fixture fixture) throws Exception {
    Exchange response = publish(repository, authorization, fixture);
    assertEquals(202, response.status(), response.text());
    waitForTask(repository, authorization, response);
  }

  private static Exchange publish(
      String repository, String authorization, Fixture fixture) throws Exception {
    String boundary = "----kkrepo-ansible-" + UUID.randomUUID();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    writeUtf8(body, "--" + boundary + "\r\n"
        + "Content-Disposition: form-data; name=\"sha256\"\r\n\r\n"
        + fixture.sha256() + "\r\n");
    writeUtf8(body, "--" + boundary + "\r\n"
        + "Content-Disposition: form-data; name=\"file\"; filename=\""
        + fixture.filename() + "\"\r\n"
        + "Content-Type: application/octet-stream\r\n\r\n");
    body.write(fixture.archive());
    writeUtf8(body, "\r\n--" + boundary + "--\r\n");
    return send(HttpRequest.newBuilder(URI.create(
            repository + "/api/v3/artifacts/collections/"))
        .header("Authorization", authorization)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
  }

  private static JsonNode waitForTask(
      String repository, String authorization, Exchange publish) throws Exception {
    String task = json(publish).path("task").asText();
    assertFalse(task.isBlank(), "publish task");
    String taskId = List.of(task.split("/")).reversed().stream()
        .filter(segment -> !segment.isBlank()).findFirst().orElseThrow();
    Exchange latest = null;
    for (int attempt = 0; attempt < 60; attempt++) {
      latest = get(repository + "/api/v3/imports/collections/" + taskId + "/", authorization);
      if (latest.status() == 200) {
        JsonNode state = json(latest);
        if (!state.path("finished_at").isMissingNode()
            && !state.path("finished_at").isNull()
            && !state.path("finished_at").asText().isBlank()) {
          assertEquals("completed", state.path("state").asText(), state.toString());
          return state;
        }
      }
      Thread.sleep(250L);
    }
    throw new AssertionError("Ansible import task did not finish: "
        + (latest == null ? "<none>" : latest.status() + " " + latest.text()));
  }

  private static Config configured() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(),
        "Set ANSIBLE_COMPAT_ENABLED=true to run Nexus 3.93+ Ansible compatibility");
    assumeTrue(reachable(config.nexusBase(), config.nexusAuth()),
        "Nexus Ansible reference is not reachable at " + config.nexusBase());
    assumeTrue(reachable(config.candidateBase(), config.candidateAuth()),
        "kkrepo candidate is not reachable at " + config.candidateBase());
    return config;
  }

  private static boolean reachable(String base, String authorization) {
    try {
      return send(HttpRequest.newBuilder(URI.create(base + "/service/rest/v1/status"))
          .header("Authorization", authorization).GET()).status() > 0;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static void ensureRepositories(Config config) throws Exception {
    ensureNexusHosted(config, config.hosted());
    ensureNexusHosted(config, config.secondaryHosted());
    ensureNexusProxy(config);
    ensureNexusGroup(config);
    ensureCandidateHosted(config, config.hosted());
    ensureCandidateHosted(config, config.secondaryHosted());
    ensureCandidateProxy(config);
    ensureCandidateGroup(config);
  }

  private static void ensureNexusHosted(Config config, String name) throws Exception {
    if (repositoryExists(config.nexusAdmin("/service/rest/v1/repositories"), name)) return;
    String body = """
        {"name":"%s","online":true,
         "storage":{"blobStoreName":"default","strictContentTypeValidation":true,
                    "writePolicy":"ALLOW_ONCE"}}
        """.formatted(name);
    assert2xx("create Nexus Ansible hosted", send(config.nexusAdmin(
        "/service/rest/v1/repositories/ansiblegalaxy/hosted")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))));
  }

  private static void ensureNexusProxy(Config config) throws Exception {
    if (repositoryExists(config.nexusAdmin("/service/rest/v1/repositories"), config.proxy())) return;
    String body = """
        {"name":"%s","online":true,
         "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
         "proxy":{"remoteUrl":"https://galaxy.ansible.com/",
                  "contentMaxAge":1440,"metadataMaxAge":60},
         "negativeCache":{"enabled":true,"timeToLive":5},
         "httpClient":{"blocked":false,"autoBlock":true}}
        """.formatted(config.proxy());
    assert2xx("create Nexus Ansible proxy", send(config.nexusAdmin(
        "/service/rest/v1/repositories/ansiblegalaxy/proxy")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))));
  }

  private static void ensureNexusGroup(Config config) throws Exception {
    if (repositoryExists(config.nexusAdmin("/service/rest/v1/repositories"), config.group())) return;
    String body = """
        {"name":"%s","online":true,
         "storage":{"blobStoreName":"default","strictContentTypeValidation":true},
         "group":{"memberNames":["%s","%s","%s"]}}
        """.formatted(
        config.group(), config.hosted(), config.secondaryHosted(), config.proxy());
    assert2xx("create Nexus Ansible group", send(config.nexusAdmin(
        "/service/rest/v1/repositories/ansiblegalaxy/group")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))));
  }

  private static void ensureCandidateHosted(Config config, String name) throws Exception {
    if (repositoryExists(config.candidateAdmin("/internal/repositories"), name)) return;
    String body = """
        {"name":"%s","recipe":"ansiblegalaxy-hosted","online":true,
         "blobStoreName":"default","strictContentTypeValidation":true,
         "hosted":{"writePolicy":"ALLOW_ONCE"}}
        """.formatted(name);
    assert2xx("create kkrepo Ansible hosted", send(config.candidateAdmin("/internal/repositories")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))));
  }

  private static void ensureCandidateProxy(Config config) throws Exception {
    if (repositoryExists(
        config.candidateAdmin("/internal/repositories"), config.proxy())) return;
    String body = """
        {"name":"%s","recipe":"ansiblegalaxy-proxy","online":true,
         "blobStoreName":"default","strictContentTypeValidation":true,
         "proxy":{"remoteUrl":"https://galaxy.ansible.com/",
                  "contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":60,
                  "negativeCacheEnabled":true,"negativeCacheTtlMinutes":5,
                  "autoBlock":true}}
        """.formatted(config.proxy());
    assert2xx("create kkrepo Ansible proxy", send(config.candidateAdmin("/internal/repositories")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))));
  }

  private static void ensureCandidateGroup(Config config) throws Exception {
    if (repositoryExists(
        config.candidateAdmin("/internal/repositories"), config.group())) return;
    String body = """
        {"name":"%s","recipe":"ansiblegalaxy-group","online":true,
         "blobStoreName":"default","strictContentTypeValidation":true,
         "group":{"memberNames":["%s","%s","%s"]}}
        """.formatted(
        config.group(), config.hosted(), config.secondaryHosted(), config.proxy());
    assert2xx("create kkrepo Ansible group", send(config.candidateAdmin("/internal/repositories")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))));
  }

  private static boolean repositoryExists(HttpRequest.Builder request, String name)
      throws Exception {
    JsonNode repositories = json(send(request.GET()));
    if (!repositories.isArray()) return false;
    for (JsonNode repository : repositories) {
      if (name.equals(repository.path("name").asText())) return true;
    }
    return false;
  }

  private static void assert2xx(String label, Exchange exchange) {
    assertTrue(exchange.status() >= 200 && exchange.status() < 300,
        label + " status=" + exchange.status() + " body=" + exchange.text());
  }

  private static Exchange get(String url, String authorization) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", authorization).GET());
  }

  private static Exchange head(String url, String authorization) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", authorization)
        .method("HEAD", HttpRequest.BodyPublishers.noBody()));
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        request.header("User-Agent", "kkrepo-ansible-compat-test/1")
            .timeout(Duration.ofSeconds(180)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(), response.headers().map());
  }

  private static JsonNode json(Exchange exchange) throws Exception {
    return JSON.readTree(exchange.body());
  }

  private static String resolve(String repositoryBase, String candidate) {
    URI value = URI.create(candidate);
    return value.isAbsolute()
        ? value.toASCIIString()
        : URI.create(repositoryBase + "/").resolve(value).toASCIIString();
  }

  private static String uniqueCollection(String prefix) {
    return (prefix + "_" + Long.toUnsignedString(System.nanoTime(), 36)).toLowerCase();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void writeUtf8(ByteArrayOutputStream output, String value) {
    output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, byte[]> untar(byte[] archive) throws Exception {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (GzipCompressorInputStream gzip = new GzipCompressorInputStream(
            new ByteArrayInputStream(archive));
         TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      for (TarArchiveEntry entry; (entry = tar.getNextTarEntry()) != null;) {
        if (entry.isFile()) entries.put(entry.getName(), tar.readAllBytes());
      }
    }
    return entries;
  }

  private static String basic(String username, String password) {
    return "Basic " + Base64.getEncoder().encodeToString(
        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static boolean boolSetting(String property, String env, boolean fallback) {
    return CompatDefaults.setting(property, env).map(Boolean::parseBoolean).orElse(fallback);
  }

  private record Exchange(int status, byte[] body, Map<String, List<String>> headers) {
    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    String header(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .flatMap(entry -> entry.getValue().stream())
          .findFirst().orElse("");
    }
  }

  private record Endpoint(String base, String authorization) {
  }

  private record Config(
      boolean enabled,
      String nexusBase,
      String candidateBase,
      String nexusAuth,
      String candidateAuth,
      String hosted,
      String secondaryHosted,
      String proxy,
      String group,
      String proxyNamespace,
      String proxyCollection,
      String proxyVersion) {
    static Config load() {
      String nexusUser = CompatDefaults.setting(
          "compat.ansible.nexus.username", "ANSIBLE_NEXUS_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusUsername().orElse(""));
      String nexusPassword = CompatDefaults.setting(
          "compat.ansible.nexus.password", "ANSIBLE_NEXUS_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPassword().orElse(""));
      String candidateUser = CompatDefaults.setting(
          "compat.ansible.kkrepo.username", "ANSIBLE_KKREPO_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusPlusUsername().orElse(""));
      String candidatePassword = CompatDefaults.setting(
          "compat.ansible.kkrepo.password", "ANSIBLE_KKREPO_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPlusPassword().orElse(""));
      return new Config(
          boolSetting("compat.ansible.enabled", "ANSIBLE_COMPAT_ENABLED", false),
          CompatDefaults.setting("compat.ansible.nexus.baseUrl", "ANSIBLE_NEXUS_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusBaseUrl().orElse("")),
          CompatDefaults.setting(
              "compat.ansible.kkrepo.baseUrl", "ANSIBLE_KKREPO_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusPlusBaseUrl().orElse("")),
          basic(nexusUser, nexusPassword),
          basic(candidateUser, candidatePassword),
          CompatDefaults.setting(
              "compat.ansible.hostedRepository", "ANSIBLE_COMPAT_HOSTED_REPOSITORY")
              .orElse("ansible-compat-hosted"),
          CompatDefaults.setting(
              "compat.ansible.secondaryHostedRepository",
              "ANSIBLE_COMPAT_SECONDARY_HOSTED_REPOSITORY")
              .orElse("ansible-compat-hosted-secondary"),
          CompatDefaults.setting(
              "compat.ansible.proxyRepository", "ANSIBLE_COMPAT_PROXY_REPOSITORY")
              .orElse("ansible-compat-proxy"),
          CompatDefaults.setting(
              "compat.ansible.groupRepository", "ANSIBLE_COMPAT_GROUP_REPOSITORY")
              .orElse("ansible-compat-group"),
          CompatDefaults.setting(
              "compat.ansible.proxyNamespace", "ANSIBLE_COMPAT_PROXY_NAMESPACE")
              .orElse("community"),
          CompatDefaults.setting(
              "compat.ansible.proxyCollection", "ANSIBLE_COMPAT_PROXY_COLLECTION")
              .orElse("general"),
          CompatDefaults.setting(
              "compat.ansible.proxyVersion", "ANSIBLE_COMPAT_PROXY_VERSION")
              .orElse("10.4.0"));
    }

    String nexusRepository(String name) {
      return nexusBase + "/repository/" + name;
    }

    String candidateRepository(String name) {
      return candidateBase + "/repository/" + name;
    }

    HttpRequest.Builder nexusAdmin(String path) {
      return HttpRequest.newBuilder(URI.create(nexusBase + path))
          .header("Authorization", nexusAuth);
    }

    HttpRequest.Builder candidateAdmin(String path) {
      return HttpRequest.newBuilder(URI.create(candidateBase + path))
          .header("Authorization", candidateAuth);
    }
  }

  private record Fixture(
      String namespace,
      String name,
      String version,
      String filename,
      String sha256,
      byte[] archive) {
    static Fixture create(
        String namespace, String name, String version, String marker) throws Exception {
      Map<String, byte[]> files = new LinkedHashMap<>();
      files.put("README.md", ("# " + namespace + "." + name + "\n" + marker + "\n")
          .getBytes(StandardCharsets.UTF_8));
      files.put("meta/runtime.yml", "requires_ansible: '>=2.15'\n"
          .getBytes(StandardCharsets.UTF_8));
      files.put("compat-padding.bin", deterministicPadding());
      List<Map<String, Object>> inventory = new java.util.ArrayList<>();
      inventory.add(Map.of("name", ".", "ftype", "dir"));
      inventory.add(Map.of("name", "meta", "ftype", "dir"));
      files.forEach((path, bytes) -> inventory.add(Map.of(
          "name", path,
          "ftype", "file",
          "chksum_type", "sha256",
          "chksum_sha256", AnsibleGalaxyRepositoryBlackBoxCompatibilityTest.sha256(bytes))));
      byte[] filesJson = JSON.writeValueAsBytes(Map.of("files", inventory, "format", 1));
      Map<String, Object> collectionInfo = new LinkedHashMap<>();
      collectionInfo.put("namespace", namespace);
      collectionInfo.put("name", name);
      collectionInfo.put("version", version);
      collectionInfo.put("authors", List.of("kkRepo Compatibility"));
      collectionInfo.put("description", "Ansible compatibility fixture " + marker);
      collectionInfo.put("license", List.of("Apache-2.0"));
      collectionInfo.put("tags", List.of("compat"));
      collectionInfo.put("dependencies", Map.of());
      byte[] manifestJson = JSON.writeValueAsBytes(Map.of(
          "collection_info", collectionInfo,
          "file_manifest_file", Map.of(
              "name", "FILES.json",
              "ftype", "file",
              "chksum_type", "sha256",
              "chksum_sha256",
              AnsibleGalaxyRepositoryBlackBoxCompatibilityTest.sha256(filesJson)),
          "format", 1));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
           TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
        addFile(tar, "MANIFEST.json", manifestJson);
        addFile(tar, "FILES.json", filesJson);
        TarArchiveEntry meta = new TarArchiveEntry("meta/");
        meta.setMode(0755);
        tar.putArchiveEntry(meta);
        tar.closeArchiveEntry();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
          addFile(tar, file.getKey(), file.getValue());
        }
      }
      byte[] archive = output.toByteArray();
      return new Fixture(
          namespace,
          name,
          version,
          AnsibleGalaxyPathParser.canonicalFilename(namespace, name, version),
          AnsibleGalaxyRepositoryBlackBoxCompatibilityTest.sha256(archive),
          archive);
    }

    private static byte[] deterministicPadding() {
      byte[] bytes = new byte[4096];
      int state = 0x6d2b79f5;
      for (int index = 0; index < bytes.length; index++) {
        state ^= state << 13;
        state ^= state >>> 17;
        state ^= state << 5;
        bytes[index] = (byte) state;
      }
      return bytes;
    }

    private static void addFile(TarArchiveOutputStream tar, String name, byte[] bytes)
        throws Exception {
      TarArchiveEntry entry = new TarArchiveEntry(name);
      entry.setMode(0644);
      entry.setSize(bytes.length);
      tar.putArchiveEntry(entry);
      tar.write(bytes);
      tar.closeArchiveEntry();
    }
  }
}
