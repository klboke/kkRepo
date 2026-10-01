package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class RepositoryDataMigrationWorkerTest {
  @Test
  void discoveryWakesPackageSyncAlreadyEnabledOnAnotherReplica() throws Exception {
    Fixture fixture = fixture();
    try {
      when(fixture.migrationJobDao.findById(100L)).thenReturn(Optional.of(new MigrationJobRecord(
          100L, "3.68.1-02", "", "running", Map.of("packageMigrationEnabled", true), Map.of(), Instant.now(), null)));
      when(fixture.migrationDao.findTargetAssetsByPathHash(eq(9L), any())).thenReturn(Map.of());
      when(fixture.migrationDao.claimAssetsForMigration(eq(100L), anyInt(), anyInt(), any())).thenReturn(List.of());
      fixture.worker.triggerPackages(100L);
      verify(fixture.migrationDao, org.mockito.Mockito.timeout(2000)).claimAssetsForMigration(
          eq(100L), anyInt(), anyInt(), any());
      RepositoryAssetPage page = new RepositoryAssetPage("source", null, "a.jar", true,
          List.of(metadata("a.jar", "2026-01-03T00:00:00Z")), List.of());
      invoke(fixture.worker, "processDiscoveryPage", new Class<?>[] {
          RepositoryDataMigrationRepositoryRecord.class, RepositoryAssetPage.class, Instant.class},
          repositoryJob(RepositoryFormat.MAVEN2, Map.of()), page, null);
      verify(fixture.migrationDao, org.mockito.Mockito.timeout(2000).atLeast(2)).claimAssetsForMigration(
          eq(100L), anyInt(), anyInt(), any());
    } finally { fixture.worker.shutdown(); }
  }

  @Test
  void wakeupWhileTriggerIsRunningIsReplayedInsteadOfDropped() throws Exception {
    Fixture fixture = fixture();
    var started = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var replayed = new java.util.concurrent.CountDownLatch(1);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    Runnable work = () -> {
      if (calls.incrementAndGet() == 1) {
        started.countDown();
        try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      } else { replayed.countDown(); }
    };
    try {
      invoke(fixture.worker, "trigger", new Class<?>[] {String.class, Runnable.class}, "packages:100", work);
      assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
      invoke(fixture.worker, "trigger", new Class<?>[] {String.class, Runnable.class}, "packages:100", work);
      release.countDown();
      assertTrue(replayed.await(2, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(2, calls.get());
    } finally { release.countDown(); fixture.worker.shutdown(); }
  }

  @Test
  void freshReplicaRecoversDurablePendingJobsWithoutAPerPageTrigger() {
    Fixture replica = fixture();
    when(replica.migrationDao.findPackageMigrationJobsToWake(any(), anyInt(), eq(16)))
        .thenReturn(List.of(100L));
    when(replica.migrationDao.claimAssetsForMigration(eq(100L), anyInt(), anyInt(), any())).thenReturn(List.of());
    try {
      replica.worker.recoverPackageMigrations();
      verify(replica.migrationDao, org.mockito.Mockito.timeout(2000)).claimAssetsForMigration(
          eq(100L), anyInt(), anyInt(), any());
    } finally { replica.worker.shutdown(); }
  }

  @Test
  void failedTriggerCanBeStartedAgain() throws Exception {
    Fixture fixture = fixture();
    var recovered = new java.util.concurrent.CountDownLatch(1);
    when(fixture.migrationDao.claimAssetsForMigration(eq(100L), anyInt(), anyInt(), any()))
        .thenThrow(new IllegalStateException("temporary database failure"))
        .thenAnswer(invocation -> { recovered.countDown(); return List.of(); });
    try {
      fixture.worker.triggerPackages(100L);
      verify(fixture.migrationDao, org.mockito.Mockito.timeout(2000)).claimAssetsForMigration(
          eq(100L), anyInt(), anyInt(), any());
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      do {
        fixture.worker.triggerPackages(100L);
      } while (!recovered.await(20, java.util.concurrent.TimeUnit.MILLISECONDS) && System.nanoTime() < deadline);
      assertEquals(0, recovered.getCount(), "A failed task must not permanently suppress later triggers");
    } finally { fixture.worker.shutdown(); }
  }

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
              Instant.class
          },
          repository, page, Instant.parse("2026-01-02T00:00:00Z")));

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
  void batchRenewsQueuedClaimsAndCompletesWithSharedProgress() throws Exception {
    Fixture fixture = fixture();
    var claim = claim(10L, 100L, RepositoryFormat.CARGO, "config.json");
    when(fixture.migrationDao.claimAssetsForMigration(eq(100L), anyInt(), anyInt(), any()))
        .thenReturn(List.of(claim));
    try {
      assertEquals(true, invoke(fixture.worker, "migrateAssetBatch", new Class<?>[] {Long.class}, 100L));
      var order = org.mockito.Mockito.inOrder(fixture.migrationDao);
      order.verify(fixture.migrationDao).renewAssetClaim(eq(claim.asset().id()), eq(1), any());
      order.verify(fixture.migrationDao).markAssetMigrated(claim.asset().id(), 10L, 1, null, null, null);
      order.verify(fixture.migrationDao).refreshRepositoryProgress(10L);
    } finally { fixture.worker.shutdown(); }
  }

  @Test
  void databaseFailureDuringRenewalClosesTheUnownedBodyAndDoesNotPublish() throws Exception {
    Fixture fixture = fixture();
    var claim = claim(10L, 100L, RepositoryFormat.MAVEN2, "large.jar");
    when(fixture.migrationDao.renewAssetClaim(anyLong(), anyInt(), any()))
        .thenThrow(new IllegalStateException("database unavailable"));
    try (var lease = fixture.worker.new ClaimLease(claim)) {
      InputStream body = mock(InputStream.class);
      org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> lease.guard(body));
      verify(body).close();
      NexusRestClient client = mock(NexusRestClient.class);
      invoke(fixture.worker, "migrateOne",
          new Class<?>[] {AssetClaim.class, NexusRestClient.class, boolean.class}, claim, client, true);
      verify(client, never()).getRepositoryAsset(anyString(), anyString());
      verify(fixture.migrationDao, never()).markAssetFailed(anyLong(), anyLong(), anyInt(), anyInt(), any());
    } finally { fixture.worker.shutdown(); }
  }

  @Test
  void lostHeartbeatStopsTheDownloadStreamAndCannotRenewAgain() throws Exception {
    Fixture fixture = fixture();
    AssetClaim claim = claim(10L, 100L, RepositoryFormat.MAVEN2, "large.jar");
    when(fixture.migrationDao.renewAssetClaim(anyLong(), anyInt(), any())).thenReturn(true, true, false);
    try (var lease = fixture.worker.new ClaimLease(claim)) {
      InputStream stream = lease.guard(new ByteArrayInputStream(new byte[] {1, 2, 3}));
      assertEquals(1, stream.read());
      lease.renew();
      assertEquals(1, stream.read(new byte[1], 0, 1));
      lease.renew();
      org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, stream::read);
      lease.renew();
      verify(fixture.migrationDao, org.mockito.Mockito.times(3)).renewAssetClaim(anyLong(), eq(1), any());
    } finally { fixture.worker.shutdown(); }
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
          Map.of("Content-Type", List.of("application/java-archive")), (a, b) -> true));
      when(response.body()).thenReturn(
          new ByteArrayInputStream("jar".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      when(client.getRepositoryAsset("source", "com/acme/app.jar")).thenReturn(response);
      when(fixture.writer.write(eq(1L), eq(claim.asset()), any(), eq("application/java-archive"), eq(true)))
          .thenReturn(new RepositoryDataMigrationWriter.WriteResult(20L, 30L, 40L, "object"));

      invoke(
          fixture.worker, "migrateOne",
          new Class<?>[] {AssetClaim.class, NexusRestClient.class, boolean.class},
          claim, client, true);

      verify(fixture.migrationDao).markAssetMigrated(
          claim.asset().id(), claim.asset().repositoryJobId(), 1, 20L, 30L, 40L);
      verify(fixture.migrationDao, never()).markAssetFailed(anyLong(), anyLong(), anyInt(), anyInt(), any());
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
          new Class<?>[] {AssetClaim.class, NexusRestClient.class, boolean.class},
          failed, client, true);

      verify(body).close();
      verify(fixture.migrationDao).markAssetFailed(
          eq(failed.asset().id()), eq(failed.asset().repositoryJobId()), eq(1), eq(5),
          org.mockito.ArgumentMatchers.contains("HTTP 404"));

      AssetClaim skipped = claim(11L, 100L, RepositoryFormat.CARGO, "/config.json");
      invoke(
          fixture.worker, "migrateOne",
          new Class<?>[] {AssetClaim.class, NexusRestClient.class, boolean.class},
          skipped, client, true);
      verify(fixture.migrationDao).markAssetMigrated(
          skipped.asset().id(), skipped.asset().repositoryJobId(), 1, null, null, null);
      verify(client, never()).getRepositoryAsset("source", "/config.json");
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
    when(migrationDao.renewAssetClaim(anyLong(), anyInt(), any())).thenReturn(true);
    RepositoryDataMigrationService migrationService = mock(RepositoryDataMigrationService.class);
    RepositoryDataMigrationWriter writer = mock(RepositoryDataMigrationWriter.class);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(mock(TransactionStatus.class));
    return new Fixture(
        migrationJobDao,
        migrationDao,
        writer,
        new RepositoryDataMigrationWorker(
            new ObjectMapper(), migrationJobDao, migrationDao, migrationService, writer, transactions));
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
      RepositoryDataMigrationWorker worker) {
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
        Map.of("sourceUsername", "admin", "sourcePassword", "test-password"));
  }
}
