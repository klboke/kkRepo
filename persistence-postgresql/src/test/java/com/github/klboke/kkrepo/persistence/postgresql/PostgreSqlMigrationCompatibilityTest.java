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
  /** Derived from the classpath so adding a migration never needs a constant bump (see PR #472). */
  private static final java.util.List<Integer> MIGRATION_VERSIONS = classpathVersions();
  private static final String LATEST_MIGRATION =
      Integer.toString(MIGRATION_VERSIONS.get(MIGRATION_VERSIONS.size() - 1));

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
    assertEquals(LATEST_MIGRATION, flyway().info().current().getVersion().getVersion());
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

  /** Versioned PostgreSQL migrations present on the classpath, sorted ascending. */
  private static java.util.List<Integer> classpathVersions() {
    try {
      var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
          .getResources("classpath:db/migration/postgresql/V*.sql");
      var versions = new java.util.ArrayList<Integer>();
      var pattern = java.util.regex.Pattern.compile("^V(\\d+)__.*\\.sql$");
      for (var resource : resources) {
        String name = resource.getFilename();
        var matcher = pattern.matcher(name == null ? "" : name);
        if (matcher.matches()) {
          versions.add(Integer.parseInt(matcher.group(1)));
        }
      }
      java.util.Collections.sort(versions);
      if (versions.isEmpty()) {
        throw new IllegalStateException("No versioned PostgreSQL migrations found");
      }
      return java.util.List.copyOf(versions);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
