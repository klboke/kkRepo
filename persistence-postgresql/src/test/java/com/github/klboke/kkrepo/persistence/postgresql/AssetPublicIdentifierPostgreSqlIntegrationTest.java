package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetPublicIdentifierRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetPublicIdentifierRecord.IdentifierType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.JdbcAssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssetPublicIdentifierPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  @Test
  void duplicateRegistrationLeavesOuterTransactionUsableAndDeletionCreatesTombstone() {
    long repositoryId = insertRepository();
    AssetDao dao = new JdbcAssetDao(jdbc(), jsonColumns());
    long assetId = dao.insertAsset(new AssetRecord(
        null, repositoryId, null, null, RepositoryFormat.RAW, "tools/setup.exe",
        HashColumns.pathHash("tools/setup.exe"), "setup.exe", "asset",
        "application/octet-stream", 4L, null, Instant.EPOCH, Map.of()));
    String opaque = "fedcba98765432100123456789abcdef";

    inTransaction(() -> {
      assertTrue(dao.tryInsertPublicIdentifier(identifier(repositoryId, opaque, assetId)));
      assertFalse(dao.tryInsertPublicIdentifier(identifier(repositoryId, opaque, assetId)));
      assertEquals(assetId, dao.lockPublicIdentifier(repositoryId, opaque).orElseThrow().assetId());
    });

    assertEquals(1, dao.deleteAssetById(assetId));
    assertEquals(null, dao.findPublicIdentifier(repositoryId, opaque).orElseThrow().assetId());
  }

  private long insertRepository() {
    jdbc().update("""
        INSERT INTO blob_store (name, type, attributes_json)
        VALUES ('public-id-store', 'S3', '{}'::jsonb)
        """);
    Long blobStoreId = jdbc().queryForObject(
        "SELECT id FROM blob_store WHERE name = 'public-id-store'", Long.class);
    jdbc().update("""
        INSERT INTO repository
          (name, format, type, recipe_name, blob_store_id, attributes_json)
        VALUES ('windows-components', 'raw', 'hosted', 'raw-hosted', ?, '{}'::jsonb)
        """, blobStoreId);
    return jdbc().queryForObject(
        "SELECT id FROM repository WHERE name = 'windows-components'", Long.class);
  }

  private static AssetPublicIdentifierRecord identifier(
      long repositoryId, String opaqueId, long assetId) {
    return new AssetPublicIdentifierRecord(
        null, repositoryId, opaqueId, assetId, null, IdentifierType.NEXUS_ALIAS,
        "http://nexus.example/", 99L, Instant.EPOCH);
  }
}
