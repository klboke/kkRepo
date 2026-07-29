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

/** Nexus 3 management contracts used by Moon's Windows publication and cleanup flow. */
class MoonWindowsManagementBlackBoxCompatibilityTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration SEARCH_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration SEARCH_POLL_INTERVAL = Duration.ofMillis(250);
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void readOnlySearchAndMalformedAssetIdMatchNexusWhenConfigured() throws Exception {
    String referenceBase = CompatDefaults.nexusBaseUrl().orElse(null);
    String candidateBase = CompatDefaults.nexusPlusBaseUrl().orElse(null);
    assumeTrue(referenceBase != null && candidateBase != null,
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run Moon compatibility");

    String missingRepository = "kkrepo-moon-missing-" + System.nanoTime();
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
        "Set COMPAT_WRITE_ENABLED=true to run Moon write/delete compatibility");
    Endpoint reference = Endpoint.referenceEndpoint();
    Endpoint candidate = Endpoint.candidateEndpoint();
    String repository = setting(
        "compat.moon.rawRepository", "COMPAT_MOON_RAW_REPOSITORY")
        .orElse("moon-windows-compat");
    refuseProductionRepository(repository);
    assertTrue(repositoryReady(reference, repository) && repositoryReady(candidate, repository),
        "Create the same dedicated Raw hosted repository on both endpoints before running writes");

    String suffix = Long.toUnsignedString(System.nanoTime());
    String directory = "moon-windows-compat/" + suffix;
    String filename = "windows-component-" + suffix + ".zip";
    String path = directory + "/" + filename;
    byte[] payload = ("Moon Windows compatibility fixture " + suffix + "\n")
        .getBytes(StandardCharsets.UTF_8);
    String referenceId = null;
    String candidateId = null;
    try {
      assertEquals(204, upload(reference, repository, directory, filename, payload).status(),
          "reference Raw multipart upload");
      assertEquals(204, upload(candidate, repository, directory, filename, payload).status(),
          "candidate Raw multipart upload");

      JsonNode referenceAsset = awaitOneAsset(reference, repository, path);
      JsonNode candidateAsset = awaitOneAsset(candidate, repository, path);
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
      awaitEmptyPage(reference, repository, path);
      awaitEmptyPage(candidate, repository, path);
    } finally {
      deleteQuietly(reference, referenceId);
      deleteQuietly(candidate, candidateId);
    }
  }

  @Test
  void mavenGroupManagementResponseMatchesNexusWhenCredentialsAreConfigured() throws Exception {
    Endpoint reference = Endpoint.referenceEndpoint();
    Endpoint candidate = Endpoint.candidateEndpoint();
    String repository = setting(
        "compat.moon.mavenGroupRepository", "COMPAT_MOON_MAVEN_GROUP_REPOSITORY")
        .orElse("maven-public");
    String path = "/service/rest/v1/repositories/maven/group/" + encodeSegment(repository);

    Exchange referenceResponse = send(reference.request(path).GET());
    Exchange candidateResponse = send(candidate.request(path).GET());
    assertEquals(referenceResponse.status(), candidateResponse.status(), "Maven group status");
    assertEquals(200, candidateResponse.status(), "Maven group must be available to configured principal");
    JsonNode referenceJson = json(referenceResponse.body());
    JsonNode candidateJson = json(candidateResponse.body());
    assertMavenGroupShape(referenceJson, repository);
    assertMavenGroupShape(candidateJson, repository);
    assertEquals(referenceJson.path("online").asBoolean(), candidateJson.path("online").asBoolean());
    assertEquals(
        referenceJson.path("storage").path("strictContentTypeValidation").asBoolean(),
        candidateJson.path("storage").path("strictContentTypeValidation").asBoolean());
    assertEquals(
        referenceJson.path("group").path("memberNames"),
        candidateJson.path("group").path("memberNames"),
        "Maven group members");
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
        .file("raw.asset1", filename, "application/zip", payload)
        .field("raw.asset1.filename", filename);
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

  private static JsonNode awaitOneAsset(
      Endpoint endpoint, String repository, String path) throws Exception {
    long deadline = System.nanoTime() + SEARCH_VISIBILITY_TIMEOUT.toNanos();
    byte[] lastPage;
    do {
      lastPage = search(endpoint, repository, path);
      JsonNode page = json(lastPage);
      if (page.path("items").isArray() && page.path("items").size() == 1) {
        return oneAsset(lastPage);
      }
      Thread.sleep(SEARCH_POLL_INTERVAL.toMillis());
    } while (System.nanoTime() < deadline);
    return oneAsset(lastPage);
  }

  private static void awaitEmptyPage(
      Endpoint endpoint, String repository, String path) throws Exception {
    long deadline = System.nanoTime() + SEARCH_VISIBILITY_TIMEOUT.toNanos();
    byte[] lastPage;
    do {
      lastPage = search(endpoint, repository, path);
      JsonNode page = json(lastPage);
      if (page.path("items").isArray()
          && page.path("items").isEmpty()
          && page.path("continuationToken").isNull()) {
        return;
      }
      Thread.sleep(SEARCH_POLL_INTERVAL.toMillis());
    } while (System.nanoTime() < deadline);
    assertEmptyPage(lastPage);
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

  private static void assertMavenGroupShape(JsonNode body, String repository) {
    assertEquals(repository, body.path("name").asText());
    assertTrue(body.path("online").isBoolean());
    assertFalse(body.path("storage").path("blobStoreName").asText().isBlank());
    assertTrue(body.path("storage").path("strictContentTypeValidation").isBoolean());
    assertTrue(body.path("group").path("memberNames").isArray());
    assertTrue(body.path("maven").isMissingNode(), "Nexus 3.27 group GET does not return maven");
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

  private static void refuseProductionRepository(String repository) {
    boolean allowed = Boolean.parseBoolean(setting(
        "compat.moon.allowProductionRepository", "COMPAT_MOON_ALLOW_PRODUCTION_REPOSITORY")
        .orElse("false"));
    assertTrue(allowed || !"windows-artifacts".equalsIgnoreCase(repository),
        "Refusing writes to windows-artifacts without COMPAT_MOON_ALLOW_PRODUCTION_REPOSITORY=true");
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
    private final String boundary = "kkrepo-moon-" + Long.toUnsignedString(System.nanoTime());
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
