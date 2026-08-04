-- Cleanup scans run against large live tables. Every DDL is both online and restart-safe because
-- MySQL commits ALTER TABLE independently and Flyway repair may need to resume this migration.
SET @kkrepo_cleanup_namespace_prefix_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE component ADD COLUMN cleanup_namespace_prefix VARBINARY(384) GENERATED ALWAYS AS (LEFT(CAST(COALESCE(namespace, '''') AS BINARY), 384)) VIRTUAL, ALGORITHM=INSTANT',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'component'
    AND column_name = 'cleanup_namespace_prefix'
);
PREPARE kkrepo_cleanup_namespace_prefix_statement
  FROM @kkrepo_cleanup_namespace_prefix_sql;
EXECUTE kkrepo_cleanup_namespace_prefix_statement;
DEALLOCATE PREPARE kkrepo_cleanup_namespace_prefix_statement;

SET @kkrepo_cleanup_namespace_hash_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE component ADD COLUMN cleanup_namespace_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(COALESCE(namespace, ''''), 256))) VIRTUAL, ALGORITHM=INSTANT',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'component'
    AND column_name = 'cleanup_namespace_hash'
);
PREPARE kkrepo_cleanup_namespace_hash_statement
  FROM @kkrepo_cleanup_namespace_hash_sql;
EXECUTE kkrepo_cleanup_namespace_hash_statement;
DEALLOCATE PREPARE kkrepo_cleanup_namespace_hash_statement;

SET @kkrepo_cleanup_name_prefix_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE component ADD COLUMN cleanup_name_prefix VARBINARY(384) GENERATED ALWAYS AS (LEFT(CAST(name AS BINARY), 384)) VIRTUAL, ALGORITHM=INSTANT',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'component'
    AND column_name = 'cleanup_name_prefix'
);
PREPARE kkrepo_cleanup_name_prefix_statement FROM @kkrepo_cleanup_name_prefix_sql;
EXECUTE kkrepo_cleanup_name_prefix_statement;
DEALLOCATE PREPARE kkrepo_cleanup_name_prefix_statement;

SET @kkrepo_cleanup_name_hash_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE component ADD COLUMN cleanup_name_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(name, 256))) VIRTUAL, ALGORITHM=INSTANT',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'component'
    AND column_name = 'cleanup_name_hash'
);
PREPARE kkrepo_cleanup_name_hash_statement FROM @kkrepo_cleanup_name_hash_sql;
EXECUTE kkrepo_cleanup_name_hash_statement;
DEALLOCATE PREPARE kkrepo_cleanup_name_hash_statement;

SET @kkrepo_cleanup_kind_key_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE component ADD COLUMN cleanup_kind_key VARBINARY(200) GENERATED ALWAYS AS (LEFT(CAST(COALESCE(kind, '''') AS BINARY), 200)) VIRTUAL, ALGORITHM=INSTANT',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'component'
    AND column_name = 'cleanup_kind_key'
);
PREPARE kkrepo_cleanup_kind_key_statement FROM @kkrepo_cleanup_kind_key_sql;
EXECUTE kkrepo_cleanup_kind_key_statement;
DEALLOCATE PREPARE kkrepo_cleanup_kind_key_statement;

SET @kkrepo_component_cleanup_scan_index_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE component ADD INDEX idx_component_cleanup_scan (repository_id, cleanup_namespace_prefix, cleanup_namespace_hash, cleanup_name_prefix, cleanup_name_hash, cleanup_kind_key, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'component'
    AND index_name = 'idx_component_cleanup_scan'
);
PREPARE kkrepo_component_cleanup_scan_index_statement
  FROM @kkrepo_component_cleanup_scan_index_sql;
EXECUTE kkrepo_component_cleanup_scan_index_statement;
DEALLOCATE PREPARE kkrepo_component_cleanup_scan_index_statement;

SET @kkrepo_asset_cleanup_unbound_index_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE asset ADD INDEX idx_asset_cleanup_unbound (repository_id, component_id, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'asset'
    AND index_name = 'idx_asset_cleanup_unbound'
);
PREPARE kkrepo_asset_cleanup_unbound_index_statement
  FROM @kkrepo_asset_cleanup_unbound_index_sql;
EXECUTE kkrepo_asset_cleanup_unbound_index_statement;
DEALLOCATE PREPARE kkrepo_asset_cleanup_unbound_index_statement;

SET @kkrepo_docker_manifest_cleanup_index_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE docker_manifest ADD INDEX idx_docker_manifest_cleanup (repository_id, deleted_at, asset_id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'docker_manifest'
    AND index_name = 'idx_docker_manifest_cleanup'
);
PREPARE kkrepo_docker_manifest_cleanup_index_statement
  FROM @kkrepo_docker_manifest_cleanup_index_sql;
EXECUTE kkrepo_docker_manifest_cleanup_index_statement;
DEALLOCATE PREPARE kkrepo_docker_manifest_cleanup_index_statement;

SET @kkrepo_cleanup_protection_scan_index_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE cleanup_protection ADD INDEX idx_cleanup_protection_scan (repository_id, subject_key_hash, enabled, expires_at, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'cleanup_protection'
    AND index_name = 'idx_cleanup_protection_scan'
);
PREPARE kkrepo_cleanup_protection_scan_index_statement
  FROM @kkrepo_cleanup_protection_scan_index_sql;
EXECUTE kkrepo_cleanup_protection_scan_index_statement;
DEALLOCATE PREPARE kkrepo_cleanup_protection_scan_index_statement;
