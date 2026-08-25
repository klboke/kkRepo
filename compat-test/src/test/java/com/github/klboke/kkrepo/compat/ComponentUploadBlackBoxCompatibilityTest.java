package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

class ComponentUploadBlackBoxCompatibilityTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void uploadSpecsExposeNexusCompatibleSupportedFormatsWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run component upload compatibility");

    Exchange reference = send(config.nexus.raw("/service/rest/v1/formats/upload-specs").GET());
    Exchange candidate = send(config.nexusPlus.raw("/service/rest/v1/formats/upload-specs").GET());
    assert2xx("reference upload specs", reference);
    assert2xx("candidate upload specs", candidate);

    Map<String, SpecShape> referenceSpecs = supportedSpecs(reference.body());
    Map<String, SpecShape> candidateSpecs = supportedSpecs(candidate.body());
    assertEquals(referenceSpecs, candidateSpecs, "supported upload spec shapes");
  }

  @Test
  void publicComponentUploadsMatchNexusWhenConfigured() throws Exception {
    CompatConfig config = CompatConfig.load();
    assumeTrue(config.configured(),
        "Set NEXUS_COMPAT_BASE_URL and KKREPO_COMPAT_BASE_URL to run component upload compatibility");
    if (config.setupEnabled()) {
      ensureNexusRepositories(config);
      ensureKkRepoRepositories(config);
    }

    String suffix = Long.toString(System.currentTimeMillis());
    assertMavenUpload(config, suffix);
    assertNpmUpload(config, suffix);
    assertPypiUpload(config, suffix);
    assertHelmUpload(config, suffix);
  }

  private static void assertMavenUpload(CompatConfig config, String suffix) throws Exception {
    String groupId = "com.github.klboke.upload";
    String artifactId = "kkrepo-upload-" + suffix;
    String version = "1.0." + suffix;
    String basePath = groupId.replace('.', '/') + "/" + artifactId + "/" + version;
    byte[] jar = ("component upload jar " + suffix + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] pom = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
          <version>%s</version>
        </project>
        """.formatted(groupId, artifactId, version).getBytes(StandardCharsets.UTF_8);

    Multipart multipart = new Multipart()
        .field("maven2.groupId", groupId)
        .field("maven2.artifactId", artifactId)
        .field("maven2.version", version)
        .file("maven2.asset1", artifactId + "-" + version + ".jar", "application/java-archive", jar)
        .field("maven2.asset1.extension", "jar")
        .file("maven2.asset2", artifactId + "-" + version + ".pom", "application/xml", pom)
        .field("maven2.asset2.extension", "pom");
    assertStatus("reference maven component upload", 204,
        send(config.nexus.componentUpload(config.mavenHosted(), multipart).POST(multipart.publisher())));
    Multipart candidateMultipart = multipart.copy();
    assertStatus("candidate maven component upload", 204,
        send(config.nexusPlus.componentUpload(config.mavenHosted(), candidateMultipart).POST(candidateMultipart.publisher())));
    Multipart duplicateMultipart = multipart.copy();
    assertDuplicateRejected("candidate duplicate maven component upload",
        send(config.nexusPlus.componentUpload(config.mavenHosted(), duplicateMultipart).POST(duplicateMultipart.publisher())));

    assertBody("maven jar", config.nexus.repository(config.mavenHosted(), basePath + "/" + artifactId + "-" + version + ".jar"),
        config.nexusPlus.repository(config.mavenHosted(), basePath + "/" + artifactId + "-" + version + ".jar"), jar);
    assertBody("maven pom", config.nexus.repository(config.mavenHosted(), basePath + "/" + artifactId + "-" + version + ".pom"),
        config.nexusPlus.repository(config.mavenHosted(), basePath + "/" + artifactId + "-" + version + ".pom"), pom);
  }

  private static void assertNpmUpload(CompatConfig config, String suffix) throws Exception {
    String name = "kkrepo-upload-npm-" + suffix;
    String version = "1.0." + suffix;
    String tarballName = name + "-" + version + ".tgz";
    byte[] tarball = tarGz("package/package.json",
        ("{\"name\":\"" + name + "\",\"version\":\"" + version
            + "\",\"description\":\"component upload fixture\"}\n").getBytes(StandardCharsets.UTF_8));

    Multipart referenceMultipart = new Multipart()
        .file("npm.asset", tarballName, "application/gzip", tarball);
    assertStatus("reference npm component upload", 204,
        send(config.nexus.componentUpload(config.npmHosted(), referenceMultipart).POST(referenceMultipart.publisher())));
    Multipart candidateMultipart = referenceMultipart.copy();
    assertStatus("candidate npm component upload", 204,
        send(config.nexusPlus.componentUpload(config.npmHosted(), candidateMultipart).POST(candidateMultipart.publisher())));
    Multipart duplicateMultipart = referenceMultipart.copy();
    assertDuplicateRejected("candidate duplicate npm component upload",
        send(config.nexusPlus.componentUpload(config.npmHosted(), duplicateMultipart).POST(duplicateMultipart.publisher())));

    Exchange referenceMetadata = send(config.nexus.repository(config.npmHosted(), name).GET());
    Exchange candidateMetadata = send(config.nexusPlus.repository(config.npmHosted(), name).GET());
    assertEquals(referenceMetadata.status(), candidateMetadata.status(), "npm metadata status");
    assert2xx("candidate npm metadata", candidateMetadata);
    assertTrue(candidateMetadata.bodyText().contains("\"" + version + "\""), "candidate npm metadata should contain version");
    assertBody("npm tarball", config.nexus.repository(config.npmHosted(), name + "/-/" + tarballName),
        config.nexusPlus.repository(config.npmHosted(), name + "/-/" + tarballName), tarball);
  }

  private static void assertPypiUpload(CompatConfig config, String suffix) throws Exception {
    String name = "kkrepo-upload-pypi-" + suffix;
    String normalized = name.toLowerCase();
    String version = "1.0." + suffix;
    String filename = name.replace('-', '_') + "-" + version + "-py3-none-any.whl";
    byte[] wheel = wheel(name, version, filename);
    Multipart referenceMultipart = new Multipart()
        .file("pypi.asset", filename, "application/octet-stream", wheel);
    assertStatus("reference pypi component upload", 204,
        send(config.nexus.componentUpload(config.pypiHosted(), referenceMultipart).POST(referenceMultipart.publisher())));
    Multipart candidateMultipart = referenceMultipart.copy();
    assertStatus("candidate pypi component upload", 204,
        send(config.nexusPlus.componentUpload(config.pypiHosted(), candidateMultipart).POST(candidateMultipart.publisher())));
    Multipart duplicateMultipart = referenceMultipart.copy();
    assertDuplicateRejected("candidate duplicate pypi component upload",
        send(config.nexusPlus.componentUpload(config.pypiHosted(), duplicateMultipart).POST(duplicateMultipart.publisher())));

    assertBody("pypi wheel", config.nexus.repository(config.pypiHosted(),
            "packages/" + normalized + "/" + version + "/" + filename),
        config.nexusPlus.repository(config.pypiHosted(), "packages/" + normalized + "/" + version + "/" + filename),
        wheel);
    Exchange candidateIndex = send(config.nexusPlus.repository(config.pypiHosted(), "simple/" + normalized + "/").GET());
    assert2xx("candidate pypi simple index", candidateIndex);
    assertTrue(candidateIndex.bodyText().contains(filename), "candidate PyPI index should link uploaded wheel");
  }

  private static void assertHelmUpload(CompatConfig config, String suffix) throws Exception {
    String name = "kkrepo-upload-helm";
    String version = "1.0." + suffix;
    String filename = name + "-" + version + ".tgz";
    byte[] chart = chart(name, version);
    Multipart referenceMultipart = new Multipart()
        .file("helm.asset", filename, "application/gzip", chart);
    assertStatus("reference helm component upload", 204,
        send(config.nexus.componentUpload(config.helmHosted(), referenceMultipart).POST(referenceMultipart.publisher())));
    Multipart candidateMultipart = referenceMultipart.copy();
    assertStatus("candidate helm component upload", 204,
        send(config.nexusPlus.componentUpload(config.helmHosted(), candidateMultipart).POST(candidateMultipart.publisher())));
    Multipart duplicateMultipart = referenceMultipart.copy();
    assertDuplicateRejected("candidate duplicate helm component upload",
        send(config.nexusPlus.componentUpload(config.helmHosted(), duplicateMultipart).POST(duplicateMultipart.publisher())));

    assertBody("helm chart", config.nexus.repository(config.helmHosted(), filename),
        config.nexusPlus.repository(config.helmHosted(), filename), chart);
    Exchange candidateIndex = send(config.nexusPlus.repository(config.helmHosted(), "index.yaml").GET());
    assert2xx("candidate helm index", candidateIndex);
    assertTrue(candidateIndex.bodyText().contains(filename), "candidate Helm index should link uploaded chart");

    Exchange candidateBrowseRoot = send(config.nexusPlus.raw(
        "/service/rest/repository/browse/" + config.helmHosted() + "/").GET());
    assert2xx("candidate helm browse root", candidateBrowseRoot);
    assertTrue(candidateBrowseRoot.bodyText().contains(name + "/"),
        "candidate Helm browse root should expose chart directory");
    assertTrue(candidateBrowseRoot.bodyText().contains("index.yaml"),
        "candidate Helm browse root should expose index.yaml");
    assertFalse(candidateBrowseRoot.bodyText().contains(filename),
        "candidate Helm browse root should not expose chart packages flat");

    Exchange candidateBrowseVersion = send(config.nexusPlus.raw(
        "/service/rest/repository/browse/" + config.helmHosted() + "/" + name + "/" + version + "/").GET());
    assert2xx("candidate helm browse version", candidateBrowseVersion);
    assertTrue(candidateBrowseVersion.bodyText().contains(filename),
        "candidate Helm browse version should expose uploaded chart package");
    assertTrue(candidateBrowseVersion.bodyText().contains("/repository/" + config.helmHosted() + "/" + filename),
        "candidate Helm browse version should link to repository package path");
  }

  private static Map<String, SpecShape> supportedSpecs(byte[] body) throws Exception {
    Set<String> supported = Set.of(
        "maven2", "npm", "pypi", "helm", "raw", "yum", "nuget", "rubygems");
    Map<String, SpecShape> result = new LinkedHashMap<>();
    for (Map<String, Object> spec : MAPPER.readValue(body, LIST_MAP)) {
      String format = String.valueOf(spec.get("format"));
      if (supported.contains(format)) {
        result.put(format, SpecShape.from(spec));
      }
    }
    return result;
  }

  private static void assertBody(String label, HttpRequest.Builder referenceRequest,
      HttpRequest.Builder candidateRequest, byte[] expected) throws Exception {
    Exchange reference = send(referenceRequest.GET());
    Exchange candidate = send(candidateRequest.GET());
    assertEquals(reference.status(), candidate.status(), label + " status");
    assert2xx(label + " candidate", candidate);
    assertArrayEquals(expected, reference.body(), label + " reference body");
    assertArrayEquals(expected, candidate.body(), label + " candidate body");
  }

  private static void ensureNexusRepositories(CompatConfig config) throws Exception {
    if (!nexusRepositoryExists(config.nexus, config.mavenHosted())) {
      assertStatus("create Nexus Maven hosted", 201, send(config.nexus.raw("/service/rest/v1/repositories/maven/hosted")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("""
              {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"allow"},"maven":{"versionPolicy":"RELEASE","layoutPolicy":"STRICT"}}
              """.formatted(config.mavenHosted())))));
    }
    if (!nexusRepositoryExists(config.nexus, config.npmHosted())) {
      assertStatus("create Nexus npm hosted", 201, send(config.nexus.raw("/service/rest/v1/repositories/npm/hosted")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(hostedJson(config.npmHosted())))));
    }
    if (!nexusRepositoryExists(config.nexus, config.pypiHosted())) {
      assertStatus("create Nexus PyPI hosted", 201, send(config.nexus.raw("/service/rest/v1/repositories/pypi/hosted")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(hostedJson(config.pypiHosted())))));
    }
    if (!nexusRepositoryExists(config.nexus, config.helmHosted())) {
      assertStatus("create Nexus Helm hosted", 201, send(config.nexus.raw("/service/rest/v1/repositories/helm/hosted")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(hostedJson(config.helmHosted())))));
    }
  }

  private static void ensureKkRepoRepositories(CompatConfig config) throws Exception {
    ensureKkRepoHosted(config, config.mavenHosted(), "maven2-hosted",
        """
            {"versionPolicy":"RELEASE","layoutPolicy":"STRICT","writePolicy":"ALLOW"}
            """);
    ensureKkRepoHosted(config, config.npmHosted(), "npm-hosted", "{\"writePolicy\":\"ALLOW\"}");
    ensureKkRepoHosted(config, config.pypiHosted(), "pypi-hosted", "{\"writePolicy\":\"ALLOW\"}");
    ensureKkRepoHosted(config, config.helmHosted(), "helm-hosted", "{\"writePolicy\":\"ALLOW\"}");
  }

  private static void ensureKkRepoHosted(
      CompatConfig config, String name, String recipe, String hosted) throws Exception {
    String payload = """
        {"name":"%s","recipe":"%s","online":true,"blobStoreName":"default","strictContentTypeValidation":true,"hosted":%s}
        """.formatted(name, recipe, hosted);
    if (nexusPlusRepositoryExists(config.nexusPlus, name)) {
      assert2xx("update kkrepo " + name, send(config.nexusPlus.raw("/internal/repositories/" + name)
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(payload))));
    } else {
      assert2xx("create kkrepo " + name, send(config.nexusPlus.raw("/internal/repositories")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload))));
    }
  }

  private static boolean nexusRepositoryExists(Endpoint endpoint, String name) throws Exception {
    Exchange exchange = send(endpoint.raw("/service/rest/v1/repositories").GET());
    assert2xx("Nexus repositories", exchange);
    return exchange.bodyText().contains("\"name\" : \"" + name + "\"")
        || exchange.bodyText().contains("\"name\":\"" + name + "\"");
  }

  private static boolean nexusPlusRepositoryExists(Endpoint endpoint, String name) throws Exception {
    Exchange exchange = send(endpoint.raw("/internal/repositories").GET());
    assert2xx("kkrepo repositories", exchange);
    return exchange.bodyText().contains("\"name\":\"" + name + "\"");
  }

  private static String hostedJson(String name) {
    return """
        {"name":"%s","online":true,"storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"allow"}}
        """.formatted(name);
  }

  private static byte[] wheel(String name, String version, String filename) throws Exception {
    String distInfo = name.replace('-', '_') + "-" + version + ".dist-info/";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      zip(zip, distInfo + "METADATA", """
          Metadata-Version: 2.1
          Name: %s
          Version: %s
          Summary: component upload fixture
          Requires-Python: >=3.8

          """.formatted(name, version));
      zip(zip, distInfo + "WHEEL", """
          Wheel-Version: 1.0
          Generator: kkrepo-compat-test
          Root-Is-Purelib: true
          Tag: py3-none-any

          """);
      zip(zip, distInfo + "RECORD", "");
    }
    return out.toByteArray();
  }

  private static void zip(ZipOutputStream zip, String name, String body) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(body.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static byte[] chart(String name, String version) throws Exception {
    ByteArrayOutputStream gzipBytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBytes);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      putTarEntry(tar, name + "/Chart.yaml", """
          apiVersion: v2
          name: %s
          description: component upload fixture
          version: %s
          appVersion: "1.0.0"
          """.formatted(name, version));
      putTarEntry(tar, name + "/templates/configmap.yaml", """
          apiVersion: v1
          kind: ConfigMap
          metadata:
            name: component-upload
          data:
            message: hello
          """);
    }
    return gzipBytes.toByteArray();
  }

  private static void putTarEntry(TarArchiveOutputStream tar, String name, String body) throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }

  private static byte[] tarGz(String entryName, byte[] content) throws Exception {
    ByteArrayOutputStream gzipBytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBytes);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      TarArchiveEntry entry = new TarArchiveEntry(entryName);
      entry.setSize(content.length);
      tar.putArchiveEntry(entry);
      tar.write(content);
      tar.closeArchiveEntry();
    }
    return gzipBytes.toByteArray();
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body());
  }

  private static void assertStatus(String label, int expected, Exchange exchange) {
    assertEquals(expected, exchange.status(), label + " status; body=" + exchange.bodyText());
  }

  private static void assert2xx(String label, Exchange exchange) {
    assertTrue(exchange.status() >= 200 && exchange.status() < 300,
        label + " expected 2xx but got " + exchange.status() + "; body=" + exchange.bodyText());
  }

  private static void assertDuplicateRejected(String label, Exchange exchange) {
    assertTrue(exchange.status() >= 400,
        label + " expected duplicate rejection but got " + exchange.status() + "; body=" + exchange.bodyText());
    assertTrue(exchange.bodyText().toLowerCase().contains("already exists")
            || exchange.bodyText().toLowerCase().contains("forbids"),
        label + " duplicate response should explain the existing asset; body=" + exchange.bodyText());
  }

  private record Exchange(int status, byte[] body) {
    String bodyText() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private record SpecShape(
      String format,
      boolean multipleUpload,
      List<FieldShape> componentFields,
      List<FieldShape> assetFields) {
    @SuppressWarnings("unchecked")
    static SpecShape from(Map<String, Object> spec) {
      return new SpecShape(
          String.valueOf(spec.get("format")),
          Boolean.TRUE.equals(spec.get("multipleUpload")),
          fieldShapes((List<Map<String, Object>>) spec.getOrDefault("componentFields", List.of())),
          fieldShapes((List<Map<String, Object>>) spec.getOrDefault("assetFields", List.of())));
    }

    static List<FieldShape> fieldShapes(List<Map<String, Object>> fields) {
      List<FieldShape> result = new ArrayList<>();
      for (Map<String, Object> field : fields) {
        result.add(new FieldShape(
            String.valueOf(field.get("name")),
            String.valueOf(field.get("type")),
            Boolean.TRUE.equals(field.get("optional"))));
      }
      result.sort(Comparator.comparing(FieldShape::name));
      return result;
    }
  }

  private record FieldShape(String name, String type, boolean optional) {}

  private static final class Multipart {
    private final String boundary = "kkrepo-upload-" + System.nanoTime();
    private final List<Part> parts = new ArrayList<>();

    Multipart field(String name, String value) {
      parts.add(new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8)));
      return this;
    }

    Multipart file(String name, String filename, String contentType, byte[] body) {
      parts.add(new Part(name, filename, contentType, body));
      return this;
    }

    Multipart copy() {
      Multipart copy = new Multipart();
      copy.parts.addAll(parts);
      return copy;
    }

    HttpRequest.Builder headers(HttpRequest.Builder builder) {
      return builder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
    }

    HttpRequest.BodyPublisher publisher() {
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Part part : parts) {
          out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
          out.write(("Content-Disposition: form-data; name=\"" + part.name() + "\"").getBytes(StandardCharsets.UTF_8));
          if (part.filename() != null) {
            out.write(("; filename=\"" + part.filename() + "\"").getBytes(StandardCharsets.UTF_8));
          }
          out.write("\r\n".getBytes(StandardCharsets.UTF_8));
          if (part.contentType() != null) {
            out.write(("Content-Type: " + part.contentType() + "\r\n").getBytes(StandardCharsets.UTF_8));
          }
          out.write("\r\n".getBytes(StandardCharsets.UTF_8));
          out.write(part.body());
          out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArray(out.toByteArray());
      } catch (Exception e) {
        throw new IllegalStateException("Failed to build multipart body", e);
      }
    }

    private record Part(String name, String filename, String contentType, byte[] body) {}
  }

  private record CompatConfig(
      Endpoint nexus,
      Endpoint nexusPlus,
      boolean setupEnabled,
      String mavenHosted,
      String npmHosted,
      String pypiHosted,
      String helmHosted) {
    static CompatConfig load() {
      Endpoint nexus = new Endpoint(
          CompatDefaults.nexusBaseUrl(),
          CompatDefaults.nexusUsername(),
          CompatDefaults.nexusPassword());
      Endpoint nexusPlus = new Endpoint(
          CompatDefaults.nexusPlusBaseUrl(),
          CompatDefaults.nexusPlusUsername(),
          CompatDefaults.nexusPlusPassword());
      boolean setup = Boolean.parseBoolean(
          setting("compat.upload.setup.enabled", "COMPAT_UPLOAD_SETUP_ENABLED").orElse("true"));
      return new CompatConfig(
          nexus,
          nexusPlus,
          setup,
          setting("compat.upload.mavenHosted", "COMPAT_UPLOAD_MAVEN_HOSTED").orElse("maven-releases"),
          setting("compat.upload.npmHosted", "COMPAT_UPLOAD_NPM_HOSTED").orElse("npm-hosted"),
          setting("compat.upload.pypiHosted", "COMPAT_UPLOAD_PYPI_HOSTED").orElse("pypi-hosted"),
          setting("compat.upload.helmHosted", "COMPAT_UPLOAD_HELM_HOSTED").orElse("helm-hosted"));
    }

    boolean configured() {
      return nexus.baseUrl().isPresent() && nexusPlus.baseUrl().isPresent();
    }
  }

  private record Endpoint(Optional<String> baseUrl, Optional<String> username, Optional<String> password) {
    HttpRequest.Builder raw(String path) {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl.orElseThrow() + path))
          .timeout(Duration.ofSeconds(90));
      addAuth(builder);
      return builder;
    }

    HttpRequest.Builder repository(String repository, String path) {
      return raw("/repository/" + repository + "/" + path);
    }

    HttpRequest.Builder componentUpload(String repository, Multipart multipart) {
      return multipart.headers(raw("/service/rest/v1/components?repository=" + repository));
    }

    private void addAuth(HttpRequest.Builder builder) {
      if (username.isPresent() && password.isPresent()) {
        String token = Base64.getEncoder().encodeToString(
            (username.get() + ":" + password.get()).getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + token);
      }
    }
  }

  private static Optional<String> urlSetting(String property, String env) {
    return setting(property, env).map(ComponentUploadBlackBoxCompatibilityTest::stripTrailingSlash);
  }

  private static Optional<String> setting(String property, String env) {
    String sys = System.getProperty(property);
    if (sys != null && !sys.isBlank()) return Optional.of(sys.trim());
    String value = System.getenv(env);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
  }

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
    return result;
  }
}
