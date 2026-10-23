package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.server.docker.DockerManifestStore;
import com.github.klboke.kkrepo.server.docker.DockerMigrationMetadataCleanup;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.DockerSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryValidationException;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;

/** Issue #430: use the real migration writer, Browse deletion, cleanup, and repository deletion. */
public final class DockerMigrationCleanupSmokeChecks {
  private DockerMigrationCleanupSmokeChecks() {}

  public static void exercise(ConfigurableApplicationContext first, ConfigurableApplicationContext second,
      Database firstDatabase, Database secondDatabase) throws Exception {
    var repositories = first.getBean(RepositoryService.class);
    for (String name : List.of("migration-empty-docker", "migration-kept-docker")) {
      repositories.create(new CreateCommand(name, "docker-hosted", true, "smoke-file", true,
          new HostedSettings("ALLOW", null, null), null, null,
          new DockerSettings(false, null, null), null, null));
    }
    var runtime = first.getBean(RepositoryRuntimeRegistry.class).resolve("migration-empty-docker").orElseThrow();
    var kept = first.getBean(RepositoryRuntimeRegistry.class).resolve("migration-kept-docker").orElseThrow();
    byte[] body = ("""
        {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
         "config":{"mediaType":"application/vnd.oci.empty.v1+json","digest":"sha256:%s","size":0},
         "layers":[]}
        """).formatted("0".repeat(64)).getBytes(StandardCharsets.UTF_8);
    String digest = DockerDigest.sha256(body).value();
    var writer = first.getBean(RepositoryDataMigrationWriter.class);
    for (int round = 0; round < 4; round++) {
      for (String reference : List.of("latest", "1.0", digest)) {
        writer.write(runtime.id(), source(reference, body.length), new ByteArrayInputStream(body),
            DockerConstants.MEDIA_TYPE_OCI_MANIFEST, true);
      }
    }
    var shared = writer.write(kept.id(), source("latest", body.length), new ByteArrayInputStream(body),
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST, true);
    var jdbc = firstDatabase;
    Runnable attemptDeletion = () -> second.getBean(RepositoryService.class).delete("migration-empty-docker");
    assertThrows(RepositoryValidationException.class, attemptDeletion::run);
    assertEquals(3, count(jdbc, "asset", runtime.id()), "live registry must prevent repair");
    assertEquals(3, count(jdbc, "component", runtime.id()), "four migrations are path-idempotent");
    var store = first.getBean(DockerManifestStore.class);
    assertEquals(1, store.deleteBrowseReference(runtime, "team/app", digest));
    assertEquals(1, store.deleteBrowseReference(runtime, "team/app", "latest"));
    assertEquals(digest, store.getManifest(runtime, "team/app", "1.0").manifest().digest());
    assertThrows(RepositoryValidationException.class, attemptDeletion::run);
    assertEquals(3, count(jdbc, "asset", runtime.id()), "remaining tag must keep all metadata");
    assertEquals(1, store.deleteBrowseReference(runtime, "team/app", "1.0"));

    // Reproduces the reporter's shape before repair: Browse/index empty, aliases and components remain.
    assertEquals(0, count(jdbc, "docker_manifest", runtime.id()));
    assertEquals(2, count(jdbc, "asset", runtime.id()));
    assertEquals(3, count(jdbc, "component", runtime.id()));
    assertEquals(1, jdbc.queryForObject("""
        SELECT COUNT(*) FROM component c WHERE c.repository_id = ?
          AND NOT EXISTS (SELECT 1 FROM asset a WHERE a.component_id = c.id)
        """, Integer.class, runtime.id()));
    // Do not discard metadata from failed/incomplete migration jobs, even with an empty index.
    jdbc.update("""
        INSERT INTO migration_job (source_nexus_version, source_data_path, status, options_json)
        VALUES ('3.96.3', 'issue-430', 'finished', '{}')
        """);
    long jobId = jdbc.queryForObject("SELECT MAX(id) FROM migration_job", Long.class);
    jdbc.update("""
        INSERT INTO repository_data_migration_repository
          (migration_job_id, source_repository_name, target_repository_name, target_repository_id,
           format, status, page_size, options_json)
        VALUES (?, 'docker-source', 'migration-empty-docker', ?, 'docker', 'finished_with_failures', 100, '{}')
        """, jobId, runtime.id());
    assertThrows(RepositoryValidationException.class, attemptDeletion::run);
    assertEquals(2, count(jdbc, "asset", runtime.id()));
    jdbc.update("UPDATE repository_data_migration_repository SET status = 'finished' WHERE migration_job_id = ?", jobId);

    // A delete on a sibling replica must not wait for repository/asset rows owned by a writer.
    var transactions = first.getBean(TransientTransactionRetry.class);
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      transactions.execute("hold repository for concurrent writer", () -> {
        assertTrue(first.getBean(DockerRegistryDao.class).lockEmptyRepositoryForMigrationCleanup(runtime.id()));
        await(executor.submit(() -> assertThrows(RepositoryValidationException.class, attemptDeletion::run)));
        assertEquals(2, count(jdbc, "asset", runtime.id()));
        return null;
      });
      transactions.execute("hold assets for concurrent writer", () -> {
        jdbc.queryForList("SELECT id FROM asset WHERE repository_id = ? FOR UPDATE", Long.class, runtime.id());
        await(executor.submit(() -> assertThrows(RepositoryValidationException.class, attemptDeletion::run)));
        assertEquals(2, count(jdbc, "asset", runtime.id()));
        return null;
      });
      // Manifest insertion on another replica takes a conflicting FK lock on repository.
      long aliasId = first.getBean(AssetDao.class).findAssetByPath(
          runtime.id(), "v2/team/app/manifests/latest").orElseThrow().id();
      var insertion = transactions.execute("hold empty registry against manifest insertion", () -> {
        assertTrue(first.getBean(DockerRegistryDao.class).lockEmptyRepositoryForMigrationCleanup(runtime.id()));
        var started = new java.util.concurrent.CountDownLatch(1);
        var pending = executor.submit(() -> {
          started.countDown();
          secondDatabase.update("""
              INSERT INTO docker_manifest
                (repository_id, image_name, image_name_hash, digest_algorithm, digest, digest_hash,
                 media_type, asset_id, size, attributes_json)
              VALUES (?, 'team/app', ?, 'sha256', ?, ?, ?, ?, ?, '{}')
              """, runtime.id(), PersistenceHashes.sha256("team/app"), digest,
              PersistenceHashes.sha256(digest), DockerConstants.MEDIA_TYPE_OCI_MANIFEST, aliasId, body.length);
        });
        try {
          assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
          assertThrows(java.util.concurrent.TimeoutException.class,
              () -> pending.get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
        return pending;
      });
      await(insertion);
      assertThrows(RepositoryValidationException.class, attemptDeletion::run);
      assertEquals(2, count(jdbc, "asset", runtime.id()));
      jdbc.update("DELETE FROM docker_manifest WHERE repository_id = ?", runtime.id());
    }

    // An exception after removing an asset rolls back that removal and its outbox/reference changes.
    var realAssets = second.getBean(AssetDao.class);
    AssetDao failingAssets = mock(AssetDao.class, org.mockito.AdditionalAnswers.delegatesTo(realAssets));
    doThrow(new IllegalStateException("injected GC failure")).when(failingAssets)
        .markBlobDeletedIfUnreferenced(anyLong(), anyString());
    var failingCleanup = new DockerMigrationMetadataCleanup(second.getBean(DockerRegistryDao.class),
        failingAssets, second.getBean(ComponentDao.class));
    assertThrows(IllegalStateException.class, () -> second.getBean(TransientTransactionRetry.class).execute(
        "injected cleanup failure", () -> {
      failingCleanup.cleanupBeforeRepositoryDeletion(runtime.id());
      return null;
    }));
    assertEquals(2, count(jdbc, "asset", runtime.id()));
    // A non-migration component must still block deletion, and the attempted repair must roll back.
    jdbc.update("""
        INSERT INTO component (repository_id, format, name, coordinate_hash, attributes_json)
        VALUES (?, 'docker', 'unrelated', ?, '{}')
        """, runtime.id(), PersistenceHashes.sha256("unrelated"));
    assertThrows(RepositoryValidationException.class, attemptDeletion::run);
    assertEquals(2, count(jdbc, "asset", runtime.id()));
    assertEquals(4, count(jdbc, "component", runtime.id()));
    jdbc.update("DELETE FROM component WHERE repository_id = ? AND name = 'unrelated'", runtime.id());
    attemptDeletion.run();
    assertEquals(0, count(jdbc, "asset", runtime.id()));
    assertEquals(0, count(jdbc, "component", runtime.id()));
    assertEquals(1, count(jdbc, "asset", kept.id()));
    assertNull(jdbc.queryForObject("SELECT deleted_at FROM asset_blob WHERE id = ?", java.sql.Timestamp.class, shared.assetBlobId()),
        "a shared blob must not be marked deleted");
    assertEquals(digest, second.getBean(DockerManifestStore.class).getManifest(kept, "team/app", "latest").manifest().digest());
    try (var content = second.getBean(DockerManifestStore.class).serveManifest(kept, "team/app", "latest", false).body()) {
      assertArrayEquals(body, content.readAllBytes(), "shared manifest bytes remain readable");
    }
    assertTrue(first.getBean(RepositoryDao.class).findByName("migration-empty-docker").isEmpty());
  }

  private static void await(java.util.concurrent.Future<?> future) {
    try {
      future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError("cleanup should skip a busy row without waiting", e);
    }
  }

  private static int count(Database jdbc, String table, long repositoryId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE repository_id = ?",
        Integer.class, repositoryId);
  }

  private static RepositoryDataMigrationAssetRecord source(String reference, int size) {
    Instant now = Instant.now();
    String path = "v2/team/app/manifests/" + reference;
    return new RepositoryDataMigrationAssetRecord(1L, 2L, reference, reference, path,
        PersistenceHashes.pathHash(path), RepositoryFormat.DOCKER, null, "team/app", reference,
        "MANIFEST", DockerConstants.MEDIA_TYPE_OCI_MANIFEST, (long) size, "source-blob-ref",
        now.minusSeconds(60), null, now.minusSeconds(120), now.minusSeconds(60), "nexus-admin", "127.0.0.1",
        null, 0, null, null, null, null, null, null, Map.of(), now.minusSeconds(180));
  }
  /** Database fixtures are supplied by the bootstrap smoke test; this helper imports only APIs. */
  public interface Database {
    int update(String sql, Object... arguments);
    <T> T queryForObject(String sql, Class<T> type, Object... arguments);
    <T> List<T> queryForList(String sql, Class<T> type, Object... arguments);
  }
}
