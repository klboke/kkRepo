package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@EnabledIfEnvironmentVariable(named = "OHPM_COMPAT_ENABLED", matches = "true")
class OhpmRepositoryBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private static final String PACKAGE = "@kkrepo/blackbox";
  private static final String BOUNDARY = "kkrepo-ohpm-blackbox-boundary";
  @TempDir Path directory;
  private final List<String> repositories = new ArrayList<>();
  private String base, admin, user, token, hosted, proxy, group;
  private String clientInput;
  private int clientInvocations;

  @BeforeEach
  void createIsolatedRepositoriesAndPublisher() throws Exception {
    base = CompatDefaults.nexusPlusBaseUrl().orElseThrow(
        () -> new IllegalStateException("Set KKREPO_COMPAT_BASE_URL to the disposable candidate")).replaceAll("/+$", "");
    admin = "Basic " + Base64.getEncoder().encodeToString((CompatDefaults.nexusPlusUsername().orElse("admin")
        + ":" + CompatDefaults.nexusPlusPassword().orElse("12345678")).getBytes(StandardCharsets.UTF_8));
    String suffix = UUID.randomUUID().toString().substring(0, 12);
    user = "ohpm-" + suffix;
    admin("POST", "/internal/security/users", Map.of("source", "Local", "userId", user,
        "firstName", "OHPM", "lastName", "Compatibility", "password", UUID.randomUUID().toString(),
        "status", "ACTIVE", "roles", List.of("nx-admin")), 200);
    var issued = admin("POST", "/internal/security/api-keys", Map.of("domain", "GenericToken",
        "ownerSource", "Local", "ownerUserId", user, "displayName", "OHPM blackbox", "status", "ACTIVE",
        "scopes", List.of()), 200);
    token = issued.path("token").asText();
    assertFalse(token.isBlank());
    hosted = repository("hosted-" + suffix, "ohpm-hosted", Map.of("hosted", Map.of("writePolicy", "ALLOW_ONCE")));
    String upstream = System.getenv().getOrDefault("OHPM_COMPAT_UPSTREAM_BASE_URL", base).replaceAll("/+$", "");
    proxy = repository("proxy-" + suffix, "ohpm-proxy", Map.of("proxy", Map.of(
        "remoteUrl", upstream + "/repository/" + hosted + "/", "remoteBearerToken", token,
        "metadataMaxAgeMinutes", 60, "contentMaxAgeMinutes", 60, "autoBlock", false)));
    group = repository("group-" + suffix, "ohpm-group", Map.of("group", Map.of("memberNames", List.of(hosted, proxy))));
  }

  @AfterEach
  void removeOwnedResources() throws Exception {
    if (base == null || admin == null) return;
    for (String repository : repositories.reversed()) {
      if (!repository.equals(group) && !repository.equals(proxy)) {
        for (String name : List.of(PACKAGE, "@kkrepo/uploaded", "@kkrepo/imported",
            "@kkrepo/client-har", "@kkrepo/client-hsp", "@kkrepo/signed-blackbox")) {
          var removed = send("DELETE", root(repository) + name, token, JSON.writeValueAsBytes(Map.of("version", "")),
              "application/json", Map.of());
          assertTrue(removed.statusCode() == 200 || removed.statusCode() == 404, removed.body());
        }
      }
      var response = send("DELETE", base + "/internal/repositories/" + repository, admin, null, "application/json", Map.of());
      if (repository.equals(proxy) && response.statusCode() == 400
          && response.body().contains("still has components")) {
        System.out.println("OHPM cached proxy fixture retained for disposable candidate teardown: " + repository);
        continue;
      }
      assertTrue(response.statusCode() == 204 || response.statusCode() == 404, response.body());
    }
    if (user != null) {
      var response = send("DELETE", base + "/internal/security/users/Local/" + user, admin, null, "application/json", Map.of());
      assertTrue(response.statusCode() == 200 || response.statusCode() == 204 || response.statusCode() == 404, response.body());
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void hostedHarAndHspSupportNormalAndStreamingPublication(boolean hsp, boolean stream) throws Exception {
    var fixture = OhpmFixture.create(directory, PACKAGE, "1.2.3", hsp, stream);
    assertEquals(200, send("GET", root(hosted) + "-/ping", null, null, "application/json", Map.of()).statusCode());
    assertEquals(401, send("PUT", root(hosted) + PACKAGE, "GenericToken.invalid",
        JSON.writeValueAsBytes(publication(fixture, true)), "application/json", Map.of()).statusCode());
    publish(hosted, fixture, stream, 200);
    publish(hosted, fixture, stream, 400);
    JsonNode metadata = metadata(hosted, fixture.name());
    assertEquals(fixture.version(), metadata.path("dist-tags").path("latest").asText());
    verifyDownloads(metadata, fixture);
    var fetched = send("GET", root(hosted) + PACKAGE, token, null, "application/json", Map.of());
    assertEquals(304, send("GET", root(hosted) + PACKAGE, token, null, "application/json",
        Map.of("If-None-Match", fetched.headers().firstValue("ETag").orElseThrow())).statusCode());
    expect("POST", root(hosted) + "-/package/" + PACKAGE + "/dist-tags/beta", Map.of("version", "1.2.3"), 200);
    assertEquals("1.2.3", metadata(hosted, PACKAGE).path("dist-tags").path("beta").asText());
    expect("DELETE", root(hosted) + "-/package/" + PACKAGE + "/dist-tags/beta", null, 200);
    expect("DELETE", root(hosted) + PACKAGE, Map.of("version", "1.2.3"), 200);
    expect("GET", root(hosted) + PACKAGE, null, 404);
  }

  @Test
  void proxyAndGroupResolveBothFilesWithoutMixingSources() throws Exception {
    var fixture = OhpmFixture.create(directory, PACKAGE, "2.0.0", true, false);
    publish(hosted, fixture, false, 200);
    verifyDownloads(metadata(proxy, PACKAGE), fixture);
    JsonNode grouped = metadata(group, PACKAGE);
    verifyDownloads(grouped, fixture);
    String other = repository("second-" + UUID.randomUUID().toString().substring(0, 8),
        "ohpm-hosted", Map.of("hosted", Map.of("writePolicy", "ALLOW_ONCE")));
    repositories.remove(other);
    repositories.add(repositories.indexOf(group), other);
    var competing = OhpmFixture.create(directory, PACKAGE, "2.0.0", true, true);
    publish(other, competing, true, 200);
    admin("PUT", "/internal/repositories/" + group, Map.of("name", group, "recipe", "ohpm-group",
        "online", true, "blobStoreName", "default", "group", Map.of("memberNames", List.of(other, hosted, proxy))), 200);
    verifyDownloads(metadata(group, PACKAGE), competing);
    expect("PUT", root(group) + PACKAGE, publication(fixture, true), 405);
    expect("PUT", root(proxy) + PACKAGE, publication(fixture, true), 405);
    expect("DELETE", root(hosted) + PACKAGE, Map.of("version", ""), 200);
    verifyDownloads(metadata(proxy, PACKAGE), fixture);
  }

  @Test
  void managementUploadAndResumableImportUseTheSameHostedContent() throws Exception {
    var uploaded = OhpmFixture.create(directory, "@kkrepo/uploaded", "1.0.0", true, false);
    var upload = multipart("POST", base + "/service/rest/v1/components?repository=" + hosted, admin,
        Map.of("ohpm.asset", uploaded.har(), "ohpm.hsp", uploaded.hsp()),
        Map.of("ohpm.asset", "library.har", "ohpm.hsp", "library.hsp"), Map.of());
    assertEquals(204, upload.statusCode(), upload.body());
    verifyDownloads(metadata(hosted, uploaded.name()), uploaded);
    var browse = admin("GET", "/internal/browse/" + hosted + "?path=@kkrepo/uploaded/1.0.0", null, 200);
    assertEquals(2, browse.path("entries").size());
    var details = admin("GET", "/internal/browse/" + hosted
        + "/attributes?path=@kkrepo/uploaded/1.0.0/uploaded-1.0.0.har", null, 200);
    assertEquals(uploaded.name(), details.path("ohpm").path("name").asText());
    assertEquals(128, details.path("checksum").path("sha512").asText().length());
    var imported = OhpmFixture.create(directory, "@kkrepo/imported", "1.0.0", false, false);
    var job = admin("POST", "/internal/ohpm/imports", Map.of("repository", hosted, "source", "DIRECTORY"), 200);
    String endpoint = base + "/internal/ohpm/imports/" + job.path("id").asText();
    var plan = multipart("POST", endpoint + "/directory", admin, Map.of("asset", imported.har()),
        Map.of("asset", "imported.har"), Map.of("sourceKey", "imported.har"));
    assertEquals(200, plan.statusCode(), plan.body());
    String item = JSON.readTree(plan.body()).path("key").asText();
    expect("GET", root(hosted) + imported.name(), null, 404);
    for (String state : List.of("READY", "APPLYING")) {
      assertEquals(200, send("POST", endpoint + "/state", admin,
          JSON.writeValueAsBytes(Map.of("state", state)), "application/json", Map.of()).statusCode());
    }
    for (int attempt = 0; attempt < 2; attempt++) {
      var result = multipart("POST", endpoint + "/items/" + item + "/directory", admin,
          Map.of("asset", imported.har()), Map.of("asset", "imported.har"), Map.of());
      assertEquals(200, result.statusCode(), result.body());
      assertEquals("IMPORTED", JSON.readTree(result.body()).path("state").asText());
    }
    assertEquals(200, send("POST", endpoint + "/state", admin,
        JSON.writeValueAsBytes(Map.of("state", "COMPLETED")), "application/json", Map.of()).statusCode());
    verifyDownloads(metadata(group, imported.name()), imported);
  }

  @Test
  void optionalSignedLoginHonorsConfigurationReplayAndTokenScope() throws Exception {
    JsonNode configuration = admin("GET", "/internal/ohpm/login", null, 200);
    String expected = System.getenv("OHPM_COMPAT_LOGIN_ENABLED");
    if (expected != null) assertEquals(Boolean.parseBoolean(expected), configuration.path("enabled").asBoolean());
    if (!configuration.path("enabled").asBoolean()) {
      expect("POST", root(hosted) + "login", Map.of(), 404);
      return;
    }
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var key = generator.generateKeyPair();
    String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(key.getPublic().getEncoded())
        + "\n-----END PUBLIC KEY-----\n";
    var binding = admin("PUT", "/internal/ohpm/login/keys", Map.of("source", "Local", "userId", user,
        "publicKey", pem, "enabled", true), 200);
    String publishId = binding.path("publishId").asText();
    long timestamp = Instant.now().toEpochMilli();
    String nonce = UUID.randomUUID().toString().replace("-", "");
    var signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(key.getPrivate());
    signature.update(("v1-" + publishId + "-" + timestamp + "-" + nonce).getBytes(StandardCharsets.UTF_8));
    byte[] request = JSON.writeValueAsBytes(Map.of("version", "v1", "publishId", publishId,
        "timestamp", timestamp, "nonce", nonce, "signature", Base64.getEncoder().encodeToString(signature.sign())));
    var login = send("POST", root(hosted) + "login", null, request, "application/json", Map.of());
    assertEquals(200, login.statusCode());
    String signedToken = JSON.readTree(login.body()).path("token").asText();
    assertFalse(signedToken.isBlank());
    assertEquals(401, send("POST", root(hosted) + "login", null, request, "application/json", Map.of()).statusCode());
    assertEquals(401, send("GET", base + "/internal/ohpm/login", "Bearer " + signedToken,
        null, "application/json", Map.of()).statusCode());
    var fixture = OhpmFixture.create(directory, PACKAGE, "1.0.0", false, false);
    assertEquals(200, send("PUT", root(hosted) + PACKAGE, signedToken,
        JSON.writeValueAsBytes(publication(fixture, true)), "application/json", Map.of()).statusCode());
    if (Boolean.getBoolean("kkrepo.ohpm.client")) {
      String passphrase = UUID.randomUUID().toString();
      Path privateKey = OhpmSigningFixture.writePrivateKey(directory, key, passphrase);
      configureClient(hosted);
      Path config = directory.resolve(".ohpmrc");
      Files.writeString(config, Files.readString(config).replaceAll("(?m)^//[^\\n]*:_auth=.*\\n", "")
          + "publish_id=" + publishId + "\nkey_path=" + privateKey + "\n");
      var signedFixture = OhpmFixture.create(directory, "@kkrepo/signed-blackbox", "1.0.0", false, false);
      clientInput = passphrase + "\n";
      try {
        client("publish", signedFixture.archive().toString(), "--publish_registry", root(hosted));
      } finally {
        clientInput = null;
      }
      URI registry = URI.create(root(hosted));
      Files.writeString(config, Files.readString(config)
          + "//" + registry.getAuthority() + registry.getPath() + ":_auth=" + signedToken + "\n");
      Files.writeString(directory.resolve("oh-package.json5"), JSON.writeValueAsString(Map.of(
          "name", "signed-consumer", "version", "1.0.0", "dependencies", Map.of(signedFixture.name(), "1.0.0"))));
      client("install", "--registry", root(hosted));
      assertTrue(Files.exists(directory.resolve("oh_modules/" + signedFixture.name() + "/Index.ets")));
      verifyDownloads(metadata(hosted, signedFixture.name()), signedFixture);
    }
    admin("PUT", "/internal/ohpm/login/keys/" + publishId + "/enabled", Map.of("enabled", false), 200);
    assertEquals(401, send("GET", root(hosted) + PACKAGE, signedToken, null, "application/json", Map.of()).statusCode());
  }

  @Test
  void automaticCleanupIsRejectedAndProtocolDeletionRemovesHarAndHsp() throws Exception {
    admin("PUT", "/internal/repositories/" + group + "/members", Map.of("memberNames", List.of(hosted)), 200);
    var capabilities = admin("GET", "/internal/cleanup/capabilities", null, 200);
    JsonNode capability = null;
    for (var candidate : capabilities) {
      if ("ohpm".equals(candidate.path("format").asText())) capability = candidate;
    }
    assertNotNull(capability);
    assertFalse(capability.path("tryRunSupported").asBoolean(true));
    assertFalse(capability.path("executeSupported").asBoolean(true));
    var repository = admin("GET", "/internal/repositories/" + hosted, null, 200);
    var rejected = send("POST", base + "/internal/cleanup/policies", admin,
        JSON.writeValueAsBytes(Map.of("name", "ohpm-cleanup-" + user, "format", "ohpm",
            "criteria", Map.of("lastDownloadedOlderThanDays", 1),
            "repositoryIds", List.of(repository.path("id").asLong()))), "application/json", Map.of());
    assertEquals(400, rejected.statusCode(), rejected.body());
    assertTrue(rejected.body().contains("not supported"), rejected.body());

    var fixture = OhpmFixture.create(directory, PACKAGE, "1.0.0", true, false);
    publish(hosted, fixture, false, 200);
    var dist = metadata(hosted, PACKAGE).path("versions").path("1.0.0").path("dist");
    verifyDownloads(metadata(group, PACKAGE), fixture);
    expect("DELETE", root(hosted) + PACKAGE, Map.of("version", "1.0.0"), 200);
    expect("GET", root(hosted) + PACKAGE, null, 404);
    expect("GET", dist.path("tarball").asText(), null, 404);
    expect("GET", dist.path("resolved_hsp").asText(), null, 404);
    expect("GET", root(group) + PACKAGE, null, 404);

    String report = System.getenv("OHPM_CLEANUP_REPORT");
    if (report != null) {
      Files.writeString(Path.of(report), JSON.writeValueAsString(Map.of(
          "automaticCleanupRejected", true, "protocolDeletionVerified", true, "capability", capability)));
    }
  }

  @Test
  @EnabledIfSystemProperty(named = "kkrepo.ohpm.client", matches = "true")
  void actualClientPublishesInstallsAndRemovesAcrossRepositoryTypes() throws Exception {
    configureClient(hosted);
    assertEquals(System.getProperty("kkrepo.ohpm.expectedVersion", "6.0.1"), client("--version").trim());
    var fixtures = new ArrayList<OhpmFixture>();
    for (boolean hsp : List.of(false, true)) {
      for (boolean large : List.of(false, true)) {
        String name = hsp ? "@kkrepo/client-hsp" : "@kkrepo/client-har";
        String version = large ? "2.0.0" : "1.0.0";
        var fixture = OhpmFixture.create(directory, name, version, hsp, large);
        client("publish", fixture.archive().toString(), "--publish_registry", root(hosted));
        fixtures.add(fixture);
      }
    }
    for (var fixture : fixtures) {
      verifyDownloads(metadata(proxy, fixture.name()), fixture);
      for (String repository : List.of(hosted, proxy, group)) {
        configureClient(repository);
        Files.writeString(directory.resolve("oh-package.json5"), JSON.writeValueAsString(Map.of(
            "name", "consumer", "version", "1.0.0", "dependencies", Map.of(fixture.name(), fixture.version()))));
        Files.deleteIfExists(directory.resolve("oh-package-lock.json5"));
        Path modules = directory.resolve("oh_modules");
        if (Files.exists(modules)) {
          try (var paths = Files.walk(modules)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
          }
        }
        client("install", "--registry", root(repository));
        assertTrue(Files.exists(directory.resolve("oh_modules/" + fixture.name() + "/Index.ets")));
      }
    }
    configureClient(hosted);
    client("dist-tags", "add", "@kkrepo/client-har@1.0.0", "beta", "--publish_registry", root(hosted));
    client("dist-tags", "update", "@kkrepo/client-har@2.0.0", "beta", "--publish_registry", root(hosted));
    client("dist-tags", "remove", "@kkrepo/client-har", "beta", "--publish_registry", root(hosted));
    client("unpublish", "@kkrepo/client-har@1.0.0", "--publish_registry", root(hosted));
    client("unpublish", "@kkrepo/client-har", "--force", "--publish_registry", root(hosted));
    expect("GET", root(hosted) + "@kkrepo/client-har", null, 404);
  }

  private String repository(String name, String recipe, Map<String, Object> settings) throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>(Map.of(
        "name", name, "recipe", recipe, "online", true, "blobStoreName", "default", "strictContentTypeValidation", false));
    body.putAll(settings);
    admin("POST", "/internal/repositories", body, 201);
    repositories.add(name);
    return name;
  }

  private String root(String repository) {
    return base + "/repository/" + repository + "/";
  }

  private JsonNode metadata(String repository, String name) throws Exception {
    var response = expect("GET", root(repository) + name, null, 200);
    return JSON.readTree(response.body());
  }

  private void publish(String repository, OhpmFixture fixture, boolean stream, int status) throws Exception {
    if (stream) {
      var response = multipart("POST", root(repository) + "stream/" + fixture.name(), token,
          Map.of("pkg_stream", Files.readAllBytes(fixture.archive()), "metadata", JSON.writeValueAsBytes(publication(fixture, false))),
          Map.of("pkg_stream", fixture.archive().getFileName().toString()), Map.of());
      assertEquals(status, response.statusCode(), response.body());
    } else {
      expect("PUT", root(repository) + fixture.name(), publication(fixture, true), status);
    }
  }

  private static ObjectNode publication(OhpmFixture fixture, boolean attachments) throws Exception {
    ObjectNode root = JSON.createObjectNode().put("name", fixture.name()).put("_id", fixture.name());
    root.putObject("dist-tags").put("latest", fixture.version());
    var version = root.putObject("versions").putObject(fixture.version()).put("name", fixture.name())
        .put("version", fixture.version()).put("main", "Index.ets");
    var dist = version.putObject("dist").put("integrity", integrity(fixture.har()));
    if (fixture.hsp() != null) {
      root.put("packageType", "InterfaceHar").put("hspType", "bundle_app");
      version.put("types", "Index.d.ets");
      dist.put("integrity_hsp", integrity(fixture.hsp()));
    }
    if (attachments) {
      var files = root.putObject("_attachments");
      files.putObject(fixture.name() + "-" + fixture.version() + ".har")
          .put("length", fixture.har().length).put("data", Base64.getEncoder().encodeToString(fixture.har()));
      if (fixture.hsp() != null) files.putObject(fixture.name() + "-" + fixture.version() + ".hsp")
          .put("length", fixture.hsp().length).put("data", Base64.getEncoder().encodeToString(fixture.hsp()));
    }
    return root;
  }

  private void verifyDownloads(JsonNode metadata, OhpmFixture fixture) throws Exception {
    var dist = metadata.path("versions").path(fixture.version()).path("dist");
    assertEquals(integrity(fixture.har()), dist.path("integrity").asText());
    download(dist.path("tarball").asText(), fixture.har());
    if (fixture.hsp() != null) {
      assertEquals(integrity(fixture.hsp()), dist.path("integrity_hsp").asText());
      download(dist.path("resolved_hsp").asText(), fixture.hsp());
    }
  }

  private void download(String url, byte[] expected) throws Exception {
    assertEquals(URI.create(base).getAuthority(), URI.create(url).getAuthority(), "Download stays on candidate");
    var response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
        .header("Authorization", token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode());
    assertArrayEquals(expected, response.body());
  }

  private static String integrity(byte[] content) throws Exception {
    return "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(content));
  }

  private JsonNode admin(String method, String path, Object body, int status) throws Exception {
    var response = send(method, base + path, admin, body == null ? null : JSON.writeValueAsBytes(body),
        "application/json", Map.of());
    assertEquals(status, response.statusCode(), "Management request failed: " + method + " " + path);
    return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
  }

  private HttpResponse<String> expect(String method, String url, Object body, int status) throws Exception {
    var response = send(method, url, token, body == null ? null : JSON.writeValueAsBytes(body), "application/json", Map.of());
    assertEquals(status, response.statusCode(), response.body().replace(token, "<redacted>"));
    return response;
  }

  private static HttpResponse<String> send(String method, String url, String credential, byte[] body,
      String type, Map<String, String> headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Content-Type", type);
    if (credential != null) request.header("Authorization", credential);
    headers.forEach(request::header);
    return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> multipart(String method, String url, String credential, Map<String, byte[]> files,
      Map<String, String> filenames, Map<String, String> fields) throws Exception {
    var body = new ByteArrayOutputStream();
    for (var field : fields.entrySet()) part(body, field.getKey(), null, field.getValue().getBytes(StandardCharsets.UTF_8));
    for (var file : files.entrySet()) part(body, file.getKey(), filenames.get(file.getKey()), file.getValue());
    body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return send(method, url, credential, body.toByteArray(), "multipart/form-data; boundary=" + BOUNDARY, Map.of());
  }

  private static void part(ByteArrayOutputStream output, String name, String filename, byte[] content) throws Exception {
    output.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
        + (filename == null ? "" : "; filename=\"" + filename + "\"")
        + "\r\nContent-Type: " + (filename == null ? "application/json" : "application/octet-stream")
        + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    output.write(content);
    output.write("\r\n".getBytes(StandardCharsets.UTF_8));
  }

  private void configureClient(String repository) throws Exception {
    URI registry = URI.create(root(repository));
    Files.writeString(directory.resolve(".ohpmrc"), """
        registry=%s
        publish_registry=%s
        //%s%s:_auth=%s
        cache=%s
        log_level=error
        fetch_timeout=10000
        retry_times=0
        http_proxy=
        https_proxy=
        no_proxy=127.0.0.1,localhost
        use_stream_threshold_size=5
        """.formatted(registry, root(hosted), registry.getAuthority(), registry.getPath(), token,
            directory.resolve("cache-" + repository)));
  }

  private String client(String... arguments) throws Exception {
    var command = new ArrayList<String>();
    if (clientInput != null) {
      command.add("python3");
      command.add(Path.of("../scripts/ci/ohpm-signing-client.py").toAbsolutePath().normalize().toString());
    }
    command.add(System.getProperty("kkrepo.ohpm.command", "ohpm"));
    command.addAll(List.of(arguments));
    Path output = directory.resolve("client-" + clientInvocations++ + ".log");
    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        .redirectOutput(output.toFile()).start();
    if (clientInput != null) {
      process.getOutputStream().write(clientInput.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().close();
    }
    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    assertTrue(finished, "OHPM command timed out");
    String text = Files.readString(output).replace(token, "<redacted>");
    if (clientInput != null) text = text.replace(clientInput.strip(), "<redacted>");
    assertEquals(0, process.exitValue(), text);
    return text;
  }
}
