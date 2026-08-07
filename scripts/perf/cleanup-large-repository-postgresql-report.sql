\set ON_ERROR_STOP on
\timing on

SELECT 'environment' AS section, version() AS database_version,
       current_setting('shared_buffers') AS shared_buffers;

SELECT 'component-first-page' AS section;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT *
FROM component
WHERE repository_id = 1
ORDER BY COALESCE(namespace, '') COLLATE "C",
         name COLLATE "C",
         COALESCE(kind, '') COLLATE "C",
         id
LIMIT 1001;

SELECT 'component-middle-keyset-page' AS section;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT *
FROM component
WHERE repository_id = 1
  AND (COALESCE(namespace, '') COLLATE "C",
       name COLLATE "C",
       COALESCE(kind, '') COLLATE "C")
    > ((SELECT COALESCE(namespace, '') FROM component WHERE id = :component_count / 2),
       (SELECT name FROM component WHERE id = :component_count / 2),
       (SELECT COALESCE(kind, '') FROM component WHERE id = :component_count / 2))
ORDER BY COALESCE(namespace, '') COLLATE "C",
         name COLLATE "C",
         COALESCE(kind, '') COLLATE "C",
         id
LIMIT 1001;

SELECT 'unbound-asset-first-keyset-page' AS section;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
WITH bounded AS MATERIALIZED (
  SELECT id
  FROM asset
  WHERE (CASE WHEN component_id IS NULL THEN repository_id END) = 1
    AND component_id IS NULL
    AND id > 0
  ORDER BY (CASE WHEN component_id IS NULL THEN repository_id END), id
  LIMIT 1001
)
SELECT asset.id, asset.path, joined_blob.id AS joined_blob_id
FROM bounded
JOIN asset ON asset.id = bounded.id
LEFT JOIN asset_blob joined_blob ON joined_blob.id = asset.asset_blob_id
ORDER BY asset.id;

SELECT 'unbound-asset-middle-keyset-page' AS section;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
WITH bounded AS MATERIALIZED (
  SELECT id
  FROM asset
  WHERE (CASE WHEN component_id IS NULL THEN repository_id END) = 1
    AND component_id IS NULL
    AND id > :component_count + (:unbound_asset_count / 2)
  ORDER BY (CASE WHEN component_id IS NULL THEN repository_id END), id
  LIMIT 1001
)
SELECT asset.id, asset.path, joined_blob.id AS joined_blob_id
FROM bounded
JOIN asset ON asset.id = bounded.id
LEFT JOIN asset_blob joined_blob ON joined_blob.id = asset.asset_blob_id
ORDER BY asset.id;

SELECT 'policy-keyset-page' AS section;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT *
FROM cleanup_policy
WHERE state <> 'DELETED' AND id > 0
ORDER BY id
LIMIT 26;

SELECT 'policy-target-batch' AS section;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT binding.cleanup_policy_id,
       repository.id,
       repository.name,
       repository.format,
       repository.type
FROM repository_cleanup_policy binding
JOIN repository ON repository.id = binding.repository_id
WHERE binding.cleanup_policy_id = ANY (
  ARRAY[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
        14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25]::bigint[])
ORDER BY binding.cleanup_policy_id, repository.name;

SELECT 'schedule-batch' AS section;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT *
FROM cleanup_policy_schedule
WHERE policy_id = ANY (
  ARRAY[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
        14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25]::bigint[])
ORDER BY policy_id;

SELECT 'bounded-run-details' AS section;
SELECT
  'EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) '
  || 'SELECT bounded.id, bounded.run_repository_id FROM ('
  || string_agg(
       format(
         '(SELECT * FROM cleanup_run_item WHERE run_repository_id = %s ORDER BY id LIMIT 50)',
         id),
       ' UNION ALL ' ORDER BY id)
  || ') bounded ORDER BY bounded.run_repository_id, bounded.id;'
FROM (
  SELECT id
  FROM cleanup_run_repository
  WHERE run_id = :history_run_count + 1
  ORDER BY id
  LIMIT 50
) bounded_repository
\gexec

SELECT 'history-retention-candidates' AS section;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
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
  AND cleanup.completed_at < TIMESTAMPTZ '2026-01-01 00:00:00Z'
ORDER BY cleanup.completed_at, cleanup.id
LIMIT 25;

SELECT 'usage-projection-lock-read' AS section;
INSERT INTO cleanup_usage_tracking_repository
  (repository_id, tracking_started_at, updated_at)
SELECT id, TIMESTAMPTZ '2026-01-01 00:00:00Z', TIMESTAMPTZ '2026-01-01 00:00:00Z'
FROM repository
ON CONFLICT (repository_id) DO NOTHING;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT repository_id, tracking_started_at
FROM cleanup_usage_tracking_repository
ORDER BY repository_id
FOR UPDATE;

SELECT 'bounded-history-write-amplification' AS section;
CREATE TEMPORARY TABLE cleanup_perf_retention_candidate (id BIGINT PRIMARY KEY);
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
  AND cleanup.completed_at < TIMESTAMPTZ '2026-01-01 00:00:00Z'
ORDER BY cleanup.completed_at, cleanup.id
LIMIT 25;
CREATE TEMPORARY TABLE cleanup_perf_deleted_item_count (deleted_count BIGINT NOT NULL);
WITH item_batch AS MATERIALIZED (
  SELECT item.id
  FROM cleanup_run_item item
  JOIN cleanup_run_repository repository
    ON repository.id = item.run_repository_id
  WHERE repository.run_id IN (SELECT id FROM cleanup_perf_retention_candidate)
  ORDER BY item.id
  LIMIT 500
), deleted_items AS (
  DELETE FROM cleanup_run_item item
  USING item_batch
  WHERE item.id = item_batch.id
  RETURNING item.id
)
INSERT INTO cleanup_perf_deleted_item_count
SELECT COUNT(*) FROM deleted_items;

CREATE TEMPORARY TABLE cleanup_perf_deleted_run_count (deleted_count BIGINT NOT NULL);
WITH deleted_runs AS (
  DELETE FROM cleanup_run run
  USING cleanup_perf_retention_candidate candidate
  WHERE run.id = candidate.id
    AND NOT EXISTS (
      SELECT 1
      FROM cleanup_run_repository repository
      JOIN cleanup_run_item item ON item.run_repository_id = repository.id
      WHERE repository.run_id = run.id)
  RETURNING run.id
)
INSERT INTO cleanup_perf_deleted_run_count
SELECT COUNT(*) FROM deleted_runs;

SELECT 'bounded-history-result='
       || (SELECT deleted_count FROM cleanup_perf_deleted_item_count)::text
       || ','
       || (SELECT deleted_count FROM cleanup_perf_deleted_run_count)::text
       || ','
       || (SELECT deleted_count FROM cleanup_perf_deleted_item_count)::text AS result;

SELECT 'final-counts' AS section,
       (SELECT COUNT(*) FROM component) AS components,
       (SELECT COUNT(*) FROM asset) AS assets,
       (SELECT COUNT(*) FROM cleanup_policy) AS policies,
       (SELECT COUNT(*) FROM repository_cleanup_policy) AS targets,
       (SELECT COUNT(*) FROM cleanup_run) AS runs,
       (SELECT COUNT(*) FROM cleanup_run_repository) AS run_repositories,
       (SELECT COUNT(*) FROM cleanup_run_item) AS run_items;
