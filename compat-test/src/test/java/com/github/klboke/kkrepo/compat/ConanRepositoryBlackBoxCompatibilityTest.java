package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.conan.ConanManifest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

/** Opt-in Conan 2 hosted contract against a live Nexus reference and kkRepo candidate. */
class ConanRepositoryBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void generatedFixtureUsesConanManifestRevisionIdentity() throws Exception {
    Fixture fixture = Fixture.create();

    assertEquals(fixture.rrev(), ConanManifest.parse(fixture.recipeManifest()).summaryHash());
    assertEquals(fixture.prev(), ConanManifest.parse(fixture.packageManifest()).summaryHash());
    assertEquals(Set.of("conanfile.py"), archiveFiles(fixture.recipeArchive()).keySet());
    assertEquals(
        Set.of("include/kkrepo_conan_fixture.h"),
        archiveFiles(fixture.packageArchive()).keySet());
    assertEquals(40, fixture.packageId().length());
  }

  @Test
  void hostedWireBehaviorAndWriteTimeBrowseProjectionMatchNexusWhenConfigured()
      throws Exception {
    Config config = configured();
    Fixture fixture = Fixture.create();
    String name = "kkrepo_conan_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    Reference reference = new Reference(
        name, "1.0.0", "kkrepo", "stable", fixture.rrev(), fixture.packageId(), fixture.prev());
    String nexusToken = configuredToken(
        config.nexusToken(), config.nexus(), config.nexusAuthorization());
    String candidateToken = configuredToken(
        config.candidateToken(), config.candidate(), config.candidateAuthorization());

    try {
      for (Endpoint endpoint : List.of(
          config.nexus().bearer(nexusToken), config.candidate().bearer(candidateToken))) {
        assertPut(endpoint, reference.recipeFile("conan_export.tgz"), fixture.recipeArchive());
        assertPut(endpoint, reference.recipeFile("conanmanifest.txt"), fixture.recipeManifest());
        assertPut(endpoint, reference.packageFile("conan_package.tgz"), fixture.packageArchive());
        assertPut(endpoint, reference.packageFile("conaninfo.txt"), fixture.conanInfo());
        assertPut(endpoint, reference.packageFile("conanmanifest.txt"), fixture.packageManifest());
      }

      assertProtocol(config.nexus().bearer(nexusToken), reference, fixture);
      assertProtocol(config.candidate().bearer(candidateToken), reference, fixture);
      assertBrowse(config, reference);
    } finally {
      delete(config.nexus().bearer(nexusToken), reference.recipeRoot());
      delete(config.candidate().bearer(candidateToken), reference.recipeRoot());
    }
  }

  private static void assertProtocol(
      Endpoint endpoint, Reference reference, Fixture fixture) throws Exception {
    Exchange ping = get(endpoint, "v1/ping");
    assertEquals(200, ping.status());
    assertEquals(0, ping.body().length);
    assertEquals("revisions", ping.header("x-conan-server-capabilities"));

    JsonNode search = json(get(endpoint, "v2/conans/search?q=" + encode(reference.recipe())));
    assertTrue(values(search.path("results")).contains(reference.recipe()));
    assertEquals(reference.rrev(), json(get(endpoint, reference.recipeRoot() + "/latest"))
        .path("revision").asText());
    assertTrue(revisions(json(get(endpoint, reference.recipeRoot() + "/revisions")))
        .contains(reference.rrev()));
    assertEquals(Set.of("conan_export.tgz", "conanmanifest.txt"),
        files(json(get(endpoint, reference.rrevRoot() + "/files"))));
    assertEquals(archiveFiles(fixture.recipeArchive()), archiveFiles(
        get(endpoint, reference.recipeFile("conan_export.tgz")).body()));

    JsonNode packages = json(get(
        endpoint, reference.rrevRoot() + "/search?list_only=True"));
    assertEquals("", packages.path(reference.packageId()).path("content").asText());
    assertEquals(reference.prev(), json(get(endpoint, reference.packageRoot() + "/latest"))
        .path("revision").asText());
    assertTrue(revisions(json(get(endpoint, reference.packageRoot() + "/revisions")))
        .contains(reference.prev()));
    assertEquals(Set.of("conan_package.tgz", "conaninfo.txt", "conanmanifest.txt"),
        files(json(get(endpoint, reference.prevRoot() + "/files"))));
    assertEquals(archiveFiles(fixture.packageArchive()), archiveFiles(
        get(endpoint, reference.packageFile("conan_package.tgz")).body()));

    Exchange range = send(endpoint.request(reference.packageFile("conan_package.tgz"))
        .header("Range", "bytes=0-63").GET());
    assertEquals(206, range.status());
    assertArrayEquals(
        java.util.Arrays.copyOf(fixture.packageArchive(), 64), range.body());
    assertEquals(404, send(endpoint.request(reference.packageFile("conan_package.tgz"))
        .method("HEAD", HttpRequest.BodyPublishers.noBody())).status());
  }

  private static void assertBrowse(Config config, Reference reference) throws Exception {
    String revision = reference.user() + "/" + reference.name() + "/" + reference.version()
        + "/" + reference.channel() + "#" + reference.rrev();
    Set<String> recipe = browseNames(config, revision);
    assertTrue(recipe.containsAll(Set.of("conan_export.tgz", "conanmanifest.txt", "packages")));
    assertFalse(recipe.contains("conans"));
    assertFalse(recipe.contains(".conan"));

    String packageFiles = revision + "/packages/" + reference.packageId()
        + "/revisions/" + reference.prev() + "/files";
    assertEquals(
        Set.of("conan_package.tgz", "conaninfo.txt", "conanmanifest.txt"),
        browseNames(config, packageFiles));
  }

  private static Set<String> browseNames(Config config, String path) throws Exception {
    Exchange last = null;
    for (int attempt = 0; attempt < 40; attempt++) {
      last = send(HttpRequest.newBuilder(URI.create(
              config.candidateBase() + "/internal/browse/" + encode(config.candidateRepository())
                  + "?path=" + encode(path)))
          .GET(), config.candidateAuthorization());
      if (last.status() == 200) {
        Set<String> names = new java.util.LinkedHashSet<>();
        json(last).path("entries").forEach(entry -> names.add(entry.path("name").asText()));
        if (!names.isEmpty()) return Set.copyOf(names);
      }
      Thread.sleep(100L);
    }
    throw new AssertionError("Conan Browse path did not appear: " + path + " response="
        + (last == null ? "<none>" : last.status() + " " + last.text()));
  }

  private static String authenticate(Endpoint endpoint, String basic) throws Exception {
    Exchange response = send(HttpRequest.newBuilder(URI.create(
            endpoint.base() + "/v2/users/authenticate"))
        .header("Authorization", basic).GET());
    assertEquals(200, response.status(), () -> "Conan authentication failed: " + response.text());
    String token = response.text().trim();
    assertFalse(token.isBlank());
    return token;
  }

  private static String configuredToken(String token, Endpoint endpoint, String basic)
      throws Exception {
    return token == null || token.isBlank() ? authenticate(endpoint, basic) : token.trim();
  }

  private static Exchange put(Endpoint endpoint, String path, byte[] body) throws Exception {
    return send(endpoint.request(path)
        .header("Content-Type", "application/octet-stream")
        .header("X-Checksum-Sha1", sha1(body))
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
  }

  private static void assertPut(Endpoint endpoint, String path, byte[] body) throws Exception {
    Exchange response = put(endpoint, path, body);
    assertEquals(200, response.status(),
        () -> "Conan PUT failed for " + endpoint.base() + "/" + path + ": " + response.text());
  }

  private static Exchange get(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).GET());
  }

  private static void delete(Endpoint endpoint, String path) {
    try {
      send(endpoint.request(path).DELETE());
    } catch (Exception ignored) {
    }
  }

  private static JsonNode json(Exchange exchange) throws Exception {
    assertEquals(200, exchange.status(), () -> exchange.status() + " " + exchange.text());
    return JSON.readTree(exchange.body());
  }

  private static Set<String> files(JsonNode response) {
    Set<String> result = new java.util.LinkedHashSet<>();
    JsonNode files = response.path("files");
    if (files.isArray()) files.forEach(value -> result.add(value.asText()));
    else files.fieldNames().forEachRemaining(result::add);
    return Set.copyOf(result);
  }

  private static List<String> revisions(JsonNode response) {
    List<String> result = new ArrayList<>();
    response.path("revisions").forEach(value -> result.add(value.path("revision").asText()));
    return List.copyOf(result);
  }

  private static List<String> values(JsonNode array) {
    List<String> result = new ArrayList<>();
    array.forEach(value -> result.add(value.asText()));
    return List.copyOf(result);
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        request.header("User-Agent", "kkrepo-conan-compat-test/1")
            .timeout(Duration.ofSeconds(180)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(), response.headers().map());
  }

  private static Exchange send(HttpRequest.Builder request, String authorization) throws Exception {
    if (authorization != null && !authorization.isBlank()) {
      request.header("Authorization", authorization);
    }
    return send(request);
  }

  private static Config configured() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set CONAN_COMPAT_ENABLED=true to run Conan compatibility");
    assumeTrue(get(probeEndpoint(config.nexus(), config.nexusToken()), "v1/ping").status() == 200,
        "Nexus Conan repository is not reachable at " + config.nexus().base());
    assumeTrue(get(probeEndpoint(config.candidate(), config.candidateToken()), "v1/ping").status() == 200,
        "kkRepo Conan repository is not reachable at " + config.candidate().base());
    return config;
  }

  private static Endpoint probeEndpoint(Endpoint endpoint, String configuredToken) {
    return configuredToken == null || configuredToken.isBlank()
        ? endpoint
        : endpoint.bearer(configuredToken.trim());
  }

  private static Map<String, String> archiveFiles(byte[] archive) throws Exception {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    try (TarArchiveInputStream tar = new TarArchiveInputStream(
        new GzipCompressorInputStream(new ByteArrayInputStream(archive)))) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (entry.isFile()) result.put(entry.getName(), sha256(tar.readAllBytes()));
      }
    }
    return Map.copyOf(result);
  }

  private static byte[] tarGzip(String path, byte[] contents) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      TarArchiveEntry entry = new TarArchiveEntry(path);
      entry.setSize(contents.length);
      entry.setModTime(0);
      entry.setUserId(0);
      entry.setGroupId(0);
      tar.putArchiveEntry(entry);
      tar.write(contents);
      tar.closeArchiveEntry();
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private static byte[] resource(String path) throws Exception {
    try (InputStream input = ConanRepositoryBlackBoxCompatibilityTest.class
        .getResourceAsStream(path)) {
      if (input == null) throw new IllegalStateException("Missing fixture resource " + path);
      return input.readAllBytes();
    }
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String md5(byte[] bytes) {
    return digest("MD5", bytes);
  }

  private static String sha1(byte[] bytes) {
    return digest("SHA-1", bytes);
  }

  private static String sha256(byte[] bytes) {
    return digest("SHA-256", bytes);
  }

  private static String basic(String username, String password) {
    if (username == null || username.isBlank()) return "";
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
    Endpoint bearer(String token) {
      return new Endpoint(base, "Bearer " + token);
    }

    HttpRequest.Builder request(String path) {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/" + path));
      if (authorization != null && !authorization.isBlank()) {
        request.header("Authorization", authorization);
      }
      return request;
    }
  }

  private record Config(
      boolean enabled,
      String nexusBase,
      String candidateBase,
      String nexusRepository,
      String candidateRepository,
      String nexusAuthorization,
      String candidateAuthorization,
      String nexusToken,
      String candidateToken) {
    static Config load() {
      String nexusUser = CompatDefaults.setting(
          "compat.conan.nexus.username", "CONAN_NEXUS_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusUsername().orElse(""));
      String nexusPassword = CompatDefaults.setting(
          "compat.conan.nexus.password", "CONAN_NEXUS_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPassword().orElse(""));
      String candidateUser = CompatDefaults.setting(
          "compat.conan.kkrepo.username", "CONAN_KKREPO_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusPlusUsername().orElse(""));
      String candidatePassword = CompatDefaults.setting(
          "compat.conan.kkrepo.password", "CONAN_KKREPO_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPlusPassword().orElse(""));
      String repository = CompatDefaults.setting(
          "compat.conan.repository", "CONAN_COMPAT_REPOSITORY")
          .orElse("conan-compat-hosted");
      return new Config(
          CompatDefaults.setting("compat.conan.enabled", "CONAN_COMPAT_ENABLED")
              .map(Boolean::parseBoolean).orElse(false),
          CompatDefaults.setting("compat.conan.nexus.baseUrl", "CONAN_NEXUS_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusBaseUrl().orElse("")),
          CompatDefaults.setting("compat.conan.kkrepo.baseUrl", "CONAN_KKREPO_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusPlusBaseUrl().orElse("")),
          CompatDefaults.setting(
              "compat.conan.nexus.repository", "CONAN_NEXUS_COMPAT_REPOSITORY")
              .orElse(repository),
          CompatDefaults.setting(
              "compat.conan.kkrepo.repository", "CONAN_KKREPO_COMPAT_REPOSITORY")
              .orElse(repository),
          basic(nexusUser, nexusPassword),
          basic(candidateUser, candidatePassword),
          CompatDefaults.setting("compat.conan.nexus.token", "CONAN_NEXUS_COMPAT_TOKEN")
              .orElse(""),
          CompatDefaults.setting("compat.conan.kkrepo.token", "CONAN_KKREPO_COMPAT_TOKEN")
              .orElse(""));
    }

    Endpoint nexus() {
      return new Endpoint(nexusBase + "/repository/" + nexusRepository, nexusAuthorization);
    }

    Endpoint candidate() {
      return new Endpoint(candidateBase + "/repository/" + candidateRepository,
          candidateAuthorization);
    }
  }

  private record Reference(
      String name,
      String version,
      String user,
      String channel,
      String rrev,
      String packageId,
      String prev) {
    String recipe() {
      return name + "/" + version + "@" + user + "/" + channel;
    }

    String recipeRoot() {
      return "v2/conans/" + name + "/" + version + "/" + user + "/" + channel;
    }

    String rrevRoot() {
      return recipeRoot() + "/revisions/" + rrev;
    }

    String packageRoot() {
      return rrevRoot() + "/packages/" + packageId;
    }

    String prevRoot() {
      return packageRoot() + "/revisions/" + prev;
    }

    String recipeFile(String file) {
      return rrevRoot() + "/files/" + file;
    }

    String packageFile(String file) {
      return prevRoot() + "/files/" + file;
    }
  }

  private record Fixture(
      byte[] recipeArchive,
      byte[] recipeManifest,
      byte[] packageArchive,
      byte[] conanInfo,
      byte[] packageManifest,
      String rrev,
      String packageId,
      String prev) {
    static Fixture create() throws Exception {
      byte[] recipe = resource("/conan/fixture/conanfile.py");
      byte[] header = resource("/conan/fixture/include/kkrepo_conan_fixture.h");
      byte[] recipeArchive = tarGzip("conanfile.py", recipe);
      byte[] recipeManifest = ("0\nconanfile.py: " + md5(recipe) + "\n")
          .getBytes(StandardCharsets.UTF_8);
      byte[] conanInfo = new byte[0];
      byte[] packageArchive = tarGzip("include/kkrepo_conan_fixture.h", header);
      byte[] packageManifest = ("0\nconaninfo.txt: " + md5(conanInfo)
          + "\ninclude/kkrepo_conan_fixture.h: " + md5(header) + "\n")
          .getBytes(StandardCharsets.UTF_8);
      return new Fixture(
          recipeArchive,
          recipeManifest,
          packageArchive,
          conanInfo,
          packageManifest,
          ConanManifest.parse(recipeManifest).summaryHash(),
          sha1(new byte[0]),
          ConanManifest.parse(packageManifest).summaryHash());
    }

    @Override
    public byte[] recipeArchive() {
      return recipeArchive.clone();
    }

    @Override
    public byte[] recipeManifest() {
      return recipeManifest.clone();
    }

    @Override
    public byte[] packageArchive() {
      return packageArchive.clone();
    }

    @Override
    public byte[] conanInfo() {
      return conanInfo.clone();
    }

    @Override
    public byte[] packageManifest() {
      return packageManifest.clone();
    }
  }
}
