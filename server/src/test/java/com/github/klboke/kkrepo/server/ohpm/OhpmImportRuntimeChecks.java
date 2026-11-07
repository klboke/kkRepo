package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.ItemState;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.JobState;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.Source;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.ProxySettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

final class OhpmImportRuntimeChecks {
  private static final ObjectMapper JSON = new ObjectMapper();

  static void verify(ConfigurableApplicationContext first, ConfigurableApplicationContext second,
      HttpClient client, String token, Path directory) throws Exception {
    String firstBase = "http://127.0.0.1:" + ((WebServerApplicationContext) first).getWebServer().getPort();
    String secondBase = "http://127.0.0.1:" + ((WebServerApplicationContext) second).getWebServer().getPort();
    String endpoint = "/internal/ohpm/imports";
    assertEquals(401, request(client, firstBase + endpoint + "?repository=ohpm", "GET", null, null).statusCode());
    var created = request(client, firstBase + endpoint, "POST", token,
        "{\"repository\":\"ohpm\",\"source\":\"DIRECTORY\"}");
    assertEquals(200, created.statusCode(), created.body());
    String id = JSON.readTree(created.body()).path("id").asText();
    var imports = first.getBean(OhpmImportService.class);
    var replica = second.getBean(OhpmImportService.class);
    var runtime = first.getBean(RepositoryRuntimeRegistry.class).resolve("ohpm").orElseThrow();
    var assets = first.getBean(AssetDao.class);
    byte[] har = har("1.0.0", false, "original");
    var preview = multipart(client, firstBase + endpoint + "/" + id + "/directory", token, har);
    assertEquals(200, preview.statusCode(), preview.body());
    var planned = imports.report(id).getFirst();
    assertEquals(ItemState.PLANNED, planned.state());
    assertTrue(assets.findAssetByPath(runtime.id(), "@kkrepo/imported").isEmpty(), "Dry run cannot create metadata");
    assertEquals(planned, replica.report(id).getFirst());
    assertEquals(planned, replica.planDirectory(id, "library.har", new ByteArrayInputStream(har),
        null, false, null, () -> {}));
    assertThrows(OhpmException.class, () -> replica.applyDirectory(id, planned.key(),
        new ByteArrayInputStream(har), null, false, null, "admin", "127.0.0.1", () -> {}));
    imports.transition(id, JobState.READY, () -> {});
    assertEquals(JobState.APPLYING, replica.transition(id, JobState.APPLYING, () -> {}).state());

    OhpmImportDao faultyDao = spy(org.springframework.test.util.AopTestUtils
        .<OhpmImportDao>getUltimateTargetObject(first.getBean(OhpmImportDao.class)));
    doThrow(new IllegalStateException("Injected checkpoint failure")).doCallRealMethod()
        .when(faultyDao).result(eq(id), eq(planned.key()), any(), anyString());
    var failing = new OhpmImportService(faultyDao, first.getBean(OhpmImportSource.class),
        first.getBean(OhpmHostedService.class), first.getBean(RepositoryRuntimeRegistry.class),
        first.getBean(TransientTransactionRetry.class), JSON, first.getBean(RepositoryDao.class));
    var failed = failing.applyDirectory(id, planned.key(), new ByteArrayInputStream(har), null, false, null,
        "admin", "127.0.0.1", () -> {});
    assertEquals(ItemState.FAILED, failed.state());
    assertTrue(assets.findAssetByPath(runtime.id(), "@kkrepo/imported").isEmpty(),
        "Checkpoint failure must roll back publication and metadata");
    assertTrue(assets.findAssetByPath(runtime.id(), "@kkrepo/imported/-/@kkrepo/imported-1.0.0.har").isEmpty());
    var imported = replica.applyDirectory(id, planned.key(), new ByteArrayInputStream(har), null,
        false, null, "admin", "127.0.0.1", () -> {});
    assertEquals(ItemState.IMPORTED, imported.state());
    assertEquals(imported, imports.applyDirectory(id, planned.key(), new ByteArrayInputStream(har), null,
        false, null, "admin", "127.0.0.1", () -> {}));
    assertEquals(JobState.COMPLETED, imports.transition(id, JobState.COMPLETED, () -> {}).state());
    var report = request(client, secondBase + endpoint + "/" + id, "GET", token, null);
    assertEquals(200, report.statusCode(), report.body());
    assertEquals("IMPORTED", JSON.readTree(report.body()).path("items").get(0).path("state").asText());
    assertEquals("MANUAL", JSON.readTree(report.body()).path("manualActions").get(0).path("state").asText());
    var metadata = request(client, secondBase + "/repository/ohpm/@kkrepo/imported", "GET", token, null);
    assertEquals(200, metadata.statusCode(), metadata.body());
    assertTrue(JSON.readTree(metadata.body()).path("dist-tags").isEmpty(), "Import must not invent tags");

    var duplicateJob = imports.create("ohpm", Source.DIRECTORY, null, "admin");
    var duplicate = replica.planDirectory(duplicateJob.id(), "library.har", new ByteArrayInputStream(har),
        null, false, null, () -> {});
    imports.transition(duplicateJob.id(), JobState.READY, () -> {});
    imports.transition(duplicateJob.id(), JobState.APPLYING, () -> {});
    var repeated = multipart(client, secondBase + endpoint + "/" + duplicateJob.id()
        + "/items/" + duplicate.key() + "/directory", token, har);
    assertEquals(200, repeated.statusCode(), repeated.body());
    assertEquals("SKIPPED", JSON.readTree(repeated.body()).path("state").asText());

    var changedMetadata = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(metadata.body());
    ((com.fasterxml.jackson.databind.node.ObjectNode) changedMetadata.path("versions").path("1.0.0"))
        .put("description", "Different source metadata with identical archive bytes");
    var metadataJob = imports.create("ohpm", Source.DIRECTORY, null, "admin");
    var metadataConflict = imports.planDirectory(metadataJob.id(), "library.har", new ByteArrayInputStream(har),
        null, false, com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageMetadata.parse(
            OhpmPackageId.parse("@kkrepo/imported"), changedMetadata), () -> {});
    assertEquals(ItemState.MANUAL, metadataConflict.state());
    assertTrue(metadataConflict.detail().contains("publication metadata"));

    var conflictJob = imports.create("ohpm", Source.DIRECTORY, null, "admin");
    byte[] changed = har("1.0.0", false, "changed");
    var conflict = replica.planDirectory(conflictJob.id(), "changed.har", new ByteArrayInputStream(changed),
        null, false, null, () -> {});
    assertEquals(ItemState.MANUAL, conflict.state());
    imports.transition(conflictJob.id(), JobState.READY, () -> {});
    replica.transition(conflictJob.id(), JobState.APPLYING, () -> {});
    assertEquals(ItemState.MANUAL, imports.applyDirectory(conflictJob.id(), conflict.key(),
        new ByteArrayInputStream(changed), null, false, null, "admin", "127.0.0.1", () -> {}).state());
    assertThrows(OhpmException.class, () -> imports.transition(conflictJob.id(), JobState.COMPLETED, () -> {}));

    byte[] interfaceHar = har("2.0.0", true, "interface");
    byte[] hsp = new byte[] {1, 2, 3};
    byte[] bundle = tar(Map.of("library.har", interfaceHar, "library.hsp", hsp));
    var bundleJob = imports.create("ohpm", Source.DIRECTORY, null, "admin");
    var bundlePlan = imports.planDirectory(bundleJob.id(), "library.tgz", new ByteArrayInputStream(bundle),
        null, true, null, () -> {});
    assertEquals(ItemState.PLANNED, bundlePlan.state());
    imports.transition(bundleJob.id(), JobState.READY, () -> {});
    replica.transition(bundleJob.id(), JobState.APPLYING, () -> {});
    byte[] mutated = tar(Map.of("library.har", interfaceHar, "library.hsp", new byte[] {4, 5, 6}));
    assertEquals(ItemState.MANUAL, replica.applyDirectory(bundleJob.id(), bundlePlan.key(),
        new ByteArrayInputStream(mutated), null, true, null, "admin", "127.0.0.1", () -> {}).state());
    assertTrue(assets.findAssetByPath(runtime.id(), "@kkrepo/imported/-/@kkrepo/imported-2.0.0.hsp").isEmpty());
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<OhpmImportDao.Item> firstRequest = () -> imports.applyDirectory(bundleJob.id(), bundlePlan.key(),
          new ByteArrayInputStream(bundle), null, true, null, "admin", "127.0.0.1", () -> {});
      Callable<OhpmImportDao.Item> secondRequest = () -> replica.applyDirectory(bundleJob.id(), bundlePlan.key(),
          new ByteArrayInputStream(bundle), null, true, null, "admin", "127.0.0.1", () -> {});
      for (var result : executor.invokeAll(java.util.List.of(firstRequest, secondRequest))) {
        assertEquals(ItemState.IMPORTED, result.get().state());
      }
    }
    var stored = first.getBean(OhpmHostedService.class).download(runtime,
        "@kkrepo/imported/-/@kkrepo/imported-2.0.0.hsp", false);
    try (var content = stored.body()) {
      assertArrayEquals(hsp, content.readAllBytes());
    }
    var security = first.getBean(com.github.klboke.kkrepo.server.security.SecurityManagementService.class);
    String restricted = security.createApiKey(
        new com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand(
            "GenericToken", "Local", "ohpm-uploader", "Import access test", "ACTIVE",
            java.util.List.of(), null, null, null)).token();
    assertEquals(403, request(client, secondBase + endpoint + "/" + id, "GET", restricted, null).statusCode());
    security.saveRole(new com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleCommand(
        "ohpm-uploader", "Local", "OHPM uploader", "", false, java.util.List.of(),
        java.util.List.of("nx-admin"), Map.of()));
    assertEquals(200, request(client, secondBase + endpoint + "/" + id, "GET", restricted, null).statusCode());
    security.saveRole(new com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleCommand(
        "ohpm-uploader", "Local", "OHPM uploader", "", false, java.util.List.of(), java.util.List.of(), Map.of()));
    assertEquals(403, request(client, secondBase + endpoint + "/" + id, "GET", restricted, null).statusCode());
    verifyRegistry(first, second, secondBase, token, directory);
  }

  private static HttpResponse<String> multipart(HttpClient client, String url, String token, byte[] har) throws Exception {
    String boundary = "ohpm-import-runtime";
    var body = new java.io.ByteArrayOutputStream();
    body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"sourceKey\"\r\n\r\nlibrary.har\r\n"
        + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"asset\"; filename=\"library.har\"\r\n"
        + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    body.write(har);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static void verifyRegistry(ConfigurableApplicationContext first, ConfigurableApplicationContext second,
      String secondBase, String token, Path directory) throws Exception {
    String name = "@kkrepo/from-registry";
    byte[] har = har(name, "1.0.0", false, "export const value = 42;");
    byte[] interfaceHar = har(name, "2.0.0", true, "interface");
    byte[] hsp = {7, 8, 9};
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String remote = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/";
    var sourceMetadata = JSON.createObjectNode().put("name", name).put("_id", name);
    sourceMetadata.putObject("dist-tags").put("latest", "1.0.0").put("beta", "2.0.0");
    var versions = sourceMetadata.putObject("versions");
    versions.putObject("1.0.0").put("name", name).put("version", "1.0.0").put("main", "Index.ets")
        .put("_ohpmVersion", "6.0.1").putObject("dist")
        .put("integrity", OhpmIntegrity.compute(new ByteArrayInputStream(har)).value()).put("tarball", remote + "file.har");
    versions.putObject("2.0.0").put("name", name).put("version", "2.0.0").put("types", "Index.d.ets")
        .put("packageType", "InterfaceHar").put("hspType", "bundle_app").putObject("dist")
        .put("integrity", OhpmIntegrity.compute(new ByteArrayInputStream(interfaceHar)).value())
        .put("integrity_hsp", OhpmIntegrity.compute(new ByteArrayInputStream(hsp)).value())
        .put("tarball", remote + "interface.har").put("resolved_hsp", remote + "file.hsp");
    versions.set("3.0.0", versions.path("1.0.0").deepCopy());
    ((com.fasterxml.jackson.databind.node.ObjectNode) versions.path("3.0.0")).put("version", "3.0.0");
    var corrupt = new AtomicBoolean(false);
    var tagMoved = new AtomicBoolean(false);
    upstream.createContext("/", exchange -> {
      try (exchange) {
        if (!"import-source-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          exchange.sendResponseHeaders(401, -1);
          return;
        }
        String path = exchange.getRequestURI().getPath();
        byte[] body;
        if (path.equals("/" + name)) {
          var metadata = sourceMetadata.deepCopy();
          if (tagMoved.get()) ((com.fasterxml.jackson.databind.node.ObjectNode) metadata.path("dist-tags"))
              .put("latest", "2.0.0");
          body = JSON.writeValueAsBytes(metadata);
        } else if (path.equals("/file.har")) {
          body = corrupt.get() ? new byte[] {0, 1} : har;
        } else if (path.equals("/interface.har")) {
          body = interfaceHar;
        } else if (path.equals("/file.hsp")) {
          body = hsp;
        } else {
          exchange.sendResponseHeaders(404, -1);
          return;
        }
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      }
    });
    upstream.start();
    try {
      first.getBean(RepositoryService.class).create(new CreateCommand("ohpm-import-source", "ohpm-proxy",
          true, "default", false, null,
          new ProxySettings(remote, 60, 5, false, null, null, false, "import-source-token", true),
          null, null, null, null));
      var imports = first.getBean(OhpmImportService.class);
      var replica = second.getBean(OhpmImportService.class);
      var job = imports.create("ohpm", Source.REGISTRY, "ohpm-import-source", "admin");
      assertEquals(3, replica.sourceVersions(job.id(), name).size());
      var mismatchedJob = imports.create("ohpm", Source.REGISTRY, "ohpm-import-source", "admin");
      assertEquals(ItemState.MANUAL, imports.planRegistry(mismatchedJob.id(), name, "3.0.0", () -> {}).state(),
          "A source version cannot import an archive whose manifest identifies a different version");
      var planned = imports.planRegistry(job.id(), name, "1.0.0", () -> {});
      assertEquals(ItemState.PLANNED, planned.state(), planned.detail());
      var hspPlan = replica.planRegistry(job.id(), name, "2.0.0", () -> {});
      assertEquals(ItemState.PLANNED, hspPlan.state(), hspPlan.detail());
      var registry = first.getBean(RepositoryRuntimeRegistry.class);
      long targetId = registry.resolve("ohpm").orElseThrow().id();
      long sourceId = registry.resolve("ohpm-import-source").orElseThrow().id();
      var assets = first.getBean(AssetDao.class);
      assertTrue(assets.findAssetByPath(targetId, name).isEmpty());
      assertTrue(assets.findAssetByPath(sourceId, name).isEmpty(), "Importer must not populate proxy cache");
      imports.transition(job.id(), JobState.READY, () -> {});
      replica.transition(job.id(), JobState.APPLYING, () -> {});
      corrupt.set(true);
      assertEquals(ItemState.MANUAL, replica.applyRegistry(job.id(), planned.key(), "admin", "127.0.0.1", () -> {}).state());
      assertTrue(assets.findAssetByPath(targetId, name).isEmpty());
      corrupt.set(false);
      tagMoved.set(true);
      assertEquals(ItemState.MANUAL, replica.applyRegistry(job.id(), planned.key(), "admin", "127.0.0.1", () -> {}).state());
      assertTrue(assets.findAssetByPath(targetId, name).isEmpty(), "Changed source tags cannot bypass the sealed plan");
      tagMoved.set(false);
      assertEquals(ItemState.IMPORTED, replica.applyRegistry(job.id(), planned.key(), "admin", "127.0.0.1", () -> {}).state());
      assertEquals(ItemState.IMPORTED, imports.applyRegistry(job.id(), hspPlan.key(), "admin", "127.0.0.1", () -> {}).state());
      assertEquals(JobState.COMPLETED, replica.transition(job.id(), JobState.COMPLETED, () -> {}).state());
      var target = registry.resolveFreshById(targetId).orElseThrow();
      var hosted = first.getBean(OhpmHostedService.class);
      try (var metadata = hosted.metadata(target, OhpmPackageId.parse(name),
          secondBase + "/repository/ohpm/", false).body()) {
        assertEquals("1.0.0", JSON.readTree(metadata).path("dist-tags").path("latest").asText());
      }
      for (var file : Map.of(".har", interfaceHar, ".hsp", hsp).entrySet()) {
        String path = OhpmPackageId.parse(name).downloadPath(name + "-2.0.0" + file.getKey());
        try (var downloaded = hosted.download(target, path, false).body()) {
          assertArrayEquals(file.getValue(), downloaded.readAllBytes());
        }
      }
      tagMoved.set(true);
      var conflictJob = imports.create("ohpm", Source.REGISTRY, "ohpm-import-source", "admin");
      var conflictingTag = imports.planRegistry(conflictJob.id(), name, "2.0.0", () -> {});
      assertEquals(ItemState.MANUAL, conflictingTag.state());
      assertTrue(conflictingTag.detail().contains("tag conflicts"));
      if (Boolean.getBoolean("kkrepo.ohpm.client")) {
        Path project = Files.createDirectory(directory.resolve("import-client"));
        String url = secondBase + "/repository/ohpm/";
        URI endpoint = URI.create(url);
        Files.writeString(project.resolve(".ohpmrc"), """
            registry=%s
            //%s%s:_auth=%s
            cache=%s
            http_proxy=
            https_proxy=
            no_proxy=127.0.0.1,localhost
            """.formatted(url, endpoint.getAuthority(), endpoint.getPath(), token, project.resolve("cache")));
        Files.writeString(project.resolve("oh-package.json5"),
            "{\"name\":\"consumer\",\"version\":\"1.0.0\",\"dependencies\":{\"@kkrepo/from-registry\":\"1.0.0\"}}");
        OhpmHostedRuntimeTest.client(project, token, "install", "--registry", url);
        assertTrue(Files.exists(project.resolve("oh_modules/@kkrepo/from-registry/Index.ets")));
      }
    } finally {
      upstream.stop(0);
    }
  }

  private static HttpResponse<String> request(HttpClient client, String url, String method, String token, String body)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", url.contains("/internal/") ? "Bearer " + token : token);
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static byte[] har(String version, boolean hsp, String content) throws Exception {
    return har("@kkrepo/imported", version, hsp, content);
  }

  private static byte[] har(String name, String version, boolean hsp, String content) throws Exception {
    return tar(Map.of("package/oh-package.json5", JSON.writeValueAsBytes(hsp
        ? Map.of("name", name, "version", version, "types", "Index.d.ets", "packageType", "InterfaceHar")
        : Map.of("name", name, "version", version, "main", "Index.ets",
            "numericConfig", Map.of("minimum", 1, "weight", 1.5))),
        "package/Index.ets", content.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] tar(Map<String, byte[]> files) throws Exception {
    var output = new java.io.ByteArrayOutputStream();
    try (var archive = new TarArchiveOutputStream(new GzipCompressorOutputStream(output))) {
      for (var file : files.entrySet()) {
        var header = new TarArchiveEntry(file.getKey());
        header.setSize(file.getValue().length);
        header.setModTime(0);
        archive.putArchiveEntry(header);
        archive.write(file.getValue());
        archive.closeArchiveEntry();
      }
    }
    return output.toByteArray();
  }
}
