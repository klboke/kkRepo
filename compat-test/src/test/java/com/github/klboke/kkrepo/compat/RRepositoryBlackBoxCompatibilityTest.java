package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.r.RPackageIndex;
import com.github.klboke.kkrepo.protocol.r.RPackageMetadata;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;

/** Opt-in CRAN-style hosted contract against a live Nexus 3.94 reference and kkRepo. */
class RRepositoryBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void generatedFixtureHasCanonicalDescriptionAndInstallableLayout() throws Exception {
    RCompatibilityFixtures.SourcePackage fixture = RCompatibilityFixtures.sourcePackage(
        "kkrepoRfixture", "1.2.3", "fixture");
    Map<String, byte[]> entries = archiveEntries(fixture.bytes());
    RPackageMetadata metadata = RPackageMetadata.fromDescription(
        entries.get(fixture.name() + "/DESCRIPTION"), fixture.filename());

    assertEquals(fixture.name(), metadata.packageName());
    assertEquals(fixture.version(), metadata.version());
    assertTrue(entries.containsKey(fixture.name() + "/NAMESPACE"));
    assertTrue(entries.containsKey(fixture.name() + "/R/marker.R"));
  }

  @Test
  void hostedIndexAndPackageHttpMatchNexusWhenConfigured() throws Exception {
    Config config = configured();
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String repository = "r-compat-" + suffix;
    RCompatibilityFixtures.SourcePackage fixture = RCompatibilityFixtures.sourcePackage(
        "kkrepoR" + suffix, "1.2.3", "live compatibility");
    String path = "src/contrib/" + fixture.filename();

    try {
      createNexusHosted(config, repository);
      createCandidateHosted(config, repository);

      assertRepositoryRoot(config.nexus(repository));
      assertRepositoryRoot(config.candidate(repository));
      Exchange nexusUpload = put(config.nexus(repository), path, fixture.bytes());
      Exchange candidateUpload = put(config.candidate(repository), path, fixture.bytes());
      assertEquals(200, nexusUpload.status(), () -> "Nexus upload: " + nexusUpload.text());
      assertEquals(nexusUpload.status(), candidateUpload.status(),
          () -> "kkRepo upload: " + candidateUpload.text());

      RPackageMetadata nexus = waitForIndex(config.nexus(repository), fixture.name());
      RPackageMetadata candidate = waitForIndex(config.candidate(repository), fixture.name());
      assertEquals(nexus.packageName(), candidate.packageName());
      assertEquals(nexus.version(), candidate.version());
      assertEquals(nexus.fields().get("License"), candidate.fields().get("License"));
      assertEquals(fixture.md5(), candidate.fields().get("MD5sum"));

      assertPackageHttp(config.nexus(repository), path, fixture.bytes());
      assertPackageHttp(config.candidate(repository), path, fixture.bytes());
      assertBrowseAndSearch(config, repository, fixture);
    } finally {
      deleteAsset(config.candidate(repository), path);
      deleteRepository(config.nexusAdmin(), repository);
      deleteRepository(config.candidateAdmin(), repository);
    }
  }

  private static RPackageMetadata waitForIndex(Endpoint endpoint, String packageName)
      throws Exception {
    Exchange last = null;
    for (int attempt = 0; attempt < 120; attempt++) {
      last = get(endpoint, "src/contrib/PACKAGES.gz");
      if (last.status() == 200) {
        try (GzipCompressorInputStream gzip = new GzipCompressorInputStream(
            new ByteArrayInputStream(last.body()))) {
          RPackageMetadata match = RPackageIndex.parse(gzip.readAllBytes()).stream()
              .filter(row -> packageName.equals(row.packageName()))
              .findFirst().orElse(null);
          if (match != null) {
            assertTrue(mediaType(last.header("content-type")).contains("gzip"));
            return match;
          }
        } catch (IllegalArgumentException ignored) {
          // The durable publisher may still be replacing the initial empty snapshot.
        }
      }
      Thread.sleep(250L);
    }
    throw new AssertionError("PACKAGES.gz did not expose " + packageName + ": "
        + (last == null ? "<none>" : last.status() + " " + last.text()));
  }

  private static void assertPackageHttp(Endpoint endpoint, String path, byte[] expected)
      throws Exception {
    Exchange get = get(endpoint, path);
    assertEquals(200, get.status());
    assertArrayEquals(expected, get.body());
    assertTrue(mediaType(get.header("content-type")).contains("gzip"));

    Exchange head = send(endpoint.request(path)
        .method("HEAD", HttpRequest.BodyPublishers.noBody()));
    assertEquals(200, head.status());
    assertEquals(Integer.toString(expected.length), head.header("content-length"));
    assertEquals(0, head.body().length);

    Exchange range = send(endpoint.request(path).header("Range", "bytes=0-63").GET());
    assertEquals(206, range.status());
    assertArrayEquals(Arrays.copyOf(expected, 64), range.body());
    assertEquals("bytes 0-63/" + expected.length, range.header("content-range"));

    assertFalse(get.header("etag").isBlank());
    Exchange conditional = send(endpoint.request(path)
        .header("If-None-Match", get.header("etag")).GET());
    assertEquals(304, conditional.status());
  }

  private static void assertRepositoryRoot(Endpoint endpoint) throws Exception {
    Exchange root = get(endpoint, "");
    assertEquals(200, root.status());
    assertEquals("text/html", mediaType(root.header("content-type")));
    assertTrue(root.text().toLowerCase().contains("repository"));
  }

  private static void assertBrowseAndSearch(
      Config config,
      String repository,
      RCompatibilityFixtures.SourcePackage fixture) throws Exception {
    Exchange search = send(config.candidateAdmin().request(
        "/internal/search/components?format=r&q=" + fixture.name()).GET());
    assertEquals(200, search.status());
    boolean found = false;
    for (JsonNode item : JSON.readTree(search.body()).path("items")) {
      if (repository.equals(item.path("repository").asText())
          && fixture.name().equals(item.path("name").asText())) {
        assertEquals(fixture.version(), item.path("version").asText());
        assertEquals(fixture.sha256(), item.path("details").path("sha256").asText());
        found = true;
      }
    }
    assertTrue(found, "R package must be discoverable through component search");

    String browseParent = "src/contrib/" + fixture.name() + "/" + fixture.version();
    String canonicalDownload = "/repository/" + repository + "/src/contrib/" + fixture.filename();
    String htmlBrowsePath =
        "/service/rest/repository/browse/" + repository + "/" + browseParent + "/";
    Exchange nexusHtml = waitForBrowse(config.nexusAdmin(), htmlBrowsePath);
    Exchange candidateHtml = waitForBrowse(config.candidateAdmin(), htmlBrowsePath);
    assertEquals(200, nexusHtml.status());
    assertEquals(nexusHtml.status(), candidateHtml.status());
    assertTrue(nexusHtml.text().contains(canonicalDownload));
    assertTrue(candidateHtml.text().contains(canonicalDownload));

    Exchange browse = send(config.candidateAdmin().request(
        "/internal/browse/" + repository + "?path=" + encode(
            browseParent)).GET());
    assertEquals(200, browse.status());
    boolean leaf = false;
    for (JsonNode entry : JSON.readTree(browse.body()).path("entries")) {
      if (fixture.filename().equals(entry.path("name").asText())) {
        assertEquals(browseParent + "/" + fixture.filename(), entry.path("path").asText());
        assertEquals(canonicalDownload, entry.path("downloadUrl").asText());
        leaf = entry.path("leaf").asBoolean();
      }
    }
    assertTrue(leaf, "R package must be a Browse leaf under src/contrib/package/version");
  }

  private static Exchange waitForBrowse(AdminEndpoint endpoint, String path) throws Exception {
    Exchange last = null;
    for (int attempt = 0; attempt < 120; attempt++) {
      last = send(endpoint.request(path).GET());
      if (last.status() == 200) return last;
      Thread.sleep(250L);
    }
    throw new AssertionError("R Browse projection did not become readable: "
        + (last == null ? "<none>" : last.status() + " " + last.text()));
  }

  private static void createNexusHosted(Config config, String repository) throws Exception {
    var body = JSON.createObjectNode();
    body.put("name", repository);
    body.put("online", true);
    var storage = body.putObject("storage");
    storage.put("blobStoreName", config.nexusBlobStore());
    storage.put("strictContentTypeValidation", true);
    storage.put("writePolicy", "ALLOW");
    body.putObject("component").put("proprietaryComponents", false);
    Exchange response = send(config.nexusAdmin().request(
            "/service/rest/v1/repositories/r/hosted")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    assertEquals(201, response.status(), () -> "Nexus R create: " + response.text());
  }

  private static void createCandidateHosted(Config config, String repository) throws Exception {
    var body = JSON.createObjectNode();
    body.put("name", repository);
    body.put("recipe", "r-hosted");
    body.put("online", true);
    body.put("blobStoreName", config.candidateBlobStore());
    body.put("strictContentTypeValidation", true);
    body.putObject("hosted").put("writePolicy", "ALLOW");
    Exchange response = send(config.candidateAdmin().request("/internal/repositories")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    assertEquals(201, response.status(), () -> "kkRepo R create: " + response.text());
  }

  private static Map<String, byte[]> archiveEntries(byte[] compressed) throws Exception {
    LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
    try (GzipCompressorInputStream gzip = new GzipCompressorInputStream(
            new ByteArrayInputStream(compressed));
        TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (entry.isFile()) entries.put(entry.getName(), tar.readNBytes((int) entry.getSize()));
      }
    }
    return Map.copyOf(entries);
  }

  private static Exchange put(Endpoint endpoint, String path, byte[] body) throws Exception {
    return send(endpoint.request(path)
        .header("Content-Type", "application/gzip")
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
  }

  private static Exchange get(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).GET());
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        request.header("User-Agent", "kkrepo-r-compat-test/1")
            .timeout(Duration.ofSeconds(180)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(), response.headers().map());
  }

  private static Config configured() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set R_COMPAT_ENABLED=true to run R compatibility");
    assumeTrue(reachable(config.nexusAdmin()),
        "Nexus R reference is not reachable at " + config.nexusBase());
    assumeTrue(reachable(config.candidateAdmin()),
        "kkRepo candidate is not reachable at " + config.candidateBase());
    return config;
  }

  private static boolean reachable(AdminEndpoint endpoint) {
    try {
      return send(endpoint.request("/service/rest/v1/status").GET()).status() > 0;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static void deleteRepository(AdminEndpoint endpoint, String repository) {
    try {
      Exchange response = send(endpoint.request(
          "/service/rest/v1/repositories/" + encode(repository)).DELETE());
      if (response.status() < 200 || response.status() >= 300) {
        send(endpoint.request("/internal/repositories/" + encode(repository)).DELETE());
      }
    } catch (Exception ignored) {
      // Best-effort cleanup for opt-in live fixtures.
    }
  }

  private static void deleteAsset(Endpoint endpoint, String path) {
    try {
      send(endpoint.request(path).DELETE());
    } catch (Exception ignored) {
      // Best-effort cleanup so the candidate repository can be removed.
    }
  }

  private static String basic(String username, String password) {
    return "Basic " + Base64.getEncoder().encodeToString(
        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String mediaType(String value) {
    int separator = value.indexOf(';');
    return (separator < 0 ? value : value.substring(0, separator)).trim().toLowerCase();
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
    HttpRequest.Builder request(String path) {
      return HttpRequest.newBuilder(URI.create(base + "/" + path))
          .header("Authorization", authorization);
    }
  }

  private record AdminEndpoint(String base, String authorization) {
    HttpRequest.Builder request(String path) {
      return HttpRequest.newBuilder(URI.create(base + path))
          .header("Authorization", authorization);
    }
  }

  private record Config(
      boolean enabled,
      String nexusBase,
      String candidateBase,
      String nexusAuthorization,
      String candidateAuthorization,
      String nexusBlobStore,
      String candidateBlobStore) {
    static Config load() {
      String nexusUser = CompatDefaults.setting(
          "compat.r.nexus.username", "R_NEXUS_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusUsername().orElse(""));
      String nexusPassword = CompatDefaults.setting(
          "compat.r.nexus.password", "R_NEXUS_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPassword().orElse(""));
      String candidateUser = CompatDefaults.setting(
          "compat.r.kkrepo.username", "R_KKREPO_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusPlusUsername().orElse(""));
      String candidatePassword = CompatDefaults.setting(
          "compat.r.kkrepo.password", "R_KKREPO_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPlusPassword().orElse(""));
      return new Config(
          CompatDefaults.setting("compat.r.enabled", "R_COMPAT_ENABLED")
              .map(Boolean::parseBoolean).orElse(false),
          CompatDefaults.setting("compat.r.nexus.baseUrl", "R_NEXUS_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusBaseUrl().orElse("")),
          CompatDefaults.setting("compat.r.kkrepo.baseUrl", "R_KKREPO_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusPlusBaseUrl().orElse("")),
          basic(nexusUser, nexusPassword),
          basic(candidateUser, candidatePassword),
          CompatDefaults.setting("compat.r.nexus.blobStore", "R_NEXUS_COMPAT_BLOB_STORE")
              .orElse("default"),
          CompatDefaults.setting("compat.r.kkrepo.blobStore", "R_KKREPO_COMPAT_BLOB_STORE")
              .orElse("default"));
    }

    Endpoint nexus(String repository) {
      return new Endpoint(nexusBase + "/repository/" + repository, nexusAuthorization);
    }

    Endpoint candidate(String repository) {
      return new Endpoint(candidateBase + "/repository/" + repository, candidateAuthorization);
    }

    AdminEndpoint nexusAdmin() {
      return new AdminEndpoint(nexusBase, nexusAuthorization);
    }

    AdminEndpoint candidateAdmin() {
      return new AdminEndpoint(candidateBase, candidateAuthorization);
    }
  }
}
