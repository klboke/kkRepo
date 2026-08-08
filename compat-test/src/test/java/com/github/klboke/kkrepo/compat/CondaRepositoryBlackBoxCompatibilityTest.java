package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;

/** Opt-in Nexus/kkrepo black-box contract for Conda hosted, proxy, and group channels. */
class CondaRepositoryBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void generatedFixturesFollowLegacyAndV2ContainerLayouts() throws Exception {
    Fixture legacy = Fixture.legacy("compat_fixture", "1.2.3", "py312_0", "linux-64", "v1");
    Fixture modern = Fixture.modern("compat_fixture", "2.0", "py312_1", "linux-64", "v2");

    assertEquals("compat_fixture-1.2.3-py312_0.tar.bz2", legacy.filename());
    assertEquals("compat_fixture-2.0-py312_1.conda", modern.filename());
    assertEquals("compat_fixture", legacyIndex(legacy.archive()).path("name").asText());
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(modern.archive()))) {
      List<String> names = zipEntries(zip);
      assertTrue(names.contains("metadata.json"));
      assertTrue(names.contains("info-compat_fixture-2.0-py312_1.tar.zst"));
      assertTrue(names.contains("pkg-compat_fixture-2.0-py312_1.tar.zst"));
    }
    assertEquals(64, legacy.sha256().length());
    assertFalse(legacy.sha256().equals(modern.sha256()));
  }

  @Test
  void hostedMetadataPackageHttpAndGroupPriorityMatchNexusWhenConfigured() throws Exception {
    Config config = configured();
    ensureRepositories(config);
    String name = "compat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    Fixture primary = Fixture.legacy(name, "1.0", "0", "linux-64", "first-member");
    Fixture secondary = Fixture.legacy(name, "1.0", "0", "linux-64", "second-member");
    Fixture modern = Fixture.modern(name, "2.0", "py312_1", "linux-64", "modern");

    for (Endpoint endpoint : List.of(
        config.nexus(config.hosted()), config.candidate(config.hosted()))) {
      assertEquals(201, put(endpoint, "team/release/linux-64/" + primary.filename(), primary).status());
      assertEquals(201, put(endpoint, "team/release/linux-64/" + modern.filename(), modern).status());
    }
    for (Endpoint endpoint : List.of(
        config.nexus(config.secondaryHosted()), config.candidate(config.secondaryHosted()))) {
      assertEquals(201, put(endpoint, "team/release/linux-64/" + secondary.filename(), secondary).status());
    }

    JsonNode nexus = waitForRepodata(config.nexus(config.hosted()), "team/release", primary.filename());
    JsonNode candidate = waitForRepodata(
        config.candidate(config.hosted()), "team/release", primary.filename());
    assertPackageRecord(candidate, "packages", primary);
    assertPackageRecord(candidate, "packages.conda", modern);
    assertEquals(
        nexus.path("packages").path(primary.filename()).path("sha256").asText(),
        candidate.path("packages").path(primary.filename()).path("sha256").asText());

    Endpoint candidateHosted = config.candidate(config.hosted());
    JsonNode current = json(get(
        candidateHosted, "team/release/linux-64/current_repodata.json"));
    assertEquals(candidate, current);
    assertEquals(candidate, JSON.readTree(decompressBzip2(get(
        candidateHosted, "team/release/linux-64/repodata.json.bz2").body())));
    assertEquals(candidate, JSON.readTree(decompressZstd(get(
        candidateHosted, "team/release/linux-64/repodata.json.zst").body())));
    assertEquals(200, get(candidateHosted, "team/release/noarch/repodata.json").status());

    assertBrowseDirectory(config, config.hosted(), "team/release/linux-64", name);
    assertBrowseDirectory(config, config.hosted(), "team/release/linux-64/" + name, "1.0");
    assertBrowseAsset(
        config,
        config.hosted(),
        "team/release/linux-64/" + name + "/1.0",
        primary.filename());
    assertBrowseAsset(
        config,
        config.group(),
        "team/release/linux-64/" + name + "/1.0",
        primary.filename());

    JsonNode nexusGrouped = waitForRepodata(
        config.nexus(config.group()), "team/release", primary.filename());
    String nexusGroupedSha256 = nexusGrouped.path("packages")
        .path(primary.filename()).path("sha256").asText();
    assertTrue(
        nexusGroupedSha256.equals(primary.sha256())
            || nexusGroupedSha256.equals(secondary.sha256()),
        "Nexus group metadata should select one configured member");
    // Nexus 3.94 normalizes nested hosted uploads to the root channel. It may also render the
    // later member's checksum in group repodata while serving the first member's package bytes.
    // Record that reference behavior without making kkrepo reproduce the checksum mismatch.
    Exchange nexusGroupPackage = get(
        config.nexus(config.group()), "linux-64/" + primary.filename());
    assertEquals(200, nexusGroupPackage.status());
    assertArrayEquals(primary.archive(), nexusGroupPackage.body());

    JsonNode candidateGrouped = waitForRepodata(
        config.candidate(config.group()), "team/release", primary.filename());
    assertEquals(primary.sha256(), candidateGrouped.path("packages")
        .path(primary.filename()).path("sha256").asText());
    Exchange candidateGroupPackage = get(
        config.candidate(config.group()), "team/release/linux-64/" + primary.filename());
    assertEquals(200, candidateGroupPackage.status());
    assertArrayEquals(primary.archive(), candidateGroupPackage.body());

    String packagePath = "team/release/linux-64/" + modern.filename();
    Exchange head = head(candidateHosted, packagePath);
    assertEquals(200, head.status());
    assertEquals(String.valueOf(modern.archive().length), head.header("content-length"));
    assertFalse(head.header("etag").isBlank());
    Exchange conditional = send(HttpRequest.newBuilder(URI.create(
            candidateHosted.base() + "/" + packagePath))
        .header("Authorization", candidateHosted.authorization())
        .header("If-None-Match", head.header("etag"))
        .GET());
    assertEquals(304, conditional.status());
    assertEquals(0, conditional.body().length);

    Exchange nexusProxyMetadata = get(config.nexus(config.proxy()), "noarch/repodata.json");
    Exchange candidateProxyMetadata = get(
        config.candidate(config.proxy()), "noarch/repodata.json");
    assertEquals(nexusProxyMetadata.status(), candidateProxyMetadata.status());
    assertEquals(200, candidateProxyMetadata.status());
    ProxyPackage proxyPackage = smallestProxyPackage(json(candidateProxyMetadata));
    Exchange nexusProxyPackage = get(
        config.nexus(config.proxy()), "noarch/" + proxyPackage.filename());
    Exchange candidateProxyPackage = get(
        config.candidate(config.proxy()), "noarch/" + proxyPackage.filename());
    assertEquals(nexusProxyPackage.status(), candidateProxyPackage.status());
    assertEquals(200, candidateProxyPackage.status());
    assertEquals(proxyPackage.sha256(), sha256(candidateProxyPackage.body()));
    assertArrayEquals(nexusProxyPackage.body(), candidateProxyPackage.body());
    assertBrowseDirectory(config, config.proxy(), "noarch", proxyPackage.name());
    assertBrowseDirectory(
        config, config.proxy(), "noarch/" + proxyPackage.name(), proxyPackage.version());
    assertBrowseAsset(
        config,
        config.proxy(),
        "noarch/" + proxyPackage.name() + "/" + proxyPackage.version(),
        proxyPackage.filename());
  }

  private static void assertBrowseDirectory(
      Config config, String repository, String parent, String name) throws Exception {
    JsonNode entry = waitForBrowseEntry(config, repository, parent, name);
    assertFalse(entry.path("leaf").asBoolean(),
        () -> repository + " should expose " + name + " as a Browse directory under " + parent);
  }

  private static void assertBrowseAsset(
      Config config, String repository, String parent, String filename) throws Exception {
    JsonNode entry = waitForBrowseEntry(config, repository, parent, filename);
    assertTrue(entry.path("leaf").asBoolean(),
        () -> repository + " should expose the package archive directly under " + parent);
  }

  private static JsonNode waitForBrowseEntry(
      Config config, String repository, String parent, String name) throws Exception {
    Exchange latest = null;
    for (int attempt = 0; attempt < 80; attempt++) {
      latest = send(config.candidateAdmin(
          "/internal/browse/" + encode(repository) + "?path=" + encode(parent)).GET());
      if (latest.status() == 200) {
        for (JsonNode entry : json(latest).path("entries")) {
          if (name.equals(entry.path("name").asText())) return entry;
        }
      }
      Thread.sleep(250L);
    }
    throw new AssertionError("Browse entry did not appear: repository=" + repository
        + " parent=" + parent + " name=" + name + " response="
        + (latest == null ? "<none>" : latest.status() + " " + latest.text()));
  }

  private static void assertPackageRecord(JsonNode root, String collection, Fixture fixture) {
    JsonNode record = root.path(collection).path(fixture.filename());
    assertEquals(fixture.name(), record.path("name").asText());
    assertEquals(fixture.version(), record.path("version").asText());
    assertEquals(fixture.build(), record.path("build").asText());
    assertEquals(fixture.archive().length, record.path("size").asLong());
    assertEquals(fixture.sha256(), record.path("sha256").asText());
    assertFalse(record.has("base_url"));
  }

  private static JsonNode waitForRepodata(Endpoint endpoint, String channel, String filename)
      throws Exception {
    Exchange latest = null;
    for (int attempt = 0; attempt < 80; attempt++) {
      latest = get(endpoint, channel + "/linux-64/repodata.json");
      if (latest.status() == 200) {
        JsonNode value = json(latest);
        if (value.path("packages").has(filename) || value.path("packages.conda").has(filename)) {
          return value;
        }
      }
      Thread.sleep(250L);
    }
    throw new AssertionError("Conda repodata did not include " + filename + ": "
        + (latest == null ? "<none>" : latest.status() + " " + latest.text()));
  }

  private static ProxyPackage smallestProxyPackage(JsonNode repodata) {
    ProxyPackage selected = null;
    for (String collection : List.of("packages", "packages.conda")) {
      var fields = repodata.path(collection).fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        String checksum = entry.getValue().path("sha256").asText();
        long size = entry.getValue().path("size").asLong(Long.MAX_VALUE);
        if (checksum.matches("[0-9a-f]{64}")
            && size > 0 && size <= 5L * 1024 * 1024
            && (selected == null || size < selected.size())) {
          selected = new ProxyPackage(
              entry.getKey(),
              entry.getValue().path("name").asText(),
              entry.getValue().path("version").asText(),
              checksum,
              size);
        }
      }
    }
    if (selected == null) {
      throw new AssertionError("No bounded SHA-256 package in proxy repodata");
    }
    return selected;
  }

  private static Exchange put(Endpoint endpoint, String path, Fixture fixture) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(endpoint.base() + "/" + path))
        .header("Authorization", endpoint.authorization())
        .header("Content-Type", fixture.modern()
            ? "application/vnd.conda.package.v2" : "application/x-tar")
        .PUT(HttpRequest.BodyPublishers.ofByteArray(fixture.archive())));
  }

  private static Exchange get(Endpoint endpoint, String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(endpoint.base() + "/" + path))
        .header("Authorization", endpoint.authorization()).GET());
  }

  private static Exchange head(Endpoint endpoint, String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(endpoint.base() + "/" + path))
        .header("Authorization", endpoint.authorization())
        .method("HEAD", HttpRequest.BodyPublishers.noBody()));
  }

  private static Config configured() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set CONDA_COMPAT_ENABLED=true to run Conda compatibility");
    assumeTrue(reachable(config.nexusBase(), config.nexusAuth()),
        "Nexus Conda reference is not reachable at " + config.nexusBase());
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
         "storage":{"blobStoreName":"%s","strictContentTypeValidation":true,
                    "writePolicy":"ALLOW"}}
        """.formatted(name, config.nexusBlobStore());
    assert2xx(send(config.nexusAdmin("/service/rest/v1/repositories/conda/hosted")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))));
  }

  private static void ensureNexusProxy(Config config) throws Exception {
    if (repositoryExists(config.nexusAdmin("/service/rest/v1/repositories"), config.proxy())) return;
    String body = """
        {"name":"%s","online":true,
         "storage":{"blobStoreName":"%s","strictContentTypeValidation":true},
         "proxy":{"remoteUrl":"https://repo.anaconda.com/pkgs/main/",
                  "contentMaxAge":1440,"metadataMaxAge":60},
         "negativeCache":{"enabled":true,"timeToLive":5},
         "httpClient":{"blocked":false,"autoBlock":true}}
        """.formatted(config.proxy(), config.nexusBlobStore());
    assert2xx(send(config.nexusAdmin("/service/rest/v1/repositories/conda/proxy")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))));
  }

  private static void ensureNexusGroup(Config config) throws Exception {
    if (repositoryExists(config.nexusAdmin("/service/rest/v1/repositories"), config.group())) return;
    String body = """
        {"name":"%s","online":true,
         "storage":{"blobStoreName":"%s","strictContentTypeValidation":true},
         "group":{"memberNames":["%s","%s","%s"]}}
        """.formatted(
            config.group(), config.nexusBlobStore(), config.hosted(),
            config.secondaryHosted(), config.proxy());
    assert2xx(send(config.nexusAdmin("/service/rest/v1/repositories/conda/group")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))));
  }

  private static void ensureCandidateHosted(Config config, String name) throws Exception {
    if (repositoryExists(config.candidateAdmin("/internal/repositories"), name)) return;
    String body = """
        {"name":"%s","recipe":"conda-hosted","online":true,
         "blobStoreName":"%s","strictContentTypeValidation":true,
         "hosted":{"writePolicy":"ALLOW"}}
        """.formatted(name, config.candidateBlobStore());
    assert2xx(send(config.candidateAdmin("/internal/repositories")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))));
  }

  private static void ensureCandidateProxy(Config config) throws Exception {
    if (repositoryExists(config.candidateAdmin("/internal/repositories"), config.proxy())) return;
    String body = """
        {"name":"%s","recipe":"conda-proxy","online":true,
         "blobStoreName":"%s","strictContentTypeValidation":true,
         "proxy":{"remoteUrl":"https://repo.anaconda.com/pkgs/main/",
                  "contentMaxAgeMinutes":1440,"metadataMaxAgeMinutes":60,
                  "negativeCacheEnabled":true,"negativeCacheTtlMinutes":5,"autoBlock":true}}
        """.formatted(config.proxy(), config.candidateBlobStore());
    assert2xx(send(config.candidateAdmin("/internal/repositories")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))));
  }

  private static void ensureCandidateGroup(Config config) throws Exception {
    if (repositoryExists(config.candidateAdmin("/internal/repositories"), config.group())) return;
    String body = """
        {"name":"%s","recipe":"conda-group","online":true,
         "blobStoreName":"%s","strictContentTypeValidation":true,
         "group":{"memberNames":["%s","%s","%s"]}}
        """.formatted(
            config.group(), config.candidateBlobStore(), config.hosted(),
            config.secondaryHosted(), config.proxy());
    assert2xx(send(config.candidateAdmin("/internal/repositories")
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

  private static void assert2xx(Exchange exchange) {
    assertTrue(exchange.status() >= 200 && exchange.status() < 300,
        () -> "repository creation status=" + exchange.status() + " body=" + exchange.text());
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        request.header("User-Agent", "kkrepo-conda-compat-test/1")
            .timeout(Duration.ofSeconds(180)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(), response.headers().map());
  }

  private static JsonNode json(Exchange exchange) throws Exception {
    return JSON.readTree(exchange.body());
  }

  private static JsonNode legacyIndex(byte[] archive) throws Exception {
    try (BZip2CompressorInputStream bzip2 = new BZip2CompressorInputStream(
            new ByteArrayInputStream(archive));
         TarArchiveInputStream tar = new TarArchiveInputStream(bzip2)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (entry.isFile() && entry.getName().equals("info/index.json")) {
          return JSON.readTree(tar.readAllBytes());
        }
      }
    }
    throw new AssertionError("legacy fixture lacks info/index.json");
  }

  private static List<String> zipEntries(ZipInputStream zip) throws Exception {
    java.util.ArrayList<String> names = new java.util.ArrayList<>();
    for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) names.add(entry.getName());
    return List.copyOf(names);
  }

  private static byte[] decompressBzip2(byte[] bytes) throws Exception {
    try (BZip2CompressorInputStream input = new BZip2CompressorInputStream(
        new ByteArrayInputStream(bytes))) {
      return input.readAllBytes();
    }
  }

  private static byte[] decompressZstd(byte[] bytes) throws Exception {
    try (ZstdInputStream input = new ZstdInputStream(new ByteArrayInputStream(bytes))) {
      return input.readAllBytes();
    }
  }

  private static byte[] tar(List<TarFile> files) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
      for (TarFile file : files) {
        TarArchiveEntry entry = new TarArchiveEntry(file.name());
        entry.setSize(file.bytes().length);
        tar.putArchiveEntry(entry);
        tar.write(file.bytes());
        tar.closeArchiveEntry();
      }
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private static byte[] zstd(byte[] bytes) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZstdOutputStream compressed = new ZstdOutputStream(output)) {
      compressed.write(bytes);
    }
    return output.toByteArray();
  }

  private static void putStored(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
    CRC32 crc = new CRC32();
    crc.update(bytes);
    ZipEntry entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(bytes.length);
    entry.setCompressedSize(bytes.length);
    entry.setCrc(crc.getValue());
    zip.putNextEntry(entry);
    zip.write(bytes);
    zip.closeEntry();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String basic(String username, String password) {
    return "Basic " + Base64.getEncoder().encodeToString(
        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

  private record ProxyPackage(String filename, String name, String version, String sha256, long size) {
  }

  private record Config(
      boolean enabled,
      String nexusBase,
      String candidateBase,
      String nexusAuth,
      String candidateAuth,
      String nexusBlobStore,
      String candidateBlobStore,
      String hosted,
      String secondaryHosted,
      String proxy,
      String group) {
    static Config load() {
      String nexusUser = CompatDefaults.setting(
          "compat.conda.nexus.username", "CONDA_NEXUS_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusUsername().orElse(""));
      String nexusPassword = CompatDefaults.setting(
          "compat.conda.nexus.password", "CONDA_NEXUS_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPassword().orElse(""));
      String candidateUser = CompatDefaults.setting(
          "compat.conda.kkrepo.username", "CONDA_KKREPO_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusPlusUsername().orElse(""));
      String candidatePassword = CompatDefaults.setting(
          "compat.conda.kkrepo.password", "CONDA_KKREPO_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPlusPassword().orElse(""));
      return new Config(
          CompatDefaults.setting("compat.conda.enabled", "CONDA_COMPAT_ENABLED")
              .map(Boolean::parseBoolean).orElse(false),
          CompatDefaults.setting("compat.conda.nexus.baseUrl", "CONDA_NEXUS_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusBaseUrl().orElse("")),
          CompatDefaults.setting("compat.conda.kkrepo.baseUrl", "CONDA_KKREPO_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusPlusBaseUrl().orElse("")),
          basic(nexusUser, nexusPassword),
          basic(candidateUser, candidatePassword),
          CompatDefaults.setting(
              "compat.conda.nexus.blobStore", "CONDA_NEXUS_COMPAT_BLOB_STORE")
              .orElse("default"),
          CompatDefaults.setting(
              "compat.conda.kkrepo.blobStore", "CONDA_KKREPO_COMPAT_BLOB_STORE")
              .orElse("default"),
          CompatDefaults.setting("compat.conda.hostedRepository", "CONDA_COMPAT_HOSTED_REPOSITORY")
              .orElse("conda-compat-hosted"),
          CompatDefaults.setting(
              "compat.conda.secondaryHostedRepository",
              "CONDA_COMPAT_SECONDARY_HOSTED_REPOSITORY")
              .orElse("conda-compat-hosted-secondary"),
          CompatDefaults.setting("compat.conda.proxyRepository", "CONDA_COMPAT_PROXY_REPOSITORY")
              .orElse("conda-compat-proxy"),
          CompatDefaults.setting("compat.conda.groupRepository", "CONDA_COMPAT_GROUP_REPOSITORY")
              .orElse("conda-compat-group"));
    }

    Endpoint nexus(String repository) {
      return new Endpoint(nexusBase + "/repository/" + repository, nexusAuth);
    }

    Endpoint candidate(String repository) {
      return new Endpoint(candidateBase + "/repository/" + repository, candidateAuth);
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
      String name,
      String version,
      String build,
      String subdir,
      String filename,
      byte[] archive,
      String sha256,
      boolean modern) {
    static Fixture legacy(
        String name, String version, String build, String subdir, String marker) throws Exception {
      byte[] index = index(name, version, build, subdir);
      ByteArrayOutputStream archive = new ByteArrayOutputStream();
      try (BZip2CompressorOutputStream bzip2 = new BZip2CompressorOutputStream(archive);
           TarArchiveOutputStream tar = new TarArchiveOutputStream(bzip2)) {
        for (TarFile file : List.of(
            new TarFile("info/index.json", index),
            new TarFile("share/compat.txt", marker.getBytes(StandardCharsets.UTF_8)))) {
          TarArchiveEntry entry = new TarArchiveEntry(file.name());
          entry.setSize(file.bytes().length);
          tar.putArchiveEntry(entry);
          tar.write(file.bytes());
          tar.closeArchiveEntry();
        }
        tar.finish();
      }
      byte[] bytes = archive.toByteArray();
      return new Fixture(name, version, build, subdir,
          name + "-" + version + "-" + build + ".tar.bz2", bytes,
          CondaRepositoryBlackBoxCompatibilityTest.sha256(bytes), false);
    }

    static Fixture modern(
        String name, String version, String build, String subdir, String marker) throws Exception {
      String identity = name + "-" + version + "-" + build;
      byte[] info = zstd(tar(List.of(new TarFile(
          "info/index.json", index(name, version, build, subdir)))));
      byte[] pkg = zstd(tar(List.of(new TarFile(
          "share/compat.txt", marker.getBytes(StandardCharsets.UTF_8)))));
      ByteArrayOutputStream archive = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(archive)) {
        putStored(zip, "metadata.json",
            "{\"conda_pkg_format_version\":2}".getBytes(StandardCharsets.UTF_8));
        putStored(zip, "info-" + identity + ".tar.zst", info);
        putStored(zip, "pkg-" + identity + ".tar.zst", pkg);
      }
      byte[] bytes = archive.toByteArray();
      return new Fixture(name, version, build, subdir,
          identity + ".conda", bytes,
          CondaRepositoryBlackBoxCompatibilityTest.sha256(bytes), true);
    }

    private static byte[] index(
        String name, String version, String build, String subdir) throws Exception {
      return JSON.writeValueAsBytes(Map.of(
          "name", name,
          "version", version,
          "build", build,
          "build_number", build.equals("0") ? 0 : 1,
          "subdir", subdir,
          "depends", List.of(),
          "license", "BSD-3-Clause"));
    }

    @Override
    public byte[] archive() {
      return archive.clone();
    }
  }

  private record TarFile(String name, byte[] bytes) {
    TarFile {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
