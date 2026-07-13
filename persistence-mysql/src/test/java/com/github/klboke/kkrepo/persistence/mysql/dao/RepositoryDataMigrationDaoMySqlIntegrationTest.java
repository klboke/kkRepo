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
    RepositoryDataMigrationDao dao = new JdbcRepositoryDataMigrationDao(jdbc(), jsonColumns());
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

    List<RepositoryDataMigrationDao.AssetClaim> firstClaims = inTransaction(
        () -> dao.claimAssetsForMigration(migrationJobId, 10, 3, Instant.now().minusSeconds(60)));
    assertEquals(1, firstClaims.size());
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
  void discoveryClaimHonorsJobFilterAndRetryCutoff() {
    long firstRepositoryId = insertRepository("target-one", "maven2");
    long secondRepositoryId = insertRepository("target-two", "maven2");
    long firstJobId = insertMigrationJob(true);
    long secondJobId = insertMigrationJob(true);
    RepositoryDataMigrationDao dao = new JdbcRepositoryDataMigrationDao(jdbc(), jsonColumns());
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
    String path = "com/acme/app/1.0/app-1.0.jar";
    Instant updated = Instant.parse("2026-01-01T00:00:00Z");
    return new RepositoryDataMigrationAssetRecord(
        null,
        0,
        "#12:0",
        "#11:0",
        path,
        HashColumns.pathHash(path),
        RepositoryFormat.MAVEN2,
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
