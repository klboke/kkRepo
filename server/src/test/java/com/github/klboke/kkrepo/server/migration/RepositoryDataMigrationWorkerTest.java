package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryAssetMetadata;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryAssetPage;
import com.github.klboke.kkrepo.persistence.jdbc.api.MigrationJobDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao.AssetClaim;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao.TargetAssetRef;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationJobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationRepositoryRecord;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class RepositoryDataMigrationWorkerTest {
  @Test
  void batchProgressTargetsIncludeRepositoryAndJobRows() {
    RepositoryDataMigrationWorker.BatchProgressTargets targets =
        RepositoryDataMigrationWorker.batchProgressTargets(List.of(
            claim(10L, 100L, "com/acme/app/1.0/app-1.0.jar"),
            claim(11L, 100L, "com/acme/lib/1.0/lib-1.0.jar"),
            claim(10L, 100L, "com/acme/app/1.0/app-1.0.pom")));

    assertEquals(List.of(10L, 11L), targets.repositoryJobIds());
    assertEquals(List.of(100L), targets.jobIds());
  }

  @Test
  void continuousDrainRefillsCompletedSlotBeforeSlowWorkFinishes() throws Exception {
    ExecutorService migrationExecutor = Executors.newFixedThreadPool(2);
    ExecutorService coordinator = Executors.newSingleThreadExecutor();
    ConcurrentLinkedQueue<Integer> queued = new ConcurrentLinkedQueue<>(List.of(1, 2, 3));
    CopyOnWriteArrayList<Integer> refreshed = new CopyOnWriteArrayList<>();
    CountDownLatch slowStarted = new CountDownLatch(1);
    CountDownLatch releaseSlow = new CountDownLatch(1);
    CountDownLatch replacementStarted = new CountDownLatch(1);
    try {
      Future<Boolean> migration = coordinator.submit(() -> RepositoryDataMigrationWorker.drainContinuously(
          () -> 2,
          capacity -> {
            List<Integer> claimed = new ArrayList<>(capacity);
            while (claimed.size() < capacity) {
              Integer item = queued.poll();
              if (item == null) {
                break;
              }
              claimed.add(item);
            }
            return claimed;
          },
          migrationExecutor,
          item -> {
            if (item == 1) {
              slowStarted.countDown();
              try {
                if (!releaseSlow.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("slow task was not released");
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
            } else if (item == 3) {
              replacementStarted.countDown();
            }
          },
          refreshed::addAll));

      assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
      assertTrue(
          replacementStarted.await(5, TimeUnit.SECONDS),
          "a completed slot should be refilled while the slow task is still running");
      releaseSlow.countDown();
      assertTrue(migration.get(5, TimeUnit.SECONDS));
      assertEquals(Set.of(1, 2, 3), Set.copyOf(refreshed));
    } finally {
      releaseSlow.countDown();
      coordinator.shutdownNow();
      migrationExecutor.shutdownNow();
    }
  }

  @Test
  void cargoDynamicConfigIsNotMigratedAsSourceBlob() {
    assertFalse(RepositoryDataMigrationWorker.shouldMigrateSourceAsset(RepositoryFormat.CARGO, "config.json"));
    assertFalse(RepositoryDataMigrationWorker.shouldMigrateSourceAsset(RepositoryFormat.CARGO, "/config.json"));
    assertTrue(RepositoryDataMigrationWorker.shouldMigrateSourceAsset(
        RepositoryFormat.CARGO,
        "crates/demo/0.1.0/download"));
    assertTrue(RepositoryDataMigrationWorker.shouldMigrateSourceAsset(
        RepositoryFormat.CARGO,
        "de/mo/demo"));
    assertTrue(RepositoryDataMigrationWorker.shouldMigrateSourceAsset(RepositoryFormat.NPM, "config.json"));
  }

  @Test
  void pubDerivedApiEndpointsAreNotMigratedAsSourceBlob() {
    assertTrue(RepositoryDataMigrationWorker.shouldMigrateSourceAsset(
        RepositoryFormat.PUB,
        "api/archives/demo_pkg-1.0.0.tar.gz"));
    assertTrue(RepositoryDataMigrationWorker.shouldMigrateSourceAsset(
        RepositoryFormat.PUB,
        "packages/demo_pkg/versions/1.0.0.tar.gz"));
    assertTrue(RepositoryDataMigrationWorker.shouldMigrateSourceAsset(
        RepositoryFormat.PUB,
        "api/packages/demo_pkg"));

    assertFalse(RepositoryDataMigrationWorker.shouldMigrateSourceAsset(
        RepositoryFormat.PUB,
        "api/packages/demo_pkg/versions/1.0.0"));
    assertFalse(RepositoryDataMigrationWorker.shouldMigrateSourceAsset(
        RepositoryFormat.PUB,
        "api/packages/versions/new"));
  }

  @Test
  void rubygemsDependencyIndexUsesDownloadedBytesInsteadOfSourceMetadataSize() {
    assertFalse(RepositoryDataMigrationWorker.shouldValidateDownloadedSize(
        claim(10L, 100L, RepositoryFormat.RUBYGEMS, "dependencies/demo.ruby")));
    assertFalse(RepositoryDataMigrationWorker.shouldValidateDownloadedSize(
        claim(10L, 100L, RepositoryFormat.RUBYGEMS, "/dependencies/demo.ruby")));

    assertTrue(RepositoryDataMigrationWorker.shouldValidateDownloadedSize(
        claim(10L, 100L, RepositoryFormat.RUBYGEMS, "gems/demo-1.0.0.gem")));
    assertTrue(RepositoryDataMigrationWorker.shouldValidateDownloadedSize(
        claim(10L, 100L, RepositoryFormat.MAVEN2, "dependencies/demo.ruby")));
  }

  @Test
  void composerRootMetadataUsesNexusGeneratedRepresentationSize() {
    assertFalse(RepositoryDataMigrationWorker.shouldValidateDownloadedSize(
        claim(10L, 100L, RepositoryFormat.COMPOSER, "packages.json")));
    assertFalse(RepositoryDataMigrationWorker.shouldValidateDownloadedSize(
        claim(10L, 100L, RepositoryFormat.COMPOSER, "/packages.json")));

    assertTrue(RepositoryDataMigrationWorker.shouldValidateDownloadedSize(
        claim(10L, 100L, RepositoryFormat.COMPOSER, "p2/psr/log.json")));
    assertTrue(RepositoryDataMigrationWorker.shouldValidateDownloadedSize(
        claim(10L, 100L, RepositoryFormat.COMPOSER, "psr/log/3.0.2/psr-log-3.0.2.zip")));
    assertTrue(RepositoryDataMigrationWorker.shouldValidateDownloadedSize(
        claim(10L, 100L, RepositoryFormat.MAVEN2, "packages.json")));
  }

  @Test
  void discoveryPageFiltersOldAndDynamicAssetsAndPersistsCursor() {
    Fixture fixture = fixture();
    try {
      RepositoryDataMigrationRepositoryRecord repository = repositoryJob(
          RepositoryFormat.CARGO, Map.of("metadataSince", "2026-01-02T00:00:00Z"));
      RepositoryAssetPage page = new RepositoryAssetPage(
          "cargo",
          null,
          "de/mo/demo",
          true,
          List.of(
              metadata("config.json", "2026-01-03T00:00:00Z"),
              metadata("old/crate", "2026-01-01T00:00:00Z"),
              metadata("de/mo/demo", "2026-01-03T00:00:00Z")),
          List.of("source warning"));
      when(fixture.migrationDao.findTargetAssetsByPathHash(eq(9L), any())).thenReturn(Map.of());

      assertTrue((Boolean) invoke(
          fixture.worker, "processDiscoveryPage",
          new Class<?>[] {
              RepositoryDataMigrationRepositoryRecord.class,
              RepositoryAssetPage.class,
              Instant.class,
              RepositoryDataMigrationWorker.SourceAccess.class
          },
          repository,
          page,
          Instant.parse("2026-01-02T00:00:00Z"),
          sourceAccess(null, false, false)));

      @SuppressWarnings("unchecked")
      ArgumentCaptor<List<RepositoryDataMigrationAssetRecord>> records =
          ArgumentCaptor.forClass(List.class);
      verify(fixture.migrationDao).upsertDiscoveredAssets(eq(7L), records.capture(), eq(Map.of()));
      assertEquals(List.of("de/mo/demo"),
          records.getValue().stream().map(RepositoryDataMigrationAssetRecord::sourcePath).toList());
      verify(fixture.migrationDao).finishDiscoveryPage(7L, "de/mo/demo", true);
    } finally {
      fixture.worker.shutdown();
    }
  }

  @Test
  void discoveryCapturesPublicIdForAnExistingTargetBeforeAdvancingCursor() throws Exception {
    Fixture fixture = fixture();
    try {
      RepositoryDataMigrationRepositoryRecord repository = repositoryJob(
          RepositoryFormat.RAW, Map.of());
      RepositoryAssetPage page = new RepositoryAssetPage(
          "raw", null, "tools/setup.exe", true,
          List.of(metadata("tools/setup.exe", "2026-01-03T00:00:00Z")), List.of());
      when(fixture.migrationDao.findTargetAssetsByPathHash(eq(9L), any()))
          .thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<byte[]> hashes = invocation.getArgument(1);
            return Map.of(
                java.nio.ByteBuffer.wrap(hashes.getFirst()),
                new TargetAssetRef(null, 30L, 40L));
          });
      NexusRestClient client = mock(NexusRestClient.class);

      assertTrue((Boolean) invoke(
          fixture.worker, "processDiscoveryPage",
          new Class<?>[] {
              RepositoryDataMigrationRepositoryRecord.class,
              RepositoryAssetPage.class,
              Instant.class,
              RepositoryDataMigrationWorker.SourceAccess.class
          },
          repository, page, null, sourceAccess(client, true, true)));

      verify(fixture.publicIdCaptureService).capture(
          eq(client), eq("http://nexus.example"), eq(100L), eq("source"), eq(9L), any(), eq(30L));
      verify(fixture.migrationDao).finishDiscoveryPage(7L, "tools/setup.exe", true);
    } finally {
      fixture.worker.shutdown();
    }
  }

  @Test
  void packageConcurrencyUsesConfiguredBoundsAndFallback() {
    Fixture fixture = fixture();
    try {
      when(fixture.migrationJobDao.findById(100L)).thenReturn(Optional.of(
          new MigrationJobRecord(
              100L, null, "http://nexus", "RUNNING", Map.of("concurrency", 200),
              Map.of(), null, null)));
      assertEquals(64, invoke(
          fixture.worker, "packageConcurrency", new Class<?>[] {Long.class}, 100L));

      when(fixture.migrationJobDao.findById(101L)).thenReturn(Optional.of(
          new MigrationJobRecord(
              101L, null, "http://nexus", "RUNNING", Map.of("concurrency", "invalid"),
              Map.of(), null, null)));
      assertEquals(8, invoke(
          fixture.worker, "packageConcurrency", new Class<?>[] {Long.class}, 101L));
      assertEquals(8, invoke(
          fixture.worker, "packageConcurrency", new Class<?>[] {Long.class}, (Object) null));
    } finally {
      fixture.worker.shutdown();
    }
  }

  @Test
  void migrateOneMarksSuccessfulDownloadAndTargetIds() throws Exception {
    Fixture fixture = fixture();
    try {
      AssetClaim claim = claim(10L, 100L, RepositoryFormat.MAVEN2, "com/acme/app.jar");
      NexusRestClient client = mock(NexusRestClient.class);
      @SuppressWarnings("unchecked")
      HttpResponse<InputStream> response = mock(HttpResponse.class);
      when(response.statusCode()).thenReturn(200);
      when(response.headers()).thenReturn(HttpHeaders.of(
          Map.of(
              "Content-Type", List.of("application/java-archive"),
              "Content-Length", List.of("3"),
              "ETag", List.of("\"{SHA1{66f041e16a60928b05a7e228a89c3799e5a769a9}}\"")),
          (a, b) -> true));
      when(response.body()).thenReturn(
          new ByteArrayInputStream("jar".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      when(client.getRepositoryAsset("source", "com/acme/app.jar")).thenReturn(response);
      when(fixture.writer.write(
          eq(1L),
          eq(claim.asset()),
          any(),
          eq("application/java-archive"),
          eq(true),
          eq(new RepositoryDataMigrationWriter.DownloadEvidence(
              3L,
              "\"{SHA1{66f041e16a60928b05a7e228a89c3799e5a769a9}}\""))))
          .thenReturn(new RepositoryDataMigrationWriter.WriteResult(20L, 30L, 40L, "object"));

      invoke(
          fixture.worker, "migrateOne",
          new Class<?>[] {AssetClaim.class, RepositoryDataMigrationWorker.SourceAccess.class},
          claim, sourceAccess(client, true, false));

      verify(fixture.publicIdCaptureService).capture(
          client, "http://nexus.example", 100L, "source", 1L, claim.asset(), 30L);
      verify(fixture.migrationDao).markAssetMigrated(
          claim.asset().id(), claim.asset().repositoryJobId(), 20L, 30L, 40L);
      verify(fixture.migrationDao, never()).markAssetFailed(anyLong(), anyLong(), anyInt(), any());
    } finally {
      fixture.worker.shutdown();
    }
  }

  @Test
  void migrateOneMarksHttpFailureAndSkipsDerivedCargoConfig() throws Exception {
    Fixture fixture = fixture();
    try {
      AssetClaim failed = claim(10L, 100L, RepositoryFormat.MAVEN2, "missing.jar");
      NexusRestClient client = mock(NexusRestClient.class);
      @SuppressWarnings("unchecked")
      HttpResponse<InputStream> response = mock(HttpResponse.class);
      InputStream body = mock(InputStream.class);
      when(response.statusCode()).thenReturn(404);
      when(response.body()).thenReturn(body);
      when(client.getRepositoryAsset("source", "missing.jar")).thenReturn(response);

      invoke(
          fixture.worker, "migrateOne",
          new Class<?>[] {AssetClaim.class, RepositoryDataMigrationWorker.SourceAccess.class},
          failed, sourceAccess(client, false, false));

      verify(body).close();
      verify(fixture.migrationDao).markAssetFailed(
          eq(failed.asset().id()), eq(failed.asset().repositoryJobId()), eq(5),
          org.mockito.ArgumentMatchers.contains("HTTP 404"));

      AssetClaim skipped = claim(11L, 100L, RepositoryFormat.CARGO, "/config.json");
      invoke(
          fixture.worker, "migrateOne",
          new Class<?>[] {AssetClaim.class, RepositoryDataMigrationWorker.SourceAccess.class},
          skipped, sourceAccess(client, false, false));
      verify(fixture.migrationDao).markAssetMigrated(
          skipped.asset().id(), skipped.asset().repositoryJobId(), null, null, null);
      verify(client, never()).getRepositoryAsset("source", "/config.json");
    } finally {
      fixture.worker.shutdown();
    }
  }

  @Test
  void publicIdBackfillOnlyFailsMissingTargetBeforeSourceDownload() throws Exception {
    Fixture fixture = fixture();
    try {
      AssetClaim claim = claim(10L, 100L, RepositoryFormat.RAW, "tools/missing.exe");
      NexusRestClient client = mock(NexusRestClient.class);

      invoke(
          fixture.worker, "migrateOne",
          new Class<?>[] {AssetClaim.class, RepositoryDataMigrationWorker.SourceAccess.class},
          claim, sourceAccess(client, true, true));

      verify(client, never()).getRepositoryAsset(any(), any());
      verify(fixture.writer, never()).write(anyLong(), any(), any(), any(), anyBoolean(), any());
      verify(fixture.migrationDao).markAssetFailed(
          eq(claim.asset().id()), eq(claim.asset().repositoryJobId()), eq(5),
          org.mockito.ArgumentMatchers.contains("publicIdBackfillOnly"));
    } finally {
      fixture.worker.shutdown();
    }
  }

  @Test
  void metadataEngineRecognizesProfilesAndMigrationAdapters() {
    assertEquals("ORIENTDB", invokeStatic(
        "metadataEngine", new Class<?>[] {Map.class},
        Map.of("migrationPlan", Map.of("adapter", "NexusOrientDbAdapter"))));
    assertEquals("DATASTORE_H2", invokeStatic(
        "metadataEngine", new Class<?>[] {Map.class},
        Map.of("migrationPlan", Map.of("adapter", "NexusDatastoreH2Adapter"))));
    assertEquals("DATASTORE_POSTGRESQL", invokeStatic(
        "metadataEngine", new Class<?>[] {Map.class},
        Map.of("migrationPlan", Map.of("adapter", "NexusDatastorePostgresqlAdapter"))));
    assertEquals("CUSTOM", invokeStatic(
        "metadataEngine", new Class<?>[] {Map.class},
        Map.of("sourceProfile", Map.of("metadataEngine", "CUSTOM"))));
    assertEquals("UNKNOWN", invokeStatic(
        "metadataEngine", new Class<?>[] {Map.class}, Map.of()));
  }

  private static Fixture fixture() {
    MigrationJobDao migrationJobDao = mock(MigrationJobDao.class);
    RepositoryDataMigrationDao migrationDao = mock(RepositoryDataMigrationDao.class);
    RepositoryDataMigrationService migrationService = mock(RepositoryDataMigrationService.class);
    RepositoryDataMigrationWriter writer = mock(RepositoryDataMigrationWriter.class);
    NexusPublicAssetIdCaptureService publicIdCaptureService =
        mock(NexusPublicAssetIdCaptureService.class);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(mock(TransactionStatus.class));
    return new Fixture(
        migrationJobDao,
        migrationDao,
        writer,
        publicIdCaptureService,
        new RepositoryDataMigrationWorker(
            new ObjectMapper(), migrationJobDao, migrationDao, migrationService, writer,
            publicIdCaptureService, transactions));
  }

  private static RepositoryDataMigrationRepositoryRecord repositoryJob(
      RepositoryFormat format, Map<String, Object> options) {
    return new RepositoryDataMigrationRepositoryRecord(
        7L, 100L, "source", "target", 9L, format,
        RepositoryDataMigrationDao.REPOSITORY_DISCOVERING, null, 100,
        0, 0, 0, 0, null, null, options, null, null);
  }

  private static RepositoryAssetMetadata metadata(String path, String updatedAt) {
    return new RepositoryAssetMetadata(
        "source", "asset-" + path, null, path, "cargo", null,
        "demo", "1.0.0", "asset", "application/octet-stream", 1L,
        null, updatedAt, null, null, updatedAt, "admin", null, Map.of(), Map.of());
  }

  private static Object invoke(
      Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
    try {
      Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
      method.setAccessible(true);
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Object invokeStatic(
      String methodName, Class<?>[] parameterTypes, Object... args) {
    try {
      Method method = RepositoryDataMigrationWorker.class.getDeclaredMethod(methodName, parameterTypes);
      method.setAccessible(true);
      return method.invoke(null, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Fixture(
      MigrationJobDao migrationJobDao,
      RepositoryDataMigrationDao migrationDao,
      RepositoryDataMigrationWriter writer,
      NexusPublicAssetIdCaptureService publicIdCaptureService,
      RepositoryDataMigrationWorker worker) {
  }

  private static RepositoryDataMigrationWorker.SourceAccess sourceAccess(
      NexusRestClient client, boolean capturePublicIds, boolean backfillOnly) {
    return new RepositoryDataMigrationWorker.SourceAccess(
        client,
        "UNKNOWN",
        true,
        capturePublicIds,
        backfillOnly,
        "http://nexus.example");
  }

  private static AssetClaim claim(long repositoryJobId, long migrationJobId, String path) {
    return claim(repositoryJobId, migrationJobId, RepositoryFormat.MAVEN2, path);
  }

  private static AssetClaim claim(
      long repositoryJobId,
      long migrationJobId,
      RepositoryFormat format,
      String path) {
    RepositoryDataMigrationAssetRecord asset = new RepositoryDataMigrationAssetRecord(
        repositoryJobId + 1000,
        repositoryJobId,
        null,
        null,
        path,
        null,
        format,
        "com.acme",
        "app",
        "1.0",
        "artifact",
        "application/octet-stream",
        1L,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        RepositoryDataMigrationDao.ASSET_PENDING,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of(),
        null);
    return new AssetClaim(
        asset,
        migrationJobId,
        "source",
        "target",
        1L,
        format,
        "http://nexus.example",
        Map.of());
  }
}
