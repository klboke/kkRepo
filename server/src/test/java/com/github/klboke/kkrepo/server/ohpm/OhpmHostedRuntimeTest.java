package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.server.BlobStoresController;
import com.github.klboke.kkrepo.server.KkRepoApplication;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

class OhpmHostedRuntimeTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PACKAGE = "@scope/example";
  private static final byte[] CONTENT = "deterministic HAR content".getBytes(StandardCharsets.UTF_8);
  @TempDir Path directory;

  @Test
  void postgresHostedWorksAcrossReplicas() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:12")
        .withDatabaseName("kkrepo").withUsername("kkrepo").withPassword("kkrepo")) {
      database.start();
      verify("postgresql", database.getJdbcUrl());
    }
  }

  @Test
  void mysqlHostedWorksAcrossReplicas() throws Exception {
    try (var database = new MySQLContainer("mysql:8.0")
        .withDatabaseName("kkrepo").withUsername("kkrepo").withPassword("kkrepo")) {
      database.start();
      verify("mysql", database.getJdbcUrl()
          + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC");
    }
  }

  private void verify(String databaseType, String jdbcUrl) throws Exception {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("server.port", "0");
    properties.put("management.server.port", "0");
    properties.put("spring.datasource.url", jdbcUrl);
    properties.put("spring.datasource.username", "kkrepo");
    properties.put("spring.datasource.password", "kkrepo");
    properties.put("spring.datasource.hikari.maximum-pool-size", "8");
    properties.put("kkrepo.database.type", databaseType);
    properties.put("kkrepo.storage.file.base-dir", directory.toString());
    properties.put("kkrepo.security.encryption.credential-secret", "ohpm-runtime-credential-secret");
    properties.put("kkrepo.security.encryption.api-key-payload-secret", "ohpm-runtime-api-key-secret");
    properties.put("kkrepo.catalog-cache.refresh-interval-ms", "3600000");
    properties.put("kkrepo.catalog-cache.initial-delay-ms", "3600000");
    properties.put("kkrepo.catalog-cache.jdbc.initial-delay-ms", "3600000");
    properties.put("kkrepo.blob-gc.enabled", "false");
    properties.put("kkrepo.repository-index-rebuild.enabled", "false");
    properties.put("kkrepo.maven.metadata-rebuild.enabled", "false");
    properties.put("kkrepo.storage.file.temp-cleanup-enabled", "false");
    try (var first = start(properties); var second = start(properties); var client = HttpClient.newHttpClient()) {
      assertEquals(0, first.getBean(jakarta.servlet.MultipartConfigElement.class).getFileSizeThreshold());
      var security = first.getBean(SecurityManagementService.class);
      security.initializeAdmin(new AdminBootstrapCommand("12345678", "12345678", false));
      first.getBean(BlobStoresController.class).create(new BlobStoresController.BlobStoreRequest(
          "default", "file", "file", null, null, null, null, directory.resolve("blobs").toString(),
          null, null, null));
      first.getBean(RepositoryService.class).create(new CreateCommand(
          "ohpm", "ohpm-hosted", true, "default", false,
          new HostedSettings("ALLOW_ONCE", null, null), null, null, null, null, null));
      second.getBean(BlobStorageRegistry.class).refreshAll();
      String token = security.createApiKey(new ApiKeyCommand("GenericToken", "Local", "admin",
          "OHPM integration", "ACTIVE", List.of(), null, null, null)).token();
      String firstUrl = url(first) + "/repository/ohpm/";
      String secondUrl = url(second) + "/repository/ohpm/";
      assertStatus(client, "GET", firstUrl + "-/ping", null, null, 200);
      assertStatus(client, "POST", secondUrl + "stream/@scope%2fexample", null, null, 401);
      assertStatus(client, "GET", secondUrl + "@scope%2fexample", null, null, 401);
      assertStatus(client, "PUT", firstUrl + "@scope%2fexample", "GenericToken.invalid", publication("1.0.0"), 401);
      var runtime = first.getBean(RepositoryRuntimeRegistry.class).resolve("ohpm").orElseThrow();
      var assets = first.getBean(AssetDao.class);
      assertTrue(assets.listAssetsByPrefix(runtime.id(), "").isEmpty());

      var publishFirst = client.sendAsync(request("PUT", firstUrl + "@scope%2fexample", token, publication("1.0.0")),
          HttpResponse.BodyHandlers.ofString());
      var publishSecond = client.sendAsync(request("PUT", secondUrl + "@scope/example", token, publication("2.0.0")),
          HttpResponse.BodyHandlers.ofString());
      for (var pending : List.of(publishFirst, publishSecond)) {
        var response = pending.get(30, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode(), response.body());
      }
      assertStatus(client, "PUT", firstUrl + "@scope%2fexample", token, publication("1.0.0"), 400);
      var metadata = assertStatus(client, "GET", secondUrl + "@scope%2fexample", token, null, 200);
      var root = JSON.readTree(metadata.body());
      assertEquals(2, root.path("versions").size());
      String download = root.path("versions").path("1.0.0").path("dist").path("tarball").textValue();
      assertTrue(download.startsWith(secondUrl));
      var bytes = client.send(request("GET", download, token, null), HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, bytes.statusCode());
      assertArrayEquals(CONTENT, bytes.body());
      var cached = client.send(HttpRequest.newBuilder(URI.create(secondUrl + "@scope/example"))
          .header("Authorization", token).header("If-None-Match", metadata.headers().firstValue("ETag").orElseThrow())
          .GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(304, cached.statusCode(), cached.body());

      assertStatus(client, "POST", firstUrl + "-/package/@scope%2fexample/dist-tags/beta",
          token, "{\"version\":\"1.0.0\"}", 200);
      assertStatus(client, "PUT", secondUrl + "-/package/@scope%2fexample/dist-tags/beta",
          token, "{\"version\":\"2.0.0\"}", 200);
      assertEquals("2.0.0", JSON.readTree(assertStatus(client, "GET", firstUrl + "@scope/example",
          token, null, 200).body()).path("dist-tags").path("beta").textValue());
      assertStatus(client, "DELETE", firstUrl + "@scope%2fexample@1.0.0",
          token, "{\"version\":\"2.0.0\"}", 400);
      var tagging = client.sendAsync(request("POST", secondUrl + "-/package/@scope%2fexample/dist-tags/race",
          token, "{\"version\":\"1.0.0\"}"), HttpResponse.BodyHandlers.ofString());
      var deleting = client.sendAsync(request("DELETE", firstUrl + "@scope%2fexample",
          token, "{\"version\":\"1.0.0\"}"), HttpResponse.BodyHandlers.ofString());
      var tagged = tagging.get(30, TimeUnit.SECONDS);
      assertTrue(tagged.statusCode() == 200 || tagged.statusCode() == 400, tagged.body());
      assertEquals(200, deleting.get(30, TimeUnit.SECONDS).statusCode());
      var afterDelete = JSON.readTree(assertStatus(client, "GET", secondUrl + "@scope/example",
          token, null, 200).body());
      assertFalse(afterDelete.path("dist-tags").has("race"));
      assertStatus(client, "GET", download, token, null, 404);
      assertStatus(client, "DELETE", secondUrl + "@scope%2fexample", token, "{\"version\":\"\"}", 200);
      assertStatus(client, "GET", firstUrl + "@scope/example", token, null, 404);
      assertStatus(client, "PUT", secondUrl + "@scope%2fexample", token, publication("3.0.0"), 200);
      assertEquals(1, JSON.readTree(assertStatus(client, "GET", firstUrl + "@scope/example",
          token, null, 200).body()).path("versions").size());
      var duplicateFirst = client.sendAsync(request("PUT", firstUrl + "@scope/example", token, publication("5.0.0")),
          HttpResponse.BodyHandlers.ofString());
      var duplicateSecond = client.sendAsync(request("PUT", secondUrl + "@scope/example", token, publication("5.0.0")),
          HttpResponse.BodyHandlers.ofString());
      var duplicateStatuses = List.of(duplicateFirst.get(30, TimeUnit.SECONDS).statusCode(),
          duplicateSecond.get(30, TimeUnit.SECONDS).statusCode()).stream().sorted().toList();
      assertEquals(List.of(200, 400), duplicateStatuses);
      assertStatus(client, "DELETE", firstUrl + "@scope/example", token, "{\"version\":\"5.0.0\"}", 200);

      verifyRollback(first, false);
      verifyRollback(first, true);
      verifyHspRollback(first);
      assertStatus(client, "PUT", firstUrl + "@scope/example", token, hspPublication("6.0.0"), 200);
      var hspMetadata = JSON.readTree(assertStatus(client, "GET", secondUrl + "@scope/example",
          token, null, 200).body()).path("versions").path("6.0.0").path("dist");
      assertDownload(client, hspMetadata.path("tarball").textValue(), token, CONTENT);
      assertDownload(client, hspMetadata.path("resolved_hsp").textValue(), token, new byte[] {9, 8, 7, 6});
      assertStatus(client, "PUT", secondUrl + "@scope/example", token, publication("7.0.0"), 200);
      var mixedMetadata = JSON.readTree(assertStatus(client, "GET", firstUrl + "@scope/example",
          token, null, 200).body());
      assertFalse(mixedMetadata.has("packageType"));
      assertFalse(mixedMetadata.has("hspType"));
      assertEquals("bundle_app", mixedMetadata.path("versions").path("6.0.0").path("hspType").asText());
      assertFalse(mixedMetadata.path("versions").path("7.0.0").path("dist").has("resolved_hsp"));
      assertStatus(client, "DELETE", firstUrl + "@scope/example", token, "{\"version\":\"6.0.0\"}", 200);
      assertStatus(client, "GET", hspMetadata.path("tarball").textValue(), token, null, 404);
      assertStatus(client, "GET", hspMetadata.path("resolved_hsp").textValue(), token, null, 404);
      var audit = first.getBean(SecurityAuditDao.class).search(SecurityAuditDao.AuditLogQuery.sanitize(null));
      assertTrue(audit.items().stream().anyMatch(entry -> "SUCCESS".equals(entry.outcome())
          && "ohpm".equals(entry.details().get("format")) && "publish".equals(entry.details().get("operation"))));
      assertFalse(first.getBean(com.github.klboke.kkrepo.server.cleanup.CleanupPolicyCapabilities.class)
          .supportsExecute(com.github.klboke.kkrepo.core.RepositoryFormat.OHPM));
      if (Boolean.getBoolean("kkrepo.ohpm.client")) {
        verifyClient(firstUrl, token);
        var streamPackages = first.getBean(SecurityAuditDao.class)
            .search(SecurityAuditDao.AuditLogQuery.sanitize(null)).items().stream()
            .filter(entry -> "SUCCESS".equals(entry.outcome())
                && "stream_publish".equals(entry.details().get("operation")))
            .map(entry -> entry.details().get("package")).collect(java.util.stream.Collectors.toSet());
        assertTrue(streamPackages.containsAll(List.of("@kkrepo/runtime", "@kkrepo/runtime-hsp")),
            "Large HAR and HSP must succeed through the actual stream endpoint");
      }
    }
  }

  private void verifyClient(String registry, String token) throws Exception {
    Path project = Files.createDirectory(directory.resolve("client"));
    URI endpoint = URI.create(registry);
    Files.writeString(project.resolve(".ohpmrc"), """
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
        """.formatted(registry, registry, endpoint.getAuthority(), endpoint.getPath(), token, project.resolve("cache")));
    assertEquals(System.getProperty("kkrepo.ohpm.expectedVersion", "6.0.1"),
        client(project, token, "--version").trim());
    client(project, token, "ping", "--registry", registry);
    Path small = archive(project, "1.0.0", false);
    client(project, token, "publish", small.toString(), "--publish_registry", registry, "--tag", "beta");
    Path large = archive(project, "2.0.0", true);
    client(project, token, "publish", large.toString(), "--publish_registry", registry);
    Files.writeString(project.resolve("oh-package.json5"), """
        {"name":"consumer","version":"1.0.0","dependencies":{"@kkrepo/runtime":"1.0.0"}}
        """);
    client(project, token, "install", "--registry", registry);
    assertTrue(Files.exists(project.resolve("oh_modules/@kkrepo/runtime/Index.ets")));
    client(project, token, "dist-tags", "add", "@kkrepo/runtime@1.0.0", "test", "--publish_registry", registry);
    client(project, token, "dist-tags", "update", "@kkrepo/runtime@2.0.0", "test", "--publish_registry", registry);
    client(project, token, "dist-tags", "remove", "@kkrepo/runtime", "test", "--publish_registry", registry);
    client(project, token, "unpublish", "@kkrepo/runtime@1.0.0", "--publish_registry", registry);
    client(project, token, "unpublish", "@kkrepo/runtime", "--force", "--publish_registry", registry);
    try (var http = HttpClient.newHttpClient()) {
      for (boolean largeHsp : new boolean[] {false, true}) {
        String version = largeHsp ? "2.0.0" : "1.0.0";
        HspFixture fixture = hspArchive(project, version, largeHsp);
        client(project, token, "publish", fixture.archive().toString(), "--publish_registry", registry);
        var metadata = JSON.readTree(assertStatus(http, "GET", registry + "@kkrepo%2fruntime-hsp",
            token, null, 200).body()).path("versions").path(version);
        assertEquals("bundle_app", metadata.path("hspType").textValue());
        assertDownload(http, metadata.path("dist").path("tarball").textValue(), token, fixture.har());
        assertDownload(http, metadata.path("dist").path("resolved_hsp").textValue(), token, fixture.hsp());
      }
    }
    client(project, token, "unpublish", "@kkrepo/runtime-hsp", "--force", "--publish_registry", registry);
  }

  private static Path archive(Path directory, String version, boolean large) throws Exception {
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put("package/oh-package.json5", JSON.writeValueAsBytes(Map.of(
        "name", "@kkrepo/runtime", "version", version, "main", "Index.ets",
        "description", "Generated OHPM runtime fixture", "license", "Apache-2.0",
        "author", Map.of("name", "kkRepo"), "dependencies", Map.of())));
    files.put("package/Index.ets", "export const value = 42;\n".getBytes(StandardCharsets.UTF_8));
    if (large) {
      byte[] content = new byte[6 * 1024 * 1024];
      new java.util.Random(492).nextBytes(content);
      files.put("package/resources/payload.bin", content);
    }
    Path file = directory.resolve(version + ".har");
    Files.write(file, tar(files));
    return file;
  }

  private static byte[] tar(Map<String, byte[]> files) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var archive = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      for (var entry : files.entrySet()) {
        var header = new TarArchiveEntry(entry.getKey());
        header.setSize(entry.getValue().length);
        header.setModTime(0);
        archive.putArchiveEntry(header);
        archive.write(entry.getValue());
        archive.closeArchiveEntry();
      }
    }
    return bytes.toByteArray();
  }

  private record HspFixture(Path archive, byte[] har, byte[] hsp) {}

  private static HspFixture hspArchive(Path directory, String version, boolean large) throws Exception {
    var manifest = JSON.createObjectNode().put("name", "@kkrepo/runtime-hsp").put("version", version)
        .put("description", "Generated HSP protocol fixture").put("license", "Apache-2.0")
        .put("packageType", "InterfaceHar").put("types", "Index.d.ets");
    manifest.putObject("author").put("name", "kkRepo");
    manifest.putObject("dependencies");
    byte[] har = tar(Map.of("package/oh-package.json5", JSON.writeValueAsBytes(manifest),
        "package/Index.d.ets", "export declare const value: number;".getBytes(StandardCharsets.UTF_8)));
    var bytes = new java.io.ByteArrayOutputStream();
    try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
      zip.putNextEntry(new java.util.zip.ZipEntry("module.json"));
      zip.write("{\"module\":{\"name\":\"fixture\",\"type\":\"shared\"}}".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      if (large) {
        byte[] payload = new byte[6 * 1024 * 1024];
        new java.util.Random(493).nextBytes(payload);
        zip.putNextEntry(new java.util.zip.ZipEntry("resources/payload.bin"));
        zip.write(payload);
        zip.closeEntry();
      }
    }
    byte[] hsp = bytes.toByteArray();
    Path archive = directory.resolve(version + ".tgz");
    Files.write(archive, tar(Map.of("module.har", har, "module.hsp", hsp)));
    return new HspFixture(archive, har, hsp);
  }

  private static void assertDownload(HttpClient client, String url, String token, byte[] expected) throws Exception {
    assertNotNull(url);
    var response = client.send(request("GET", url, token, null), HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode());
    assertArrayEquals(expected, response.body());
  }

  private static String client(Path project, String token, String... arguments) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add(System.getProperty("kkrepo.ohpm.command", "ohpm"));
    command.addAll(List.of(arguments));
    Path log = Files.createTempFile(project, "ohpm-", ".log");
    Process process = new ProcessBuilder(command).directory(project.toFile())
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    assertTrue(finished, "OHPM client timed out");
    String output = Files.readString(log).replace(token, "<redacted>");
    assertEquals(0, process.exitValue(), output);
    return output;
  }

  private static void verifyRollback(ConfigurableApplicationContext context, boolean shared) throws Exception {
    var runtime = context.getBean(RepositoryRuntimeRegistry.class).resolve("ohpm").orElseThrow();
    var assets = context.getBean(AssetDao.class);
    var delegate = context.getBean(RawHostedService.class);
    var failing = mock(RawHostedService.class);
    var persisted = new AtomicReference<AssetBlobRecord>();
    doAnswer(call -> {
      boolean created = delegate.putInternalWithComponentFileAtBrowsePathIfAbsent(
          call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3),
          call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(7), call.getArgument(8));
      assertTrue(created);
      var asset = assets.findAssetByPath(runtime.id(), call.getArgument(1)).orElseThrow();
      persisted.set(assets.findBlobById(asset.assetBlobId()).orElseThrow());
      throw new IllegalStateException("Injected failure after blob and asset persistence");
    }).when(failing).putInternalWithComponentFileAtBrowsePathIfAbsent(any(), anyString(), any(),
        anyString(), anyMap(), anyString(), anyString(), any(), anyString());
    var service = new OhpmHostedService(assets, failing, context.getBean(TransientTransactionRetry.class),
        JSON, context.getBean(BlobStorageRegistry.class), context.getBean(ArtifactDownloadPolicy.class), 1024);
    String body = publication("4.0.0");
    if (!shared) {
      ObjectNode root = (ObjectNode) JSON.readTree(body);
      byte[] unique = "uncommitted HAR".getBytes(StandardCharsets.UTF_8);
      root.withObject("/_attachments").withObject("/@scope~1example-4.0.0.har")
          .put("length", unique.length).put("data", Base64.getEncoder().encodeToString(unique));
      root.withObject("/versions/4.0.0/dist").put("integrity", OhpmIntegrity.compute(new ByteArrayInputStream(unique)).value());
      body = JSON.writeValueAsString(root);
    }
    String payload = body;
    assertThrows(IllegalStateException.class, () -> service.publish(runtime, OhpmPackageId.parse(PACKAGE),
        new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)), "admin", "127.0.0.1", existing -> {}));
    assertNotNull(persisted.get());
    assertTrue(assets.findAssetByPath(runtime.id(), PACKAGE + "/-/" + PACKAGE + "-4.0.0.har").isEmpty());
    AssetBlobRecord blob = persisted.get();
    BlobStorage storage = context.getBean(BlobStorageRegistry.class).forBlobStoreId(blob.blobStoreId());
    assertEquals(shared, storage.exists(BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size())));
    try (var metadata = service.metadata(runtime, OhpmPackageId.parse(PACKAGE), "http://localhost/repository/ohpm", false).body()) {
      assertFalse(JSON.readTree(metadata).path("versions").has("4.0.0"));
    }
  }

  private static void verifyHspRollback(ConfigurableApplicationContext context) throws Exception {
    var runtime = context.getBean(RepositoryRuntimeRegistry.class).resolve("ohpm").orElseThrow();
    var assets = context.getBean(AssetDao.class);
    var delegate = context.getBean(RawHostedService.class);
    var failing = mock(RawHostedService.class);
    var hspBlobs = new java.util.ArrayList<AssetBlobRecord>();
    var harBlob = new AtomicReference<AssetBlobRecord>();
    doAnswer(call -> {
      boolean created = delegate.putInternalWithComponentFileAtBrowsePathIfAbsent(
          call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3),
          call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(7), call.getArgument(8));
      assertTrue(created);
      String path = call.getArgument(1);
      var asset = assets.findAssetByPath(runtime.id(), path).orElseThrow();
      var blob = assets.findBlobById(asset.assetBlobId()).orElseThrow();
      if (path.endsWith(".hsp")) {
        hspBlobs.add(blob);
        throw new org.springframework.dao.CannotAcquireLockException("Injected failure after second attachment");
      }
      harBlob.set(blob);
      return true;
    }).when(failing).putInternalWithComponentFileAtBrowsePathIfAbsent(any(), anyString(), any(),
        anyString(), anyMap(), anyString(), anyString(), any(), anyString());
    var service = new OhpmHostedService(assets, failing, context.getBean(TransientTransactionRetry.class),
        JSON, context.getBean(BlobStorageRegistry.class), context.getBean(ArtifactDownloadPolicy.class), 1024);
    String body = hspPublication("6.0.0");
    assertThrows(org.springframework.dao.CannotAcquireLockException.class, () -> service.publish(
        runtime, OhpmPackageId.parse(PACKAGE), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
        "admin", "127.0.0.1", existing -> {}));
    assertEquals(3, hspBlobs.size());
    for (String extension : List.of(".har", ".hsp")) {
      assertTrue(assets.findAssetByPath(runtime.id(), PACKAGE + "/-/" + PACKAGE + "-6.0.0" + extension).isEmpty());
    }
    BlobStorage storage = context.getBean(BlobStorageRegistry.class).forBlobStoreId(runtime.blobStoreId());
    for (var blob : hspBlobs) {
      assertFalse(storage.exists(BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size())));
    }
    var shared = harBlob.get();
    assertTrue(storage.exists(BlobReferenceCodec.reference(shared.blobRef(), shared.objectKey(), shared.sha256(), shared.size())));
    try (var metadata = service.metadata(runtime, OhpmPackageId.parse(PACKAGE), "http://localhost/repository/ohpm", false).body()) {
      assertFalse(JSON.readTree(metadata).path("versions").has("6.0.0"));
    }
  }

  private static String hspPublication(String version) throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(publication(version));
    root.put("packageType", "InterfaceHar").put("hspType", "bundle_app");
    root.withObject("/versions/" + version).put("types", "Index.d.ets");
    byte[] hsp = new byte[] {9, 8, 7, 6};
    root.withObject("/versions/" + version + "/dist")
        .put("integrity_hsp", OhpmIntegrity.compute(new ByteArrayInputStream(hsp)).value());
    root.withObject("/_attachments").putObject(PACKAGE + "-" + version + ".hsp")
        .put("length", hsp.length).put("data", Base64.getEncoder().encodeToString(hsp));
    return JSON.writeValueAsString(root);
  }

  private static String publication(String version) throws Exception {
    ObjectNode root = JSON.createObjectNode().put("name", PACKAGE).put("_id", PACKAGE);
    root.putObject("dist-tags").put("latest", version);
    root.putObject("versions").putObject(version).put("name", PACKAGE).put("version", version)
        .put("main", "Index.ets").putObject("dist")
        .put("integrity", OhpmIntegrity.compute(new ByteArrayInputStream(CONTENT)).value());
    root.putObject("_attachments").putObject(PACKAGE + "-" + version + ".har")
        .put("length", CONTENT.length).put("data", Base64.getEncoder().encodeToString(CONTENT));
    return JSON.writeValueAsString(root);
  }

  private static HttpResponse<String> assertStatus(HttpClient client, String method, String url,
      String token, String body, int status) throws Exception {
    var response = client.send(request(method, url, token, body), HttpResponse.BodyHandlers.ofString());
    assertEquals(status, response.statusCode(), response.body());
    return response;
  }

  private static HttpRequest request(String method, String url, String token, String body) {
    var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
    if (token != null) builder.header("Authorization", token);
    if (body != null) builder.header("Content-Type", "application/json");
    return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body)).build();
  }

  private static ConfigurableApplicationContext start(Map<String, Object> properties) {
    return new SpringApplicationBuilder(KkRepoApplication.class).profiles("test").run(properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
  }

  private static String url(ConfigurableApplicationContext context) {
    return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
  }
}
