package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Black-box compatibility checks for Nexus 3 asset management endpoints. */
class NexusAssetManagementBlackBoxCompatibilityTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void readOnlySearchAndMalformedAssetIdMatchNexusWhenConfigured() throws Exception {
    String referenceBase = CompatDefaults.nexusBaseUrl().orElse(null);
    String candidateBase = CompatDefaults.nexusPlusBaseUrl().orElse(null);
    assumeTrue(referenceBase != null && candidateBase != null,
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run asset compatibility");

    String missingRepository = "kkrepo-asset-missing-" + System.nanoTime();
    String searchPath = "/service/rest/v1/search/assets?repository="
        + URLEncoder.encode(missingRepository, StandardCharsets.UTF_8);
    Exchange referenceSearch = send(referenceBase + searchPath);
    Exchange candidateSearch = send(candidateBase + searchPath);
    assertEquals(referenceSearch.status(), candidateSearch.status(), "missing repository search status");
    assertEquals(200, candidateSearch.status(), "missing repository search should return an empty page");
    assertEmptyPage(referenceSearch.body());
    assertEmptyPage(candidateSearch.body());

    String malformedPath = "/service/rest/v1/assets/not-a-valid-id";
    Exchange referenceMalformed = send(referenceBase + malformedPath);
    Exchange candidateMalformed = send(candidateBase + malformedPath);
    assertEquals(referenceMalformed.status(), candidateMalformed.status(), "malformed asset id status");
    assertEquals(422, candidateMalformed.status(), "malformed asset id should be rejected");
  }

  @Test
  void rawUploadSearchGetDownloadDeleteRoundTripMatchesNexusWhenExplicitlyEnabled()
      throws Exception {
    assumeTrue(writeEnabled(),
        "Set COMPAT_WRITE_ENABLED=true to run asset write/delete compatibility");
    Endpoint reference = Endpoint.referenceEndpoint();
    Endpoint candidate = Endpoint.candidateEndpoint();
    String repository = setting(
        "compat.asset.rawRepository", "COMPAT_ASSET_RAW_REPOSITORY").orElse(null);
    assumeTrue(repository != null && !repository.isBlank(),
        "Set COMPAT_ASSET_RAW_REPOSITORY to the same disposable Raw hosted repository on both endpoints");
    assertTrue(repositoryReady(reference, repository) && repositoryReady(candidate, repository),
        "Create the same dedicated Raw hosted repository on both endpoints before running writes");

    String suffix = Long.toUnsignedString(System.nanoTime());
    String directory = "nexus-asset-compat/" + suffix;
    String filename = "artifact-" + suffix + ".txt";
    String path = directory + "/" + filename;
    byte[] payload = ("Nexus asset API compatibility fixture " + suffix + "\n")
        .getBytes(StandardCharsets.UTF_8);
    String referenceId = null;
    String candidateId = null;
    try {
      assertEquals(204, upload(reference, repository, directory, filename, payload).status(),
          "reference Raw multipart upload");
      assertEquals(204, upload(candidate, repository, directory, filename, payload).status(),
          "candidate Raw multipart upload");

      JsonNode referenceAsset = awaitAssetByPath(reference, repository, null, path);
      JsonNode candidateAsset = awaitAssetByPath(candidate, repository, null, path);
      referenceId = requiredText(referenceAsset, "id");
      candidateId = requiredText(candidateAsset, "id");
      assertAsset(reference, referenceAsset, repository, path, payload);
      assertAsset(candidate, candidateAsset, repository, path, payload);

      JsonNode referenceGet = json(send(reference.request(
          "/service/rest/v1/assets/" + encodeSegment(referenceId)).GET()).body());
      JsonNode candidateGet = json(send(candidate.request(
          "/service/rest/v1/assets/" + encodeSegment(candidateId)).GET()).body());
      assertAsset(reference, referenceGet, repository, path, payload);
      assertAsset(candidate, candidateGet, repository, path, payload);
      assertDetailAsset(referenceGet, "raw");
      assertDetailAsset(candidateGet, "raw");
      assertEquals(requiredText(referenceGet, "contentType"), requiredText(candidateGet, "contentType"));
      assertEquals(requiredText(referenceGet, "blobStoreName"), requiredText(candidateGet, "blobStoreName"));

      assertDownload(reference, referenceGet, payload);
      assertDownload(candidate, candidateGet, payload);
      assertHead(reference, referenceGet);
      assertHead(candidate, candidateGet);

      assertEquals(204, send(reference.request(
          "/service/rest/v1/assets/" + encodeSegment(referenceId))
          .DELETE()).status(), "reference asset delete");
      referenceId = null;
      assertEquals(204, send(candidate.request(
          "/service/rest/v1/assets/" + encodeSegment(candidateId))
          .DELETE()).status(), "candidate asset delete");
      candidateId = null;

      assertEquals(404, send(reference.request(
          "/service/rest/v1/assets/" + encodeSegment(requiredText(referenceAsset, "id")))
          .GET()).status(), "reference GET after delete");
      assertEquals(404, send(candidate.request(
          "/service/rest/v1/assets/" + encodeSegment(requiredText(candidateAsset, "id")))
          .GET()).status(), "candidate GET after delete");
      awaitAssetMissingByPath(reference, repository, null, path);
      awaitAssetMissingByPath(candidate, repository, null, path);
    } finally {
      deleteQuietly(reference, referenceId);
      deleteQuietly(candidate, candidateId);
    }
  }

  @Test
  void mavenNameSearchUsesComponentNameInsteadOfAssetPathWhenWriteEnabled() throws Exception {
    assumeTrue(writeEnabled(),
        "Set COMPAT_WRITE_ENABLED=true to run Maven asset name compatibility");
    Endpoint reference = Endpoint.referenceEndpoint();
    Endpoint candidate = Endpoint.candidateEndpoint();
    String repository = setting(
        "compat.asset.mavenRepository", "COMPAT_ASSET_MAVEN_REPOSITORY")
        .orElse("maven-releases");
    try (MavenRepositoryBlackBoxCompatibilityTest.ReleaseDeployment deployment =
        MavenRepositoryBlackBoxCompatibilityTest.deployReleaseFixture(
            repository,
            reference.baseUrl(),
            CompatDefaults.nexusUsername().orElseThrow(),
            CompatDefaults.nexusPassword().orElseThrow(),
            candidate.baseUrl(),
            CompatDefaults.nexusPlusUsername().orElseThrow(),
            CompatDefaults.nexusPlusPassword().orElseThrow())) {
      String artifactName = deployment.artifactId();
      String path = deployment.jarPath();
      JsonNode referenceAsset = awaitAssetByPath(reference, repository, artifactName, path);
      JsonNode candidateAsset = awaitAssetByPath(candidate, repository, artifactName, path);
      assertPath(reference, referenceAsset, path);
      assertPath(candidate, candidateAsset, path);
      assertTrue(!artifactName.equals(path), "component name must differ from the asset path");
    }
  }

  private static JsonNode awaitAssetByPath(
      Endpoint endpoint, String repository, String name, String expectedPath)
      throws Exception {
    SearchScan lastScan = null;
    for (int attempt = 0; attempt < 120; attempt++) {
      lastScan = scanSearch(endpoint, repository, name, expectedPath);
      if (lastScan.asset() != null) {
        return lastScan.asset();
      }
      Thread.sleep(500);
    }
    throw new AssertionError(
        "asset search did not index expectedPath=" + expectedPath
            + ", repository=" + repository
            + ", name=" + diagnosticName(name)
            + ", endpoint=" + endpoint.baseUrl()
            + ", lastResponse=" + diagnosticBody(lastScan.lastBody()));
  }

  private static void awaitAssetMissingByPath(
      Endpoint endpoint, String repository, String name, String expectedPath) throws Exception {
    SearchScan lastScan = null;
    for (int attempt = 0; attempt < 120; attempt++) {
      lastScan = scanSearch(endpoint, repository, name, expectedPath);
      if (lastScan.asset() == null) {
        return;
      }
      Thread.sleep(500);
    }
    throw new AssertionError(
        "asset search still returned expectedPath=" + expectedPath
            + ", repository=" + repository
            + ", name=" + diagnosticName(name)
            + ", endpoint=" + endpoint.baseUrl()
            + ", lastResponse=" + diagnosticBody(lastScan.lastBody()));
  }

  private static SearchScan scanSearch(
      Endpoint endpoint, String repository, String name, String expectedPath)
      throws Exception {
    String continuationToken = null;
    Set<String> seenTokens = new HashSet<>();
    byte[] lastBody = new byte[0];
    for (int pageNumber = 0; pageNumber < 100; pageNumber++) {
      lastBody = search(endpoint, repository, name, continuationToken);
      JsonNode page = json(lastBody);
      JsonNode items = page.path("items");
      assertTrue(items.isArray(), "search items must be an array");
      for (JsonNode item : items) {
        if (expectedPath.equals(comparablePath(endpoint, item.path("path").asText()))) {
          return new SearchScan(item, lastBody);
        }
      }
      JsonNode tokenNode = page.path("continuationToken");
      if (!tokenNode.isTextual() || tokenNode.asText().isBlank()) {
        return new SearchScan(null, lastBody);
      }
      continuationToken = tokenNode.asText();
      if (!seenTokens.add(continuationToken)) {
        throw new AssertionError(
            "asset search repeated continuationToken at " + endpoint.baseUrl());
      }
    }
    throw new AssertionError(
        "asset search exceeded 100 pages at " + endpoint.baseUrl()
            + ", lastResponse=" + diagnosticBody(lastBody));
  }

  private static String comparablePath(Endpoint endpoint, String path) {
    if (endpoint.reference() && path.startsWith("/")) {
      return path.substring(1);
    }
    return path;
  }

  private static String diagnosticName(String name) {
    return name == null ? "<none>" : name;
  }

  private static String diagnosticBody(byte[] body) {
    String value = new String(body, StandardCharsets.UTF_8);
    return value.length() <= 2_000 ? value : value.substring(0, 2_000) + "...";
  }

  private static void assertEmptyPage(byte[] body) throws Exception {
    JsonNode page = MAPPER.readTree(body);
    assertTrue(page.path("items").isArray(), "items must be an array");
    assertTrue(page.path("items").isEmpty(), "items must be empty");
    assertTrue(page.path("continuationToken").isNull(), "continuationToken must be null");
  }

  private static Exchange send(String url) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .GET()
        .build();
    HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body());
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        request.timeout(Duration.ofSeconds(60)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body());
  }

  private static Exchange upload(
      Endpoint endpoint,
      String repository,
      String directory,
      String filename,
      byte[] payload) throws Exception {
    Multipart multipart = new Multipart()
        .field("raw.directory", directory)
        .file("raw.asset1", filename, "text/plain", payload)
        .field("raw.asset1.filename", filename);
    return send(endpoint.request(
        "/service/rest/v1/components?repository=" + query(repository))
        .header("Content-Type", "multipart/form-data; boundary=" + multipart.boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body())));
  }

  private static byte[] search(
      Endpoint endpoint, String repository, String name, String continuationToken)
      throws Exception {
    StringBuilder path = new StringBuilder(
        "/service/rest/v1/search/assets?repository=" + query(repository));
    if (name != null) {
      path.append("&name=").append(query(name));
    }
    if (continuationToken != null) {
      path.append("&continuationToken=").append(query(continuationToken));
    }
    Exchange response = send(endpoint.request(path.toString()).GET());
    assertEquals(200, response.status(), "asset search status at " + endpoint.baseUrl);
    return response.body();
  }

  private static void assertAsset(
      Endpoint endpoint, JsonNode asset, String repository, String path, byte[] payload)
      throws Exception {
    assertEquals(repository, requiredText(asset, "repository"));
    assertPath(endpoint, asset, path);
    assertEquals("raw", requiredText(asset, "format"));
    assertFalse(requiredText(asset, "id").isBlank());
    assertFalse(requiredText(asset, "downloadUrl").isBlank());
    assertEquals(digest("SHA-1", payload), requiredText(asset.path("checksum"), "sha1"));
    assertEquals(digest("MD5", payload), requiredText(asset.path("checksum"), "md5"));
  }

  private static void assertPath(Endpoint endpoint, JsonNode asset, String expectedPath) {
    assertEquals(expectedPath, comparablePath(endpoint, requiredText(asset, "path")));
  }

  private static void assertDetailAsset(JsonNode asset, String format) {
    assertFalse(requiredText(asset, "contentType").isBlank());
    assertFalse(requiredText(asset, "lastModified").isBlank());
    assertTrue(asset.has("lastDownloaded"), "lastDownloaded must be present, including when null");
    assertFalse(requiredText(asset, "blobCreated").isBlank());
    assertFalse(requiredText(asset, "blobStoreName").isBlank());
    assertTrue(asset.path(format).isObject(), format + " detail attributes must be an object");
  }

  private static void assertDownload(
      Endpoint endpoint, JsonNode asset, byte[] expected) throws Exception {
    Exchange download = send(endpoint.absolute(requiredText(asset, "downloadUrl")).GET());
    assertEquals(200, download.status(), "asset download status at " + endpoint.baseUrl);
    assertEquals(HexFormat.of().formatHex(expected), HexFormat.of().formatHex(download.body()),
        "asset download body at " + endpoint.baseUrl);
  }

  private static void assertHead(Endpoint endpoint, JsonNode asset) throws Exception {
    Exchange head = send(endpoint.absolute(requiredText(asset, "downloadUrl"))
        .method("HEAD", HttpRequest.BodyPublishers.noBody()));
    assertEquals(200, head.status(), "asset HEAD status at " + endpoint.baseUrl);
  }

  private static boolean repositoryReady(Endpoint endpoint, String repository) throws Exception {
    String path = endpoint.reference
        ? "/service/rest/v1/repositories/raw/hosted/" + encodeSegment(repository)
        : "/internal/repositories/" + encodeSegment(repository);
    Exchange response = send(endpoint.request(path).GET());
    return response.status() == 200;
  }

  private static void deleteQuietly(Endpoint endpoint, String id) {
    if (id == null || id.isBlank()) {
      return;
    }
    try {
      send(endpoint.request("/service/rest/v1/assets/" + encodeSegment(id)).DELETE());
    } catch (Exception ignored) {
      // The primary assertion remains the source of failure; cleanup is best effort.
    }
  }

  private static JsonNode json(byte[] body) throws Exception {
    return MAPPER.readTree(body);
  }

  private static String requiredText(JsonNode object, String field) {
    JsonNode value = object.path(field);
    assertTrue(value.isTextual() && !value.asText().isBlank(), field + " must be non-blank text");
    return value.asText();
  }

  private static String digest(String algorithm, byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value));
  }

  private static String query(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String encodeSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static boolean writeEnabled() {
    return Boolean.parseBoolean(
        setting("compat.write.enabled", "COMPAT_WRITE_ENABLED").orElse("false"));
  }

  private static Optional<String> setting(String property, String environment) {
    return CompatDefaults.setting(property, environment);
  }

  private record Endpoint(String baseUrl, String authorization, boolean reference) {
    static Endpoint referenceEndpoint() {
      return new Endpoint(
          CompatDefaults.nexusBaseUrl().orElseThrow(),
          basic(
              CompatDefaults.nexusUsername().orElseThrow(),
              CompatDefaults.nexusPassword().orElseThrow()),
          true);
    }

    static Endpoint candidateEndpoint() {
      return new Endpoint(
          CompatDefaults.nexusPlusBaseUrl().orElseThrow(),
          basic(
              CompatDefaults.nexusPlusUsername().orElseThrow(),
              CompatDefaults.nexusPlusPassword().orElseThrow()),
          false);
    }

    HttpRequest.Builder request(String path) {
      return HttpRequest.newBuilder(URI.create(baseUrl + path))
          .header("Authorization", authorization);
    }

    HttpRequest.Builder absolute(String url) {
      return HttpRequest.newBuilder(URI.create(url))
          .header("Authorization", authorization);
    }

    private static String basic(String username, String password) {
      return "Basic " + Base64.getEncoder().encodeToString(
          (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
  }

  private static final class Multipart {
    private final String boundary = "kkrepo-asset-" + Long.toUnsignedString(System.nanoTime());
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    Multipart field(String name, String value) throws Exception {
      write("--" + boundary + "\r\n");
      write("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
      write(value + "\r\n");
      return this;
    }

    Multipart file(String name, String filename, String contentType, byte[] body) throws Exception {
      write("--" + boundary + "\r\n");
      write("Content-Disposition: form-data; name=\"" + name
          + "\"; filename=\"" + filename + "\"\r\n");
      write("Content-Type: " + contentType + "\r\n\r\n");
      output.write(body);
      write("\r\n");
      return this;
    }

    byte[] body() throws Exception {
      write("--" + boundary + "--\r\n");
      return output.toByteArray();
    }

    private void write(String value) throws Exception {
      output.write(value.getBytes(StandardCharsets.UTF_8));
    }
  }

  private record SearchScan(JsonNode asset, byte[] lastBody) {}

  private record Exchange(int status, byte[] body) {}
}
