package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;

/** Proves the PostgreSQL baseline validates and remains idempotent on repeated startup. */
class PostgreSqlMigrationCompatibilityTest extends PostgreSqlIntegrationTestSupport {
  @Test
  void baselineValidatesAndSecondMigrateHasNoPendingWork() {
    assertTrue(flyway().validateWithResult().validationSuccessful);
    var result = flyway().migrate();
    assertEquals(0, result.migrationsExecuted);
    assertEquals("38", flyway().info().current().getVersion().getVersion());
  }

  @Test
  void onlineBlobReferenceConstraintsAreValidatedAfterV37() {
    assertEquals(2, jdbc().queryForObject("""
        SELECT COUNT(*)
        FROM pg_constraint
        WHERE conrelid = 'asset_blob'::regclass
          AND conname IN (
            'ck_asset_blob_external_reference_nonnegative',
            'ck_asset_blob_external_reference_live')
          AND convalidated
        """, Integer.class));
  }

  @Test
  void migrationFencesSecurityDocumentsFromLegacyBlobGcUpdates() {
    jdbc().update("""
        INSERT INTO blob_store (name, type, attributes_json)
        VALUES ('legacy-gc-fence', 'S3', CAST('{}' AS jsonb))
        """);
    long blobStoreId = jdbc().queryForObject(
        "SELECT id FROM blob_store WHERE name = 'legacy-gc-fence'", Long.class);
    jdbc().update("""
        INSERT INTO asset_blob
          (blob_store_id, blob_ref, blob_ref_hash, object_key, object_key_hash,
           size, attributes_json)
        VALUES (?, 'legacy@document', ?, 'security/document.json', ?, 1, CAST('{}' AS jsonb))
        """, blobStoreId, new byte[32], new byte[32]);
    long blobId = jdbc().queryForObject(
        "SELECT id FROM asset_blob WHERE blob_store_id = ?", Long.class, blobStoreId);
    assertTrue(stores().blobReferences().retain("security-sbom", 1, blobId));

    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc().update("""
            UPDATE asset_blob
            SET deleted_at = CURRENT_TIMESTAMP,
                delete_reason = 'legacy orphan reconcile',
                delete_claimed_at = NULL
            WHERE id = ?
            """, blobId));
    assertNull(jdbc().queryForObject(
        "SELECT delete_reason FROM asset_blob WHERE id = ?",
        String.class,
        blobId));

    assertEquals(1, stores().blobReferences().release("security-sbom", 1, blobId));
    jdbc().update(
        "UPDATE asset_blob SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", blobId);
    assertNotNull(jdbc().queryForObject(
        "SELECT deleted_at FROM asset_blob WHERE id = ?",
        java.time.OffsetDateTime.class,
        blobId));
  }
}
