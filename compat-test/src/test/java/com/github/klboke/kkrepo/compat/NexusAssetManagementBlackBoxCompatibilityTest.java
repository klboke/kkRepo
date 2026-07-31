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
import java.util.HexFormat;
import java.util.Optional;
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

      JsonNode referenceAsset = oneAsset(search(reference, repository, path));
      JsonNode candidateAsset = oneAsset(search(candidate, repository, path));
      referenceId = requiredText(referenceAsset, "id");
      candidateId = requiredText(candidateAsset, "id");
      assertAsset(referenceAsset, repository, path, payload);
      assertAsset(candidateAsset, repository, path, payload);

      JsonNode referenceGet = json(send(reference.request(
          "/service/rest/v1/assets/" + encodeSegment(referenceId)).GET()).body());
      JsonNode candidateGet = json(send(candidate.request(
          "/service/rest/v1/assets/" + encodeSegment(candidateId)).GET()).body());
      assertAsset(referenceGet, repository, path, payload);
      assertAsset(candidateGet, repository, path, payload);
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
      assertEmptyPage(search(reference, repository, path));
      assertEmptyPage(search(candidate, repository, path));
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
    String suffix = Long.toUnsignedString(System.nanoTime());
    String groupId = "com.kkrepo.compat";
    String artifactName = "asset-api-fixture-" + suffix;
    String version = "1.0.0";
    String path = groupId.replace('.', '/') + "/" + artifactName + "/" + version + "/"
        + artifactName + "-" + version + ".jar";
    byte[] payload = ("Maven asset component fixture " + suffix + "\n")
        .getBytes(StandardCharsets.UTF_8);
    byte[] pom = ("""
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
          <version>%s</version>
        </project>
        """.formatted(groupId, artifactName, version)).getBytes(StandardCharsets.UTF_8);

    assertEquals(204, uploadMavenComponent(
        reference, repository, groupId, artifactName, version, payload, pom).status(),
        "reference Maven component upload");
    assertEquals(204, uploadMavenComponent(
        candidate, repository, groupId, artifactName, version, payload, pom).status(),
        "candidate Maven component upload");

    JsonNode referenceAsset = awaitAssetByPath(reference, repository, artifactName, path);
    JsonNode candidateAsset = awaitAssetByPath(candidate, repository, artifactName, path);
    assertEquals(path, requiredText(referenceAsset, "path"));
    assertEquals(path, requiredText(candidateAsset, "path"));
    assertTrue(!artifactName.equals(path), "component name must differ from the asset path");
  }

  private static JsonNode awaitAssetByPath(
      Endpoint endpoint, String repository, String componentName, String expectedPath)
      throws Exception {
    for (int attempt = 0; attempt < 40; attempt++) {
      JsonNode items = json(search(endpoint, repository, componentName)).path("items");
      for (JsonNode item : items) {
        if (expectedPath.equals(item.path("path").asText())) {
          return item;
        }
      }
      Thread.sleep(500);
    }
    throw new AssertionError("asset search did not index " + expectedPath + " at " + endpoint.baseUrl);
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

  private static Exchange uploadMavenComponent(
      Endpoint endpoint,
      String repository,
      String groupId,
      String artifactId,
      String version,
      byte[] jar,
      byte[] pom) throws Exception {
    Multipart multipart = new Multipart()
        .field("maven2.groupId", groupId)
        .field("maven2.artifactId", artifactId)
        .field("maven2.version", version)
        .file("maven2.asset1", artifactId + "-" + version + ".jar",
            "application/java-archive", jar)
        .field("maven2.asset1.extension", "jar")
        .file("maven2.asset2", artifactId + "-" + version + ".pom", "application/xml", pom)
        .field("maven2.asset2.extension", "pom");
    return send(endpoint.request(
        "/service/rest/v1/components?repository=" + query(repository))
        .header("Content-Type", "multipart/form-data; boundary=" + multipart.boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body())));
  }

  private static byte[] search(Endpoint endpoint, String repository, String name)
      throws Exception {
    Exchange response = send(endpoint.request(
        "/service/rest/v1/search/assets?repository=" + query(repository)
            + "&name=" + query(name)).GET());
    assertEquals(200, response.status(), "asset search status at " + endpoint.baseUrl);
    return response.body();
  }

  private static JsonNode oneAsset(byte[] pageBody) throws Exception {
    JsonNode page = json(pageBody);
    assertTrue(page.path("items").isArray(), "items must be an array");
    assertEquals(1, page.path("items").size(), "exact path search must return one asset");
    assertTrue(page.path("continuationToken").isNull(), "exact path search has no next page");
    return page.path("items").get(0);
  }

  private static void assertAsset(
      JsonNode asset, String repository, String path, byte[] payload) throws Exception {
    assertEquals(repository, requiredText(asset, "repository"));
    assertEquals(path, requiredText(asset, "path"));
    assertEquals("raw", requiredText(asset, "format"));
    assertFalse(requiredText(asset, "id").isBlank());
    assertFalse(requiredText(asset, "downloadUrl").isBlank());
    assertEquals(digest("SHA-1", payload), requiredText(asset.path("checksum"), "sha1"));
    assertEquals(digest("MD5", payload), requiredText(asset.path("checksum"), "md5"));
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

  private record Exchange(int status, byte[] body) {}
}
