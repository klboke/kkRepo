-- Cover the admin inventory count/size aggregate without scanning asset payload rows.
-- Online and restart-safe on existing large repositories.
SET @kkrepo_usage_index_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE asset ADD INDEX idx_asset_repository_usage (repository_id, size), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'asset'
    AND index_name = 'idx_asset_repository_usage'
);
PREPARE kkrepo_usage_index_statement FROM @kkrepo_usage_index_sql;
EXECUTE kkrepo_usage_index_statement;
DEALLOCATE PREPARE kkrepo_usage_index_statement;
