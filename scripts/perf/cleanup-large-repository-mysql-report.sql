SELECT 'environment' AS section,
       VERSION() AS database_version,
       @@innodb_buffer_pool_size AS buffer_pool_bytes;

SELECT 'component-first-page' AS section;
EXPLAIN ANALYZE
SELECT *
FROM component
WHERE repository_id = 1
ORDER BY cleanup_namespace_prefix, cleanup_namespace_hash,
         cleanup_name_prefix, cleanup_name_hash, cleanup_kind_key, id
LIMIT 1001;

SET @cursor_component_id = FLOOR(@component_count / 2);
SELECT namespace, name, kind
INTO @cursor_namespace, @cursor_name, @cursor_kind
FROM component
WHERE id = @cursor_component_id;

SELECT 'component-middle-keyset-page' AS section;
SET @component_page_sql = CONCAT(
  'EXPLAIN ANALYZE SELECT * FROM component WHERE repository_id = 1 AND ',
  '((cleanup_namespace_prefix > CAST(? AS BINARY)) OR ',
  '(cleanup_namespace_prefix = CAST(? AS BINARY) AND ',
  'cleanup_namespace_hash > UNHEX(SHA2(?, 256))) OR ',
  '(cleanup_namespace_prefix = CAST(? AS BINARY) AND ',
  'cleanup_namespace_hash = UNHEX(SHA2(?, 256)) AND ',
  'cleanup_name_prefix > CAST(? AS BINARY)) OR ',
  '(cleanup_namespace_prefix = CAST(? AS BINARY) AND ',
  'cleanup_namespace_hash = UNHEX(SHA2(?, 256)) AND ',
  'cleanup_name_prefix = CAST(? AS BINARY) AND ',
  'cleanup_name_hash > UNHEX(SHA2(?, 256))) OR ',
  '(cleanup_namespace_prefix = CAST(? AS BINARY) AND ',
  'cleanup_namespace_hash = UNHEX(SHA2(?, 256)) AND ',
  'cleanup_name_prefix = CAST(? AS BINARY) AND ',
  'cleanup_name_hash = UNHEX(SHA2(?, 256)) AND ',
  'cleanup_kind_key > CAST(? AS BINARY))) ',
  'ORDER BY cleanup_namespace_prefix, cleanup_namespace_hash, ',
  'cleanup_name_prefix, cleanup_name_hash, cleanup_kind_key, id LIMIT 1001');
PREPARE component_page_statement FROM @component_page_sql;
EXECUTE component_page_statement
  USING @cursor_namespace,
        @cursor_namespace, @cursor_namespace,
        @cursor_namespace, @cursor_namespace, @cursor_name,
        @cursor_namespace, @cursor_namespace, @cursor_name, @cursor_name,
        @cursor_namespace, @cursor_namespace, @cursor_name, @cursor_name, @cursor_kind;
DEALLOCATE PREPARE component_page_statement;

SELECT 'unbound-asset-first-keyset-page' AS section;
EXPLAIN ANALYZE
WITH bounded AS (
  SELECT id
  FROM asset
  WHERE repository_id = 1
    AND component_id IS NULL
    AND id > 0
  ORDER BY repository_id, id
  LIMIT 1001
)
SELECT asset.id, asset.path, joined_blob.id AS joined_blob_id
FROM bounded
JOIN asset ON asset.id = bounded.id
LEFT JOIN asset_blob joined_blob ON joined_blob.id = asset.asset_blob_id
ORDER BY asset.id;

SELECT 'unbound-asset-middle-keyset-page' AS section;
SET @asset_cursor = @component_count + FLOOR(@unbound_asset_count / 2);
EXPLAIN ANALYZE
WITH bounded AS (
  SELECT id
  FROM asset
  WHERE repository_id = 1
    AND component_id IS NULL
    AND id > @asset_cursor
  ORDER BY repository_id, id
  LIMIT 1001
)
SELECT asset.id, asset.path, joined_blob.id AS joined_blob_id
FROM bounded
JOIN asset ON asset.id = bounded.id
LEFT JOIN asset_blob joined_blob ON joined_blob.id = asset.asset_blob_id
ORDER BY asset.id;

SELECT 'policy-keyset-page' AS section;
EXPLAIN ANALYZE
SELECT *
FROM cleanup_policy
WHERE state <> 'DELETED' AND id > 0
ORDER BY id
LIMIT 26;

SELECT 'policy-target-batch' AS section;
EXPLAIN ANALYZE
SELECT binding.cleanup_policy_id,
       repository.id,
       repository.name,
       repository.format,
       repository.type
FROM repository_cleanup_policy binding
JOIN repository ON repository.id = binding.repository_id
WHERE binding.cleanup_policy_id IN
  (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
   14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25)
ORDER BY binding.cleanup_policy_id, repository.name;

SELECT 'schedule-batch' AS section;
EXPLAIN ANALYZE
SELECT *
FROM cleanup_policy_schedule
WHERE policy_id IN
  (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
   14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25)
ORDER BY policy_id;

SELECT 'bounded-run-details' AS section;
SET SESSION group_concat_max_len = 1048576;
SELECT GROUP_CONCAT(
         CONCAT(
           '(SELECT * FROM cleanup_run_item FORCE INDEX (idx_cleanup_run_item_repository) ',
           'WHERE run_repository_id = ', id,
           ' ORDER BY id LIMIT 50)')
         ORDER BY id SEPARATOR ' UNION ALL ')
INTO @run_detail_shards
FROM (
  SELECT id
  FROM cleanup_run_repository
  WHERE run_id = @history_run_count + 1
  ORDER BY id
  LIMIT 50
) bounded_repository;
SET @run_detail_sql = CONCAT(
  'EXPLAIN ANALYZE SELECT bounded.id, bounded.run_repository_id ',
  'FROM (', @run_detail_shards, ') bounded ',
  'ORDER BY bounded.run_repository_id, bounded.id');
PREPARE run_detail_statement FROM @run_detail_sql;
EXECUTE run_detail_statement;
DEALLOCATE PREPARE run_detail_statement;

SELECT 'history-retention-candidates' AS section;
EXPLAIN ANALYZE
SELECT cleanup.id
FROM cleanup_run cleanup
JOIN (
  SELECT policy_id, id AS retained_floor
  FROM (
    SELECT policy_id,
           id,
           ROW_NUMBER() OVER (PARTITION BY policy_id ORDER BY id DESC) AS retention_rank
    FROM cleanup_run
  ) ranked
  WHERE retention_rank = 10
) retention
  ON retention.policy_id = cleanup.policy_id
 AND cleanup.id < retention.retained_floor
WHERE cleanup.state IN (
    'SUCCEEDED', 'SUCCEEDED_TRUNCATED', 'PARTIAL_LIMIT_REACHED',
    'PARTIAL', 'FAILED', 'CANCELLED')
  AND cleanup.completed_at < TIMESTAMP('2026-01-01 00:00:00')
ORDER BY cleanup.completed_at, cleanup.id
LIMIT 25;

SELECT 'usage-projection-lock-read' AS section;
INSERT INTO cleanup_usage_tracking_repository
  (repository_id, tracking_started_at, updated_at)
SELECT id, TIMESTAMP('2026-01-01 00:00:00'), TIMESTAMP('2026-01-01 00:00:00')
FROM repository
ON DUPLICATE KEY UPDATE repository_id = VALUES(repository_id);
EXPLAIN ANALYZE
SELECT repository_id, tracking_started_at
FROM cleanup_usage_tracking_repository
ORDER BY repository_id
FOR UPDATE;

SELECT 'bounded-history-write-amplification' AS section;
CREATE TEMPORARY TABLE cleanup_perf_retention_candidate (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY
);
INSERT INTO cleanup_perf_retention_candidate (id)
SELECT cleanup.id
FROM cleanup_run cleanup
JOIN (
  SELECT policy_id, id AS retained_floor
  FROM (
    SELECT policy_id,
           id,
           ROW_NUMBER() OVER (PARTITION BY policy_id ORDER BY id DESC) AS retention_rank
    FROM cleanup_run
  ) ranked
  WHERE retention_rank = 10
) retention
  ON retention.policy_id = cleanup.policy_id
 AND cleanup.id < retention.retained_floor
WHERE cleanup.state IN (
    'SUCCEEDED', 'SUCCEEDED_TRUNCATED', 'PARTIAL_LIMIT_REACHED',
    'PARTIAL', 'FAILED', 'CANCELLED')
  AND cleanup.completed_at < TIMESTAMP('2026-01-01 00:00:00')
ORDER BY cleanup.completed_at, cleanup.id
LIMIT 25;
SET @run_items_before = (SELECT COUNT(*) FROM cleanup_run_item);
DELETE FROM cleanup_run_item
WHERE id IN (
  SELECT bounded.id
  FROM (
    SELECT item.id
    FROM cleanup_run_item item
    JOIN cleanup_run_repository repository
      ON repository.id = item.run_repository_id
    JOIN cleanup_perf_retention_candidate candidate
      ON candidate.id = repository.run_id
    ORDER BY item.id
    LIMIT 500
  ) bounded
);
SET @deleted_run_items = ROW_COUNT();
DELETE FROM cleanup_run
WHERE id IN (SELECT id FROM cleanup_perf_retention_candidate)
  AND NOT EXISTS (
    SELECT 1
    FROM cleanup_run_repository repository
    JOIN cleanup_run_item item ON item.run_repository_id = repository.id
    WHERE repository.run_id = cleanup_run.id)
ORDER BY completed_at, id;
SET @deleted_runs = ROW_COUNT();
SELECT CONCAT(
         'bounded-history-result=',
         @deleted_run_items,
         ',',
         @deleted_runs,
         ',',
         @run_items_before - (SELECT COUNT(*) FROM cleanup_run_item)) AS result;

SELECT 'final-counts' AS section,
       (SELECT COUNT(*) FROM component) AS components,
       (SELECT COUNT(*) FROM asset) AS assets,
       (SELECT COUNT(*) FROM cleanup_policy) AS policies,
       (SELECT COUNT(*) FROM repository_cleanup_policy) AS targets,
       (SELECT COUNT(*) FROM cleanup_run) AS runs,
       (SELECT COUNT(*) FROM cleanup_run_repository) AS run_repositories,
       (SELECT COUNT(*) FROM cleanup_run_item) AS run_items;
