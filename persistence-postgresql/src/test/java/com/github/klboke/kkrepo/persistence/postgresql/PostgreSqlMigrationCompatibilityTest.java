package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Proves the PostgreSQL baseline validates and remains idempotent on repeated startup. */
class PostgreSqlMigrationCompatibilityTest extends PostgreSqlIntegrationTestSupport {
  private static final int LATEST_MIGRATION = 56;

  @Test
  void completionIndexesCanResumeAnInterruptedConcurrentBuild() throws Exception {
    String path = "db/migration/postgresql/V55__security_scan_completion_indexes.sql";
    try (InputStream stream = getClass().getResourceAsStream("/" + path + ".conf")) {
      assertNotNull(stream);
      assertEquals("executeInTransaction=false", new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim());
    }
    jdbc().execute("DROP INDEX idx_security_scan_run_completion");
    jdbc().execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.support.EncodedResource(
              new org.springframework.core.io.ClassPathResource(path), StandardCharsets.UTF_8));
      return null;
    });
    assertEquals(5, jdbc().queryForObject("""
        SELECT COUNT(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname IN ('idx_security_scan_task_completion',
          'idx_security_scan_task_repo_completion', 'idx_security_scan_run_completion',
          'idx_security_scan_task_status_completion', 'idx_security_scan_task_repo_status_completion')
          AND i.indisvalid
        """, Integer.class));
  }

  @Test
  void policyReferenceIndexesCanResumeAnInterruptedConcurrentBuild() throws Exception {
    String path = "db/migration/postgresql/V56__security_scan_policy_reference_indexes.sql";
    try (InputStream stream = getClass().getResourceAsStream("/" + path + ".conf")) {
      assertNotNull(stream);
      assertEquals("executeInTransaction=false", new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim());
    }
    jdbc().execute("DROP INDEX idx_asset_scan_policy_ref");
    jdbc().execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.support.EncodedResource(
              new org.springframework.core.io.ClassPathResource(path), StandardCharsets.UTF_8));
      return null;
    });
    assertEquals(4, jdbc().queryForObject("""
        SELECT COUNT(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname IN ('idx_repository_scan_policy_ref', 'idx_asset_scan_policy_ref',
          'idx_asset_context_policy_ref', 'idx_scan_waiver_policy_ref') AND i.indisvalid
        """, Integer.class));
  }

  @Test
  void baselineValidatesAndSecondMigrateHasNoPendingWork() {
    assertTrue(flyway().validateWithResult().validationSuccessful);
    var result = flyway().migrate();
    assertEquals(0, result.migrationsExecuted);
    assertEquals(
        Integer.toString(LATEST_MIGRATION),
        flyway().info().current().getVersion().getVersion());
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
  void cleanupIndexesAreOnlineAndRepairableAfterInterruptedBuilds() throws Exception {
    String migration;
    try (InputStream stream = getClass().getResourceAsStream(
        "/db/migration/postgresql/V40__cleanup_scan_indexes.sql")) {
      assertNotNull(stream);
      migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    for (String index : java.util.List.of(
        "idx_component_cleanup_scan",
        "idx_asset_cleanup_unbound",
        "idx_docker_manifest_cleanup",
        "idx_cleanup_protection_scan")) {
      int drop = migration.indexOf("DROP INDEX CONCURRENTLY IF EXISTS " + index);
      int create = migration.indexOf("CREATE INDEX CONCURRENTLY " + index);
      assertTrue(drop >= 0 && create > drop, index + " must be rebuilt restart-safely");
    }
    try (InputStream stream = getClass().getResourceAsStream(
        "/db/migration/postgresql/V40__cleanup_scan_indexes.sql.conf")) {
      assertNotNull(stream);
      assertEquals(
          "executeInTransaction=false",
          new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim());
    }
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
