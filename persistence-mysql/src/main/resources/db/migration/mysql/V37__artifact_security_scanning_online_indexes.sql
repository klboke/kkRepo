-- Explicit online DDL keeps uploads and download timestamp updates writable during the build.
-- MySQL commits each ALTER independently. Check information_schema before every operation so a
-- repaired Flyway migration can resume after either index was already committed.
SET @kkrepo_asset_policy_index_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE asset ADD INDEX idx_asset_repository_id (repository_id, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'asset'
    AND index_name = 'idx_asset_repository_id'
);
PREPARE kkrepo_asset_policy_index_statement FROM @kkrepo_asset_policy_index_sql;
EXECUTE kkrepo_asset_policy_index_statement;
DEALLOCATE PREPARE kkrepo_asset_policy_index_statement;

SET @kkrepo_asset_content_change_index_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE asset ADD INDEX idx_asset_last_updated_id (last_updated_at, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'asset'
    AND index_name = 'idx_asset_last_updated_id'
);
PREPARE kkrepo_asset_content_change_index_statement
  FROM @kkrepo_asset_content_change_index_sql;
EXECUTE kkrepo_asset_content_change_index_statement;
DEALLOCATE PREPARE kkrepo_asset_content_change_index_statement;

SET @kkrepo_docker_policy_index_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE docker_manifest_reference ADD INDEX idx_docker_reference_policy_lookup (repository_id, digest_hash, manifest_id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'docker_manifest_reference'
    AND index_name = 'idx_docker_reference_policy_lookup'
);
PREPARE kkrepo_docker_policy_index_statement FROM @kkrepo_docker_policy_index_sql;
EXECUTE kkrepo_docker_policy_index_statement;
DEALLOCATE PREPARE kkrepo_docker_policy_index_statement;
