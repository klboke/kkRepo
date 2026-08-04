package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MySqlV40MigrationCompatibilityTest extends MySqlIntegrationTestSupport {

  @Test
  void rebuildMigrationBackfillsNormalizedPypiSimpleMarkersAndResetsFailures() throws Exception {
    long repositoryId = insertRepository("migrated-pypi", "pypi");
    long blobStoreId = jdbc().queryForObject(
        "SELECT blob_store_id FROM repository WHERE id = ?", Long.class, repositoryId);
    String blobRef = "migrated-pypi/demo-pkg-1.0.0.whl";
    String objectKey = "objects/demo-pkg-1.0.0.whl";
    jdbc().update("""
        INSERT INTO asset_blob
          (blob_store_id, blob_ref, blob_ref_hash, object_key, object_key_hash, size, attributes_json)
        VALUES (?, ?, UNHEX(SHA2(?, 256)), ?, UNHEX(SHA2(?, 256)), 42, JSON_OBJECT())
        """, blobStoreId, blobRef, blobRef, objectKey, objectKey);
    long blobId = jdbc().queryForObject(
        "SELECT id FROM asset_blob WHERE blob_store_id = ? AND blob_ref_hash = UNHEX(SHA2(?, 256))",
        Long.class,
        blobStoreId,
        blobRef);
    String path = "packages/demo-pkg/1.0.0/demo_pkg-1.0.0-py3-none-any.whl";
    jdbc().update("""
        INSERT INTO asset
          (repository_id, asset_blob_id, format, path, path_hash, name, kind, size, attributes_json)
        VALUES (?, ?, 'pypi', ?, UNHEX(SHA2(?, 256)), ?, 'PACKAGE', 42, JSON_OBJECT())
        """, repositoryId, blobId, path, path, "demo_pkg-1.0.0-py3-none-any.whl");
    jdbc().update("""
        INSERT INTO repository_index_rebuild_marker
          (repository_id, index_kind, scope_key, attempts, last_attempted_at, last_error)
        VALUES (?, 'PYPI_ROOT', '', 3, CURRENT_TIMESTAMP(3), 'previous failure')
        """, repositoryId);

    executeMigration("/db/migration/mysql/V40__rebuild_migrated_pypi_simple_indexes.sql");

    assertEquals(
        List.of("PYPI_PROJECT:demo-pkg", "PYPI_ROOT:"),
        jdbc().queryForList("""
            SELECT CONCAT(index_kind, ':', scope_key)
            FROM repository_index_rebuild_marker
            WHERE repository_id = ?
            ORDER BY index_kind, scope_key
            """, String.class, repositoryId));
    assertEquals(0, jdbc().queryForObject("""
        SELECT attempts
        FROM repository_index_rebuild_marker
        WHERE repository_id = ? AND index_kind = 'PYPI_ROOT' AND scope_key = ''
        """, Integer.class, repositoryId));
    assertNull(jdbc().queryForObject("""
        SELECT last_error
        FROM repository_index_rebuild_marker
        WHERE repository_id = ? AND index_kind = 'PYPI_ROOT' AND scope_key = ''
        """, String.class, repositoryId));
  }

  private void executeMigration(String resource) throws Exception {
    try (InputStream stream = getClass().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing migration resource " + resource);
      }
      String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      for (String statement : sql.split(";")) {
        if (!statement.isBlank()) {
          jdbc().execute(statement);
        }
      }
    }
  }
}
