package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RepositoryDataMigrationDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test
  void failedAssetCanBeRetriedAndClaimedAgainWithRealJsonAndLockingSql() {
    long repositoryId = insertRepository("migration-target", "maven2");
    long migrationJobId = insertMigrationJob(true);
    RepositoryDataMigrationDao dao = new JdbcRepositoryDataMigrationDao(
        jdbc(), jsonColumns(), new com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect());
    long repositoryJobId = dao.createRepositoryJob(
        migrationJobId,
        "maven-releases",
        "migration-target",
        repositoryId,
        RepositoryFormat.MAVEN2,
        100,
        Map.of("sourceType", "hosted"));
    dao.upsertDiscoveredAssets(repositoryJobId, List.of(asset()), Map.of());
    dao.finishDiscoveryPage(repositoryJobId, null, true);

    assertEquals(List.of(migrationJobId), dao.findPackageMigrationJobsToWake(Instant.EPOCH, 3, 16));

    List<RepositoryDataMigrationDao.AssetClaim> firstClaims = inTransaction(
        () -> dao.claimAssetsForMigration(migrationJobId, 10, 3, Instant.now().minusSeconds(60)));
    assertEquals(1, firstClaims.size());
    assertTrue(dao.findPackageMigrationJobsToWake(Instant.EPOCH, 3, 16).isEmpty());
    assertEquals(List.of(migrationJobId), dao.findPackageMigrationJobsToWake(Instant.now().plusSeconds(1), 3, 16));
    assertEquals(List.of(migrationJobId), dao.findPackageMigrationJobsToWake(Instant.now().plusSeconds(1), 1, 16));
    dao.setPackageMigrationEnabled(migrationJobId, false);
    assertTrue(dao.findPackageMigrationJobsToWake(Instant.now().plusSeconds(1), 3, 16).isEmpty());
    dao.setPackageMigrationEnabled(migrationJobId, true);
    assertEquals(0, firstClaims.get(0).asset().attempts());
    assertEquals(1, jdbc().queryForObject("""
        SELECT attempts
        FROM repository_data_migration_asset
        WHERE id = ?
        """, Integer.class, firstClaims.get(0).asset().id()));
    assertEquals("migrating", dao.findRepositoryJob(repositoryJobId).orElseThrow().status());

    long assetId = firstClaims.get(0).asset().id();
    dao.markAssetFailed(assetId, repositoryJobId, 1, "upstream failed");
    dao.refreshRepositoryProgress(repositoryJobId);

    assertEquals(
        JdbcRepositoryDataMigrationDao.REPOSITORY_FINISHED_WITH_FAILURES,
        dao.findRepositoryJob(repositoryJobId).orElseThrow().status());
    assertEquals(1, dao.retryFailedAssets(migrationJobId));
    assertEquals("migrating", dao.findRepositoryJob(repositoryJobId).orElseThrow().status());

    List<RepositoryDataMigrationDao.AssetClaim> retried = inTransaction(
        () -> dao.claimAssetsForMigration(migrationJobId, 10, 3, Instant.now().plusSeconds(1)));
    assertEquals(1, retried.size());
    assertEquals(0, retried.get(0).asset().attempts());

    dao.markAssetMigrated(assetId, repositoryJobId, null, null, null);
    dao.refreshRepositoryProgress(repositoryJobId);
    RepositoryDataMigrationDao.MigrationJobProgress progress = dao.jobProgress(migrationJobId);
    assertEquals(1, progress.migratedAssets());
    assertEquals(0, progress.failedAssets());
    assertEquals(0, progress.pendingAssets());
    assertFalse(progress.active());
  }

  @Test
  void conanRecoveryOnAnotherReplicaPreservesSerialPublication() {
    long repositoryId = insertRepository("migration-serial", "conan");
    long jobId = insertMigrationJob(true);
    RepositoryDataMigrationDao first = new JdbcRepositoryDataMigrationDao(jdbc(), jsonColumns(), new com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect());
    RepositoryDataMigrationDao second = new JdbcRepositoryDataMigrationDao(jdbc(), jsonColumns(), new com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect());
    long repositoryJobId = first.createRepositoryJob(jobId, "source", "migration-serial", repositoryId,
        RepositoryFormat.CONAN, 100, Map.of());
    first.upsertDiscoveredAssets(repositoryJobId,
        List.of(asset("demo/export.tgz", RepositoryFormat.CONAN),
            asset("demo/conanmanifest.txt", RepositoryFormat.CONAN)), Map.of());
    first.finishDiscoveryPage(repositoryJobId, null, true);
    Instant cutoff = Instant.now().minusSeconds(60);
    assertTrue(inTransaction(() -> first.claimAssetsForMigration(-1L, 1, 3, cutoff)).isEmpty());
    var claimed = inTransaction(() -> first.claimAssetsForMigration(jobId, 1, 3, cutoff));
    assertEquals(1, claimed.size());
    assertTrue(inTransaction(() -> second.claimAssetsForMigration(jobId, 1, 3, cutoff)).isEmpty());
    first.markAssetMigrated(claimed.getFirst().asset().id(), repositoryJobId, null, null, null);
    var last = inTransaction(() -> second.claimAssetsForMigration(jobId, 1, 3, cutoff));
    assertEquals(1, last.size());
    second.markAssetMigrated(last.getFirst().asset().id(), repositoryJobId, null, null, null);
    second.refreshRepositoryProgress(repositoryJobId);
  }

  @Test
  void replicasShareCapacityRenewClaimsAndFinalizeExhaustedAttempts() {
    long repositoryId = insertRepository("migration-capacity", "maven2");
    long jobId = insertMigrationJob(true);
    jdbc().update("UPDATE migration_job SET options_json = ? WHERE id = ?",
        jsonColumns().write(Map.of("packageMigrationEnabled", true, "concurrency", 2)), jobId);
    RepositoryDataMigrationDao first = new JdbcRepositoryDataMigrationDao(jdbc(), jsonColumns(), new com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect());
    RepositoryDataMigrationDao second = new JdbcRepositoryDataMigrationDao(jdbc(), jsonColumns(), new com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect());
    long repo = first.createRepositoryJob(jobId, "source", "migration-capacity", repositoryId,
        RepositoryFormat.MAVEN2, 100, Map.of());
    first.upsertDiscoveredAssets(repo, List.of(asset("a.jar"), asset("b.jar"), asset("c.jar")), Map.of());
    first.finishDiscoveryPage(repo, null, true);
    Instant cutoff = Instant.now().minusSeconds(300);
    var one = inTransaction(() -> first.claimAssetsForMigration(1, 5, cutoff)).getFirst();
    var two = inTransaction(() -> second.claimAssetsForMigration(jobId, 2, 5, cutoff));
    assertEquals(1, two.size(), "replicas share the configured two slots");
    assertTrue(inTransaction(() -> first.claimAssetsForMigration(jobId, 2, 5, cutoff)).isEmpty());
    long assetId = one.asset().id();
    jdbc().update("UPDATE repository_data_migration_asset SET claimed_at = ? WHERE id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(290)), assetId);
    assertTrue(first.renewAssetClaim(assetId, 1, cutoff));
    assertTrue(inTransaction(() -> second.claimAssetsForMigration(jobId, 2, 5, cutoff)).isEmpty());
    jdbc().update("UPDATE repository_data_migration_asset SET claimed_at = ? WHERE id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(310)), assetId);
    assertFalse(first.renewAssetClaim(assetId, 1, cutoff), "expired owners cannot resurrect a lease");
    var recovered = inTransaction(() -> second.claimAssetsForMigration(jobId, 2, 5, cutoff));
    assertEquals(assetId, recovered.getFirst().asset().id());
    assertEquals(1, recovered.size());
    assertFalse(first.renewAssetClaim(assetId, 1, cutoff));
    first.markAssetMigrated(assetId, repo, 1, null, null, null);
    first.markAssetFailed(assetId, repo, 1, 5, "old owner");
    assertEquals("migrating", jdbc().queryForObject(
        "SELECT status FROM repository_data_migration_asset WHERE id = ?", String.class, assetId));
    second.markAssetMigrated(assetId, repo, 2, null, null, null);
    second.markAssetMigrated(two.getFirst().asset().id(), repo, 1, null, null, null);
    var last = inTransaction(() -> first.claimAssetsForMigration(jobId, 2, 5, cutoff)).getFirst();
    jdbc().update("UPDATE repository_data_migration_asset SET attempts = 5, claimed_at = ? WHERE id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(310)), last.asset().id());
    assertEquals(List.of(jobId), second.findPackageMigrationJobsToWake(cutoff, 5, 16));
    assertTrue(inTransaction(() -> second.claimAssetsForMigration(jobId, 2, 5, cutoff)).isEmpty());
    assertEquals("finished_with_failures", second.findRepositoryJob(repo).orElseThrow().status());
    assertFalse(second.jobProgress(jobId).active());
    assertEquals(1, second.jobProgress(jobId).failedAssets());
    assertEquals(1, second.retryFailedAssets(jobId));
    var retried = inTransaction(() -> first.claimAssetsForMigration(jobId, 2, 5, cutoff));
    assertEquals(1, retried.size());
    first.markAssetMigrated(retried.getFirst().asset().id(), repo, 1, null, null, null);
    first.refreshRepositoryProgress(repo);
  }

  @Test
  void saturatedOlderJobsDoNotStarveTheRecoveryWindow() {
    long repositoryId = insertRepository("migration-recovery-fairness", "maven2");
    RepositoryDataMigrationDao dao = new JdbcRepositoryDataMigrationDao(jdbc(), jsonColumns(), new com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect());
    var jobs = new java.util.ArrayList<Long>();
    Instant cutoff = Instant.now().minusSeconds(300);
    for (int index = 0; index < 17; index++) {
      long job = insertMigrationJob(true);
      jobs.add(job);
      jdbc().update("UPDATE migration_job SET options_json = ? WHERE id = ?",
          jsonColumns().write(Map.of("packageMigrationEnabled", true, "concurrency", 1)), job);
      long repo = dao.createRepositoryJob(job, "source", "migration-recovery-fairness", repositoryId,
          RepositoryFormat.MAVEN2, 100, Map.of());
      dao.upsertDiscoveredAssets(repo, List.of(asset("a.jar"), asset("b.jar")), Map.of());
      dao.finishDiscoveryPage(repo, null, true);
      if (index < 16) assertEquals(1, inTransaction(() -> dao.claimAssetsForMigration(job, 1, 5, cutoff)).size());
    }
    assertEquals(List.of(jobs.getLast()), dao.findPackageMigrationJobsToWake(cutoff, 5, 16));
    jobs.forEach(job -> dao.setPackageMigrationEnabled(job, false));
  }

  @Test
  void discoveryClaimHonorsJobFilterAndRetryCutoff() {
    long firstRepositoryId = insertRepository("target-one", "maven2");
    long secondRepositoryId = insertRepository("target-two", "maven2");
    long firstJobId = insertMigrationJob(true);
    long secondJobId = insertMigrationJob(true);
    RepositoryDataMigrationDao dao = new JdbcRepositoryDataMigrationDao(
        jdbc(), jsonColumns(), new com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect());
    long firstRepositoryJobId = dao.createRepositoryJob(
        firstJobId, "source-one", "target-one", firstRepositoryId,
        RepositoryFormat.MAVEN2, 50, Map.of());
    long secondRepositoryJobId = dao.createRepositoryJob(
        secondJobId, "source-two", "target-two", secondRepositoryId,
        RepositoryFormat.MAVEN2, 50, Map.of());

    assertEquals(secondRepositoryJobId, inTransaction(() -> dao.claimRepositoryForDiscovery(
        secondJobId, Instant.now())).orElseThrow().id());
    assertTrue(inTransaction(() -> dao.claimRepositoryForDiscovery(
        secondJobId, Instant.EPOCH)).isEmpty());
    assertEquals(firstRepositoryJobId, inTransaction(() -> dao.claimRepositoryForDiscovery(
        firstJobId, Instant.now())).orElseThrow().id());
  }

  private long insertMigrationJob(boolean packageMigrationEnabled) {
    jdbc().update("""
        INSERT INTO migration_job
          (source_nexus_version, source_data_path, status, options_json, summary_json)
        VALUES ('3.70.0', '/nexus-data', 'running', ?, JSON_OBJECT())
        """, jsonColumns().write(Map.of(
            "scope", "repository-data",
            "packageMigrationEnabled", packageMigrationEnabled)));
    return jdbc().queryForObject("SELECT MAX(id) FROM migration_job", Long.class);
  }

  private static RepositoryDataMigrationAssetRecord asset() {
    return asset("com/acme/app/1.0/app-1.0.jar");
  }

  private static RepositoryDataMigrationAssetRecord asset(String path) {
    return asset(path, RepositoryFormat.MAVEN2);
  }

  private static RepositoryDataMigrationAssetRecord asset(String path, RepositoryFormat format) {
    Instant updated = Instant.parse("2026-01-01T00:00:00Z");
    return new RepositoryDataMigrationAssetRecord(
        null,
        0,
        "#12:0",
        "#11:0",
        path,
        HashColumns.pathHash(path),
        format,
        "com.acme",
        "app",
        "1.0",
        "ARTIFACT",
        "application/java-archive",
        1024L,
        "default@abc",
        updated,
        null,
        updated,
        updated,
        "admin",
        "127.0.0.1",
        JdbcRepositoryDataMigrationDao.ASSET_PENDING,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of("sourceRepositoryType", "hosted"),
        null);
  }
}
