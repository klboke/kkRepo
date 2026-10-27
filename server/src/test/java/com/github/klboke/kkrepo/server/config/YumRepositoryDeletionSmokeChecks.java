package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.*;
import static com.github.klboke.kkrepo.server.config.BrowseDirectoryDeletionSmokeChecks.request;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.*;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.repositories.RepositoryValidationException;
import com.github.klboke.kkrepo.server.yum.YumMetadataCleanup;
import com.github.klboke.kkrepo.server.yum.YumService;
import com.github.klboke.kkrepo.server.yum.YumTestFixtures;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Issue #453: real two-node deletion, old metadata, queue fencing, rollback and shared blobs. */
final class YumRepositoryDeletionSmokeChecks {
  static void exercise(ConfigurableApplicationContext first, ConfigurableApplicationContext second) throws Exception {
    var repositories = first.getBean(RepositoryService.class);
    var assets = first.getBean(AssetDao.class);
    var otherRepositories = second.getBean(RepositoryService.class);
    second.getBean(BlobStorageRegistry.class).refreshAll();
    create(repositories, "delete-yum-never-used", 0);
    assertEquals(204, request(second, "DELETE", "/internal/repositories/delete-yum-never-used", null, true).statusCode());
    for (boolean legacy : List.of(false, true)) {
      for (int depth : List.of(0, 1, 2)) {
        String name = "delete-yum-" + depth + "-" + legacy;
        create(repositories, name, depth);
        var runtime = first.getBean(RepositoryRuntimeRegistry.class).resolve(name).orElseThrow();
        String root = "release/arch/".substring(0, depth == 0 ? 0 : depth == 1 ? 8 : 13);
        String rpm = root + "Packages/demo-1.2.3-4.x86_64.rpm";
        first.getBean(YumService.class).put(runtime, rpm,
            new ByteArrayInputStream(YumTestFixtures.rpmFixture()), "application/x-rpm", "admin", null);
        drain(first);
        assertEquals(400, request(second, "DELETE", "/internal/repositories/" + name, null, true).statusCode());
        assertEquals(200, request(first, "DELETE", "/internal/browse/" + name + "?path=" + rpm, null, true).statusCode());
        drain(second);
        assertTrue(assets.listAssetsByPrefix(runtime.id(), "").stream().noneMatch(a -> a.path().endsWith(".rpm")));
        assertTrue(assets.findAssetByPath(runtime.id(), root + "repodata/repomd.xml").isPresent());
        if (depth == 1 && !legacy) {
          String route = "/internal/browse/" + name + "/directory-deletion";
          var preview = BrowseDirectoryDeletionSmokeChecks.preview(first, route, root + "repodata");
          assertEquals(200, request(first, "POST", route,
              BrowseDirectoryDeletionSmokeChecks.command(preview), true).statusCode());
          assertEquals(0, assets.countAssetsByRepositoryId(runtime.id()));
          drain(second);
          assertEquals(4, assets.countAssetsByRepositoryId(runtime.id()));
        }
        if (depth == 2) {
          repositories.update(name, new UpdateCommand(null, null, null, null, null, null,
              null, null, null, null, null, null, new YumSettings(0)));
          drain(first);
          assertTrue(assets.findAssetByPath(runtime.id(), "repodata/repomd.xml").isPresent());
        }
        if (legacy) {
          // v1.3.4 has the same bytes/components, but no asset provenance attribute.
          for (var asset : assets.listAssetsByPrefix(runtime.id(), "")) {
            assets.updateAssetAttributes(asset.id(), Map.of("path", asset.path()));
          }
        }
        var queue = first.getBean(RepositoryIndexRebuildDao.class);
        queue.enqueue(runtime.id(), RepositoryIndexRebuildDao.YUM_METADATA);
        assertEquals(204, request(second, "DELETE", "/internal/repositories/" + name, null, true).statusCode());
        drain(first);
        assertTrue(first.getBean(RepositoryDao.class).findById(runtime.id()).isEmpty());
        assertEquals(0, assets.countAssetsByRepositoryId(runtime.id()));
        assertFalse(queue.hasPending(runtime.id(), RepositoryIndexRebuildDao.YUM_METADATA, ""));
      }
    }

    create(repositories, "delete-yum-guarded", 0);
    create(repositories, "delete-yum-shared", 0);
    var runtime = first.getBean(RepositoryRuntimeRegistry.class).resolve("delete-yum-guarded").orElseThrow();
    var shared = first.getBean(RepositoryRuntimeRegistry.class).resolve("delete-yum-shared").orElseThrow();
    var raw = first.getBean(RawHostedService.class);
    first.getBean(RepositoryIndexRebuildDao.class).enqueue(runtime.id(), RepositoryIndexRebuildDao.YUM_METADATA);
    drain(first);
    var metadata = assets.findAssetByPath(runtime.id(), "repodata/repomd.xml").orElseThrow();
    byte[] bytes = raw.get(runtime, metadata.path(), false).body().readAllBytes();
    raw.put(shared, "keep.xml", new ByteArrayInputStream(bytes), "application/xml", "admin", null);
    var kept = assets.findAssetByPath(shared.id(), "keep.xml").orElseThrow();
    assertEquals(metadata.assetBlobId(), kept.assetBlobId());

    // More than one cleanup page; a late user file must roll back every prior deletion/tombstone.
    for (int i = 0; i < 130; i++) {
      raw.putGenerated(runtime, "old-" + i + "/repodata/repomd.xml",
          new ByteArrayInputStream(bytes), "application/xml", "system", null);
    }
    for (String path : List.of("readme.txt", "repodata/comps.xml", "repodata/repomd.xml.asc")) {
      raw.put(runtime, path, new ByteArrayInputStream(bytes), "application/xml", "admin", null);
      long before = assets.countAssetsByRepositoryId(runtime.id());
      assertThrows(RepositoryValidationException.class, () -> otherRepositories.delete(runtime.name()));
      assertEquals(before, assets.countAssetsByRepositoryId(runtime.id()));
      assertTrue(assets.findAssetById(metadata.id()).isPresent());
      raw.delete(runtime, path);
    }
    // Overwriting a generated path with user content must clear provenance, even for the SAME blob.
    raw.put(runtime, metadata.path(), new ByteArrayInputStream(bytes), "application/xml", "admin", null);
    assertEquals(false, assets.findAssetById(metadata.id()).orElseThrow().attributes().get(YumMetadataCleanup.GENERATED_ATTRIBUTE));
    assertThrows(RepositoryValidationException.class, () -> otherRepositories.delete(runtime.name()));
    raw.putGenerated(runtime, metadata.path(), new ByteArrayInputStream(bytes), "application/xml", "system", null);

    // Busy parent/component/asset locks refuse deletion without partially cleaning any metadata.
    try (var executor = Executors.newSingleThreadExecutor()) {
      for (String lock : List.of("repository", "component", "asset", "blob")) {
        transaction(first).executeWithoutResult(status -> {
          switch (lock) {
            case "repository" -> assertTrue(first.getBean(YumMetadataCleanupDao.class).lockRepository(runtime.id()));
            case "component" -> first.getBean(ComponentDao.class).findByIdForUpdate(metadata.componentId()).orElseThrow();
            case "asset" -> assets.findAssetByIdForUpdate(metadata.id()).orElseThrow();
            case "blob" -> assets.lockLiveBlobById(metadata.assetBlobId()).orElseThrow();
          }
          try {
            executor.submit(() -> assertThrows(RepositoryValidationException.class,
                () -> otherRepositories.delete(runtime.name()))).get(15, TimeUnit.SECONDS);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
          assertTrue(assets.findAssetById(metadata.id()).isPresent());
        });
      }
      // While cleanup owns the parent, the sibling worker skips its durable marker. Rollback
      // restores all assets and keeps that marker available for the next worker cycle.
      first.getBean(RepositoryIndexRebuildDao.class).enqueue(runtime.id(), RepositoryIndexRebuildDao.YUM_METADATA);
      transaction(first).executeWithoutResult(status -> {
        first.getBean(YumMetadataCleanup.class).cleanupBeforeRepositoryDeletion(runtime.id());
        try {
          executor.submit(() -> drain(second)).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
          throw new AssertionError(e);
        }
        assertTrue(first.getBean(RepositoryIndexRebuildDao.class).hasPending(runtime.id(), RepositoryIndexRebuildDao.YUM_METADATA, ""));
        status.setRollbackOnly();
      });
    }
    assertTrue(assets.findAssetById(metadata.id()).isPresent());
    otherRepositories.delete(runtime.name());
    drain(first);
    assertTrue(assets.findAssetById(kept.id()).isPresent());
    assertEquals(Boolean.TRUE, transaction(first).execute(status -> assets.lockLiveBlobById(kept.assetBlobId()).isPresent()));
    assertArrayEquals(bytes, raw.get(shared, "keep.xml", false).body().readAllBytes());
  }

  private static void create(RepositoryService service, String name, int depth) {
    service.create(new CreateCommand(name, "yum-hosted", true, "smoke-file", false,
        new HostedSettings("ALLOW", null, null), null, null, null, null, null,
        null, null, null, new YumSettings(depth)));
  }

  private static void drain(ConfigurableApplicationContext app) {
    transaction(app).executeWithoutResult(status -> ReflectionTestUtils.invokeMethod(
        app.getBean("repositoryIndexRebuildWorker"), "runYumBatch"));
  }

  private static TransactionTemplate transaction(ConfigurableApplicationContext app) {
    var transaction = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return transaction;
  }
}
