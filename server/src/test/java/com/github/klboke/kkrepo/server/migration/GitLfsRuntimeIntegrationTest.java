package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.server.BlobStoresController;
import com.github.klboke.kkrepo.server.BlobStoresController.BlobStoreRequest;
import com.github.klboke.kkrepo.server.KkRepoApplication;
import com.github.klboke.kkrepo.server.gitlfs.GitLfsUploadCleanupWorker;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapCommand;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real filter/controller/transaction integration, including the regressions found by native clients. */
class GitLfsRuntimeIntegrationTest {
  @TempDir Path temporary;

  @Test void replicaActionsMigrationAndRecoveryUseTheRunningApplication() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:12")
        .withDatabaseName("kkrepo").withUsername("kkrepo").withPassword("kkrepo")) {
      database.start();
      var properties = new LinkedHashMap<String, Object>();
      properties.put("server.port", "0");
      properties.put("management.server.port", "0");
      properties.put("spring.datasource.url", database.getJdbcUrl());
      properties.put("spring.datasource.username", database.getUsername());
      properties.put("spring.datasource.password", database.getPassword());
      properties.put("spring.datasource.hikari.maximum-pool-size", "8");
      properties.put("kkrepo.database.type", "postgresql");
      properties.put("kkrepo.storage.file.base-dir", temporary.toString());
      properties.put("kkrepo.security.encryption.credential-secret", "lfs-runtime-credential-secret-test");
      properties.put("kkrepo.security.encryption.api-key-payload-secret", "lfs-runtime-api-key-secret-test");
      properties.put("kkrepo.catalog-cache.refresh-interval-ms", "3600000");
      properties.put("kkrepo.catalog-cache.initial-delay-ms", "3600000");
      properties.put("kkrepo.catalog-cache.jdbc.initial-delay-ms", "3600000");
      properties.put("kkrepo.blob-gc.enabled", "false");
      properties.put("kkrepo.repository-index-rebuild.enabled", "false");
      properties.put("kkrepo.maven.metadata-rebuild.enabled", "false");
      properties.put("kkrepo.storage.file.temp-cleanup-enabled", "false");
      properties.put("kkrepo.security.outbound.allow-private-addresses", "true");
      try (var first = start(properties); var second = start(properties)) {
        first.getBean(SecurityManagementService.class).initializeAdmin(
            new AdminBootstrapCommand("12345678", "12345678", true));
        first.getBean(BlobStoresController.class).create(new BlobStoreRequest(
            "default", "file", "file", null, null, null, null, temporary.resolve("blobs").toString(),
            null, null, null));
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("scripts/ci/git-lfs-resilience-e2e.py"))) root = root.getParent();
        Path output = temporary.resolve("resilience.log");
        var command = new ProcessBuilder("python3", root.resolve("scripts/ci/git-lfs-resilience-e2e.py").toString())
            .redirectErrorStream(true).redirectOutput(output.toFile());
        command.environment().put("KKREPO_COMPAT_BASE_URL", url(first));
        command.environment().put("KKREPO_SECONDARY_BASE_URL", url(second));
        command.environment().put("KKREPO_COMPAT_USERNAME", "admin");
        command.environment().put("KKREPO_COMPAT_PASSWORD", "12345678");
        command.environment().put("CLIENT_E2E_ARTIFACT_DIR", temporary.toString());
        Process process = command.start();
        boolean completed = process.waitFor(120, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly();
        assertTrue(completed, "LFS HTTP test timed out");
        assertEquals(0, process.exitValue(), Files.readString(output));

        // Call the actual migration dispatcher: a valid source path publishes through the same
        // verifier/transaction as native uploads, and resume preserves its existing asset ID.
        var target = first.getBean(RepositoryService.class).create(new CreateCommand(
            "lfs-import", "gitlfs-hosted", true, "default", false,
            new HostedSettings("ALLOW_ONCE", null, null), null, null, null, null, null));
        byte[] bytes = "verified Nexus LFS import".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String oid = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var source = source("/" + oid, bytes.length);
        var writer = first.getBean(RepositoryDataMigrationWriter.class);
        var imported = writer.write(target.id(), source, new ByteArrayInputStream(bytes), "application/octet-stream", true);
        assertNotNull(imported.assetId());
        var repeated = writer.write(target.id(), source, new ByteArrayInputStream(bytes), "application/octet-stream", true);
        assertEquals(imported.assetId(), repeated.assetId());
        assertThrows(IllegalArgumentException.class, () -> writer.write(target.id(), source("/invalid", 1),
            new ByteArrayInputStream(new byte[1]), "application/octet-stream", true));
        assertThrows(IllegalArgumentException.class, () -> writer.write(target.id(), source("/" + oid, 1),
            new ByteArrayInputStream(new byte[1]), "application/octet-stream", true));

        duplicateUploadCleanupPreservesPublishedObject(first, second);
        second.getBean(GitLfsUploadCleanupWorker.class).cleanup();
        assertTrue(first.getBean(com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.class)
            .findAssetById(imported.assetId()).isPresent());
        var repository = first.getBean(RepositoryDao.class).findById(target.id()).orElseThrow();
        assertThrows(com.github.klboke.kkrepo.server.cleanup.CleanupValidationException.class,
            () -> first.getBean(com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.class)
                .scan(repository, Map.of(), 100, Instant.now()));
      }
    }
  }

  private static void duplicateUploadCleanupPreservesPublishedObject(
      ConfigurableApplicationContext first, ConfigurableApplicationContext second) throws Exception {
    var http = java.net.http.HttpClient.newHttpClient();
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    String auth = "Basic " + java.util.Base64.getEncoder().encodeToString("admin:12345678".getBytes());
    byte[] bytes = "duplicate upload must not delete published bytes".getBytes();
    String oid = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    String root = "/repository/lfs-import/";
    var actions = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    // Obtain both actions before either transfer, then let the second finish after publication.
    for (int i = 0; i < 2; i++) {
      var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(first) + root + "info/lfs/objects/batch"))
          .header("Authorization", auth).header("Content-Type", "application/vnd.git-lfs+json")
          .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of(
              "operation", "upload", "objects", java.util.List.of(Map.of("oid", oid, "size", bytes.length)))))).build();
      var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      actions.add(json.readTree(response.body()).path("objects").get(0).path("actions").path("upload"));
    }
    for (var action : actions) {
      var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(second) + root + oid))
          .header("Authorization", auth)
          .header("X-KkRepo-Lfs-Upload", action.path("header").path("X-KkRepo-Lfs-Upload").asText())
          .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
      var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
    }
    var dao = first.getBean(com.github.klboke.kkrepo.persistence.jdbc.api.GitLfsDao.class);
    var published = dao.find(actions.get(0).path("header").path("X-KkRepo-Lfs-Upload").asText()).orElseThrow();
    var garbage = dao.find(actions.get(1).path("header").path("X-KkRepo-Lfs-Upload").asText()).orElseThrow();
    assertEquals("PUBLISHED", published.state());
    assertEquals("GARBAGE", garbage.state());
    assertNotEquals(published.objectKey(), garbage.objectKey());
    first.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update(
        "UPDATE gitlfs_upload SET expires_at = ? WHERE upload_id = ?",
        java.sql.Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")), garbage.id());
    second.getBean(GitLfsUploadCleanupWorker.class).cleanup();
    var storage = first.getBean(com.github.klboke.kkrepo.server.maven.BlobStorageRegistry.class)
        .forBlobStoreId(garbage.blobStoreId());
    assertFalse(storage.exists(com.github.klboke.kkrepo.server.blob.BlobReferenceCodec.reference(
        garbage.blobRef(), garbage.objectKey(), garbage.oid(), garbage.size())));
    var download = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(first) + root + oid))
        .header("Authorization", auth).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, download.statusCode());
    assertArrayEquals(bytes, download.body());
  }

  private static RepositoryDataMigrationAssetRecord source(String path, long size) {
    return new RepositoryDataMigrationAssetRecord(null, 1, "source", null, path,
        PersistenceHashes.pathHash(path), RepositoryFormat.GITLFS, null, path, null, "gitlfs",
        "application/octet-stream", size, null, null, null, null, null, null, null,
        "pending", 0, null, null, null, null, null, null, Map.of(), Instant.now());
  }

  private static ConfigurableApplicationContext start(Map<String, Object> properties) {
    return new SpringApplicationBuilder(KkRepoApplication.class).profiles("test").run(properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
  }
  private static String url(ConfigurableApplicationContext context) {
    return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
  }
}
