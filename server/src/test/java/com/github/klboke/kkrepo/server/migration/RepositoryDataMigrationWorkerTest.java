package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDataMigrationDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDataMigrationDao.AssetClaim;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryDataMigrationAssetRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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

  private static AssetClaim claim(long repositoryJobId, long migrationJobId, String path) {
    return claim(repositoryJobId, migrationJobId, RepositoryFormat.MAVEN2, path);
  }

  private static AssetClaim claim(
      long repositoryJobId,
      long migrationJobId,
      RepositoryFormat format,
      String path) {
    RepositoryDataMigrationAssetRecord asset = new RepositoryDataMigrationAssetRecord(
        null,
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
