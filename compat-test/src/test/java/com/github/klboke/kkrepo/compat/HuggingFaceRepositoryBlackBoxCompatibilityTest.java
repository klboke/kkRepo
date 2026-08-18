package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Black-box comparison for the Nexus Repository Hugging Face plugin surface.
 *
 * <p>The fixture is opt-in because both targets must proxy the same deterministic upstream model.
 */
class HuggingFaceRepositoryBlackBoxCompatibilityTest {
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void repositoryRootMatchesReferenceLandingPageSemantics() throws Exception {
    assumeTrue(enabled(),
        "Set HUGGINGFACE_COMPAT_ENABLED=true and both proxy URLs to run Hugging Face compatibility");
    Fixture fixture = Fixture.fromEnvironment();

    Exchange nexusRoot = get(fixture.nexus(), "");
    Exchange candidateRoot = get(fixture.candidate(), "");
    assertEquals(200, nexusRoot.status(), "Nexus repository root status");
    assertEquals(nexusRoot.status(), candidateRoot.status(), "repository root status");
    assertTrue(nexusRoot.header("content-type").startsWith("text/html"),
        "Nexus repository root content type");
    assertTrue(candidateRoot.header("content-type").startsWith("text/html"),
        "candidate repository root content type");
    String message = "This huggingface proxy repository is not directly browseable at this URL.";
    assertTrue(nexusRoot.text().contains(message), "Nexus repository root message");
    assertTrue(candidateRoot.text().contains(message), "candidate repository root message");
    assertTrue(candidateRoot.text().contains(
        "/browse/#browse/browse:" + fixture.candidate().repositoryName()),
        "candidate repository-specific Browse link");
    assertTrue(candidateRoot.text().contains("/service/rest/repository/browse/"),
        "candidate HTML index link");

    Exchange nexusHead = head(fixture.nexus(), "");
    Exchange candidateHead = head(fixture.candidate(), "");
    assertEquals(200, nexusHead.status(), "Nexus repository root HEAD status");
    assertEquals(nexusHead.status(), candidateHead.status(), "repository root HEAD status");
    assertEquals(0, candidateHead.body().length, "repository root HEAD body");
  }

  @Test
  void htmlBrowseLinksUseResolvableImmutableRoutes() throws Exception {
    assumeTrue(enabled(),
        "Set HUGGINGFACE_COMPAT_ENABLED=true and both proxy URLs to run Hugging Face compatibility");
    Fixture fixture = Fixture.fromEnvironment();
    String browsePath = fixture.modelId() + "/" + fixture.commit() + "/";
    String expectedRoute = fixture.modelId() + "/resolve/" + fixture.commit()
        + "/" + fixture.filePath();

    Exchange nexusBrowse = send(fixture.nexus().browseRequest(browsePath).GET().build());
    Exchange candidateBrowse = send(fixture.candidate().browseRequest(browsePath).GET().build());

    assertEquals(200, nexusBrowse.status(), "Nexus HTML browse status");
    assertEquals(nexusBrowse.status(), candidateBrowse.status(), "HTML browse status");
    assertTrue(nexusBrowse.text().contains(expectedRoute), "Nexus immutable download link");
    assertTrue(candidateBrowse.text().contains(expectedRoute), "candidate immutable download link");
  }

  @Test
  void modelMetadataPathsInfoAndImmutableDownloadsMatchReference() throws Exception {
    assumeTrue(enabled(),
        "Set HUGGINGFACE_COMPAT_ENABLED=true and both proxy URLs to run Hugging Face compatibility");
    Fixture fixture = Fixture.fromEnvironment();
    String modelPath = "api/models/" + fixture.modelId();
    String resolvePath = fixture.modelId() + "/resolve/" + fixture.commit()
        + "/" + fixture.filePath();

    Exchange nexusMetadata = get(fixture.nexus(), modelPath);
    Exchange candidateMetadata = get(fixture.candidate(), modelPath);
    assertEquals(200, nexusMetadata.status(), "Nexus model info status");
    assertEquals(nexusMetadata.status(), candidateMetadata.status(), "model info status");
    JsonNode nexusModel = JSON.readTree(nexusMetadata.body());
    JsonNode candidateModel = JSON.readTree(candidateMetadata.body());
    assertEquals(fixture.commit(), nexusModel.path("sha").asText(), "Nexus fixture commit");
    assertEquals(nexusModel.path("sha"), candidateModel.path("sha"), "model commit");
    assertFalse(candidateModel.toString().contains("xetHash"), "client-visible xetHash");
    assertFalse(candidateModel.toString().contains("xet-read-token"), "client-visible Xet token URL");
    assertTrue(candidateMetadata.header("etag").startsWith("\""), "candidate metadata ETag");
    Exchange notModified = get(
        fixture.candidate(), modelPath, Map.of("If-None-Match", candidateMetadata.header("etag")));
    assertEquals(304, notModified.status(), "candidate conditional model info");
    assertEquals(0, notModified.body().length, "304 body");

    byte[] pathsRequest = ("{\"paths\":[\"" + fixture.filePath() + "\"]}")
        .getBytes(StandardCharsets.UTF_8);
    String pathsInfoPath = modelPath + "/paths-info/" + fixture.commit();
    Exchange nexusPaths = post(fixture.nexus(), pathsInfoPath, pathsRequest);
    Exchange candidatePaths = post(fixture.candidate(), pathsInfoPath, pathsRequest);
    // Nexus Repository 3.94 returns 400 for the fixed-length POST emitted by
    // huggingface_hub 1.27, while legacy clients happen to avoid this route. Keep the reference
    // result explicit, but require the protocol-correct response that current clients need.
    assertTrue(nexusPaths.status() == 200 || nexusPaths.status() == 400,
        "unexpected Nexus paths-info status " + nexusPaths.status());
    assertEquals(200, candidatePaths.status(), "candidate paths-info status");
    JsonNode candidatePath = JSON.readTree(candidatePaths.body()).path(0);
    assertEquals(fixture.filePath(), candidatePath.path("path").asText(), "paths-info path");
    assertTrue(candidatePath.path("size").asLong() > 0, "paths-info size");
    if (nexusPaths.status() == 200) {
      JsonNode nexusPath = JSON.readTree(nexusPaths.body()).path(0);
      assertEquals(nexusPath.path("path"), candidatePath.path("path"), "paths-info path");
      assertEquals(nexusPath.path("size"), candidatePath.path("size"), "paths-info size");
    }
    assertFalse(candidatePaths.text().contains("xetHash"), "paths-info xetHash");

    Exchange nexusDownload = get(fixture.nexus(), resolvePath);
    Exchange candidateDownload = get(fixture.candidate(), resolvePath);
    assertEquals(200, nexusDownload.status(), "Nexus download status");
    assertEquals(nexusDownload.status(), candidateDownload.status(), "download status");
    assertArrayEquals(nexusDownload.body(), candidateDownload.body(), "download body");
    assertEquals(fixture.sha256(), sha256(candidateDownload.body()), "candidate SHA-256");
    assertEquals(mediaType(nexusDownload.header("content-type")),
        mediaType(candidateDownload.header("content-type")), "download content type");
    assertTrue(nexusDownload.header("content-disposition").startsWith("inline;"),
        "Nexus inline disposition");
    assertTrue(candidateDownload.header("content-disposition").startsWith("inline;"),
        "candidate inline disposition");
    assertTrue(candidateDownload.header("content-disposition").contains(fileName(fixture.filePath())),
        "candidate original filename");
    assertEquals(fixture.commit(), candidateDownload.header("x-repo-commit"));
    assertEquals(Long.toString(candidateDownload.body().length),
        candidateDownload.header("x-linked-size"));
    assertFalse(candidateDownload.headers().containsKey("location"),
        "candidate must bridge LFS/Xet redirects server-side");

    Exchange nexusHead = head(fixture.nexus(), resolvePath);
    Exchange candidateHead = head(fixture.candidate(), resolvePath);
    assertEquals(200, nexusHead.status(), "Nexus HEAD status");
    assertEquals(nexusHead.status(), candidateHead.status(), "HEAD status");
    assertEquals(nexusDownload.body().length,
        Long.parseLong(candidateHead.header("content-length")), "HEAD length");
    assertEquals(mediaType(nexusHead.header("content-type")),
        mediaType(candidateHead.header("content-type")), "HEAD content type");
    assertTrue(candidateHead.header("content-disposition").startsWith("inline;"),
        "HEAD inline disposition");

    int rangeEnd = Math.min(65_536, candidateDownload.body().length - 1);
    assertTrue(rangeEnd >= 1, "fixture must contain at least two bytes");
    String rangeValue = "bytes=1-" + rangeEnd;
    Exchange nexusRange = get(fixture.nexus(), resolvePath, Map.of("Range", rangeValue));
    Exchange candidateRange = get(
        fixture.candidate(), resolvePath, Map.of("Range", rangeValue));
    assertEquals(206, nexusRange.status(), "Nexus Range status");
    assertEquals(nexusRange.status(), candidateRange.status(), "Range status");
    assertArrayEquals(nexusRange.body(), candidateRange.body(), "Range body");
    assertEquals("bytes 1-" + rangeEnd + "/" + candidateDownload.body().length,
        candidateRange.header("content-range"));
  }

  private static boolean enabled() {
    return Boolean.parseBoolean(System.getenv().getOrDefault(
        "HUGGINGFACE_COMPAT_ENABLED", "false"));
  }

  private static Exchange get(Endpoint endpoint, String path) throws Exception {
    return get(endpoint, path, Map.of());
  }

  private static Exchange get(
      Endpoint endpoint, String path, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request = endpoint.request(path);
    headers.forEach(request::header);
    return send(request.GET().build());
  }

  private static Exchange head(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
  }

  private static Exchange post(Endpoint endpoint, String path, byte[] body) throws Exception {
    return send(endpoint.request(path)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build());
  }

  private static Exchange send(HttpRequest request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        request, HttpResponse.BodyHandlers.ofByteArray());
    Map<String, String> headers = new LinkedHashMap<>();
    response.headers().map().forEach((name, values) -> {
      if (!values.isEmpty()) headers.put(name.toLowerCase(), values.getFirst());
    });
    return new Exchange(response.statusCode(), response.body(), headers);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static String mediaType(String value) {
    if (value == null) return "";
    int separator = value.indexOf(';');
    return (separator < 0 ? value : value.substring(0, separator)).trim().toLowerCase();
  }

  private static String fileName(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static String setting(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private record Fixture(
      Endpoint nexus,
      Endpoint candidate,
      String modelId,
      String commit,
      String filePath,
      String sha256) {
    private static Fixture fromEnvironment() {
      String nexusUrl = required("HUGGINGFACE_NEXUS_PROXY_URL");
      String candidateUrl = required("HUGGINGFACE_KKREPO_PROXY_URL");
      return new Fixture(
          new Endpoint(
              nexusUrl,
              setting("NEXUS_COMPAT_USERNAME", "admin"),
              setting("NEXUS_COMPAT_PASSWORD", "Admin1234")),
          new Endpoint(
              candidateUrl,
              setting("KKREPO_COMPAT_USERNAME", "admin"),
              setting("KKREPO_COMPAT_PASSWORD", "12345678")),
          setting("HUGGINGFACE_COMPAT_MODEL", "kkrepo/hf-benchmark"),
          setting(
              "HUGGINGFACE_COMPAT_COMMIT",
              "0123456789abcdef0123456789abcdef01234567"),
          setting("HUGGINGFACE_COMPAT_FILE", "model.safetensors"),
          setting(
              "HUGGINGFACE_COMPAT_SHA256",
              "74a18e3f48369ee8c8e7cd03bd8b786591b0c19e2ee4df6ec97e74bef0c849d8"));
    }

    private static String required(String name) {
      String value = System.getenv(name);
      assumeTrue(value != null && !value.isBlank(), "Set " + name);
      return value.replaceAll("/+$", "");
    }
  }

  private record Endpoint(String baseUrl, String username, String password) {
    private HttpRequest.Builder request(String path) {
      return authenticatedRequest(baseUrl + "/" + path);
    }

    private HttpRequest.Builder browseRequest(String path) {
      int marker = baseUrl.indexOf("/repository/");
      if (marker < 0) {
        throw new IllegalArgumentException("Repository endpoint is missing /repository/: " + baseUrl);
      }
      String browseUrl = baseUrl.substring(0, marker)
          + "/service/rest/repository/browse/" + repositoryName() + "/" + path;
      return authenticatedRequest(browseUrl);
    }

    private String repositoryName() {
      int marker = baseUrl.indexOf("/repository/");
      if (marker < 0) {
        throw new IllegalArgumentException("Repository endpoint is missing /repository/: " + baseUrl);
      }
      return baseUrl.substring(marker + "/repository/".length()).replaceAll("/+$", "");
    }

    private HttpRequest.Builder authenticatedRequest(String url) {
      String credentials = Base64.getEncoder().encodeToString(
          (username + ":" + password).getBytes(StandardCharsets.UTF_8));
      return HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(90))
          .header("Authorization", "Basic " + credentials);
    }
  }

  private record Exchange(int status, byte[] body, Map<String, String> headers) {
    private String header(String name) {
      return headers.getOrDefault(name.toLowerCase(), "");
    }

    private String text() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }
}
