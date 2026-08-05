\set ON_ERROR_STOP on
SET synchronous_commit = off;
SET maintenance_work_mem = '512MB';

INSERT INTO blob_store (id, name, type, attributes_json)
VALUES (1, 'cleanup-perf-store', 'FILE', '{}'::jsonb);

INSERT INTO repository
  (id, name, format, type, recipe_name, online, blob_store_id,
   strict_content_type_validation, attributes_json)
SELECT n,
       'cleanup-perf-maven-' || lpad(n::text, 3, '0'),
       'maven2',
       'hosted',
       'maven2-hosted',
       TRUE,
       1,
       TRUE,
       '{}'::jsonb
FROM generate_series(1, :repository_count) n;

INSERT INTO component
  (id, repository_id, format, namespace, name, version, kind, coordinate_hash,
   attributes_json, last_updated_at, created_at, updated_at)
SELECT n,
       1,
       'maven2',
       'com.example.' || lpad(((n - 1) / 10000)::text, 3, '0'),
       'artifact-' || lpad(((n - 1) / 10)::text, 7, '0'),
       '1.' || ((n - 1) % 10)::text || '.0',
       'release',
       decode(lpad(to_hex(n), 64, '0'), 'hex'),
       '{}'::jsonb,
       TIMESTAMPTZ '2025-01-01 00:00:00Z' + (n % 365) * INTERVAL '1 day',
       TIMESTAMPTZ '2025-01-01 00:00:00Z',
       TIMESTAMPTZ '2025-01-01 00:00:00Z'
FROM generate_series(1, :component_count) n;

INSERT INTO asset
  (id, repository_id, component_id, asset_blob_id, format, path, path_hash, name,
   kind, content_type, size, last_downloaded_at, last_updated_at, attributes_json,
   created_at, updated_at)
SELECT n,
       1,
       n,
       NULL,
       'maven2',
       'com/example/artifact/' || lpad(n::text, 7, '0') || '/artifact.jar',
       decode(lpad(to_hex(n), 64, '0'), 'hex'),
       'artifact.jar',
       'asset',
       'application/java-archive',
       1024,
       NULL,
       TIMESTAMPTZ '2025-01-01 00:00:00Z' + (n % 365) * INTERVAL '1 day',
       '{}'::jsonb,
       TIMESTAMPTZ '2025-01-01 00:00:00Z',
       TIMESTAMPTZ '2025-01-01 00:00:00Z'
FROM generate_series(1, :component_count) n;

INSERT INTO asset
  (id, repository_id, component_id, asset_blob_id, format, path, path_hash, name,
   kind, content_type, size, last_downloaded_at, last_updated_at, attributes_json,
   created_at, updated_at)
SELECT :component_count + n,
       ((n - 1) % :repository_count) + 1,
       NULL,
       NULL,
       'maven2',
       'unbound/' || lpad(n::text, 7, '0') || '/metadata.xml',
       decode(lpad(to_hex(:component_count + n), 64, '0'), 'hex'),
       'metadata.xml',
       'metadata',
       'application/xml',
       512,
       NULL,
       TIMESTAMPTZ '2025-01-01 00:00:00Z' + (n % 365) * INTERVAL '1 day',
       '{}'::jsonb,
       TIMESTAMPTZ '2025-01-01 00:00:00Z',
       TIMESTAMPTZ '2025-01-01 00:00:00Z'
FROM generate_series(1, :unbound_asset_count) n;

INSERT INTO cleanup_policy
  (id, name, format, mode, notes, criteria_json, revision, state,
   scan_limit_per_repository, delete_limit_per_repository, created_at, updated_at)
SELECT n,
       'cleanup-perf-policy-' || lpad(n::text, 3, '0'),
       'maven2',
       'DELETE',
       NULL,
       '{"publishedOlderThanDays":30,"lastDownloadedOlderThanDays":30}'::jsonb,
       1,
       CASE WHEN n % 2 = 0 THEN 'ACTIVE' ELSE 'PAUSED' END,
       10000,
       100,
       TIMESTAMPTZ '2026-01-01 00:00:00Z',
       TIMESTAMPTZ '2026-01-01 00:00:00Z'
FROM generate_series(1, :policy_count) n;

INSERT INTO repository_cleanup_policy
  (repository_id, cleanup_policy_id, created_at)
SELECT ((n - 1) % :repository_count) + 1,
       n,
       TIMESTAMPTZ '2026-01-01 00:00:00Z'
FROM generate_series(1, :policy_count) n;

INSERT INTO repository_cleanup_policy
  (repository_id, cleanup_policy_id, created_at)
SELECT n, 1, TIMESTAMPTZ '2026-01-01 00:00:00Z'
FROM generate_series(1, :repository_count) n
ON CONFLICT DO NOTHING;

INSERT INTO cleanup_policy_schedule
  (policy_id, cron_expression, time_zone, enabled, created_at, updated_at)
SELECT n,
       '0 ' || (n % 60)::text || ' ' || (n % 24)::text || ' * * ?',
       'Asia/Shanghai',
       n % 2 = 0,
       TIMESTAMPTZ '2026-01-01 00:00:00Z',
       TIMESTAMPTZ '2026-01-01 00:00:00Z'
FROM generate_series(1, :policy_count) n;

INSERT INTO cleanup_run
  (id, policy_id, policy_revision, mode, trigger_kind, state, cancel_requested,
   requested_by, scheduled_for, scan_limit_per_repository, delete_limit_per_repository,
   criteria_snapshot_json, repository_snapshot_json, scanned_subjects, matched_subjects,
   would_delete_subjects, deleted_subjects, failed_subjects, truncated_repositories,
   error_summary, started_at, completed_at, cancelled_at, created_at, updated_at)
SELECT n,
       ((n - 1) % :policy_count) + 1,
       1,
       'TRY_RUN',
       'MANUAL',
       'SUCCEEDED',
       FALSE,
       'cleanup-perf',
       NULL,
       10000,
       100,
       '{"publishedOlderThanDays":30}'::jsonb,
       '[]'::jsonb,
       :history_items_per_run,
       :history_items_per_run,
       :history_items_per_run,
       0,
       0,
       0,
       NULL,
       TIMESTAMPTZ '2025-01-01 00:00:00Z' + n * INTERVAL '1 second',
       TIMESTAMPTZ '2025-01-01 00:01:00Z' + n * INTERVAL '1 second',
       NULL,
       TIMESTAMPTZ '2025-01-01 00:00:00Z' + n * INTERVAL '1 second',
       TIMESTAMPTZ '2025-01-01 00:01:00Z' + n * INTERVAL '1 second'
FROM generate_series(1, :history_run_count) n;

INSERT INTO cleanup_run
  (id, policy_id, policy_revision, mode, trigger_kind, state, cancel_requested,
   requested_by, scheduled_for, scan_limit_per_repository, delete_limit_per_repository,
   criteria_snapshot_json, repository_snapshot_json, scanned_subjects, matched_subjects,
   would_delete_subjects, deleted_subjects, failed_subjects, truncated_repositories,
   error_summary, started_at, completed_at, cancelled_at, created_at, updated_at)
VALUES
  (:history_run_count + 1, 1, 1, 'TRY_RUN', 'MANUAL', 'SUCCEEDED', FALSE,
   'cleanup-perf', NULL, 10000, 100,
   '{"publishedOlderThanDays":30}'::jsonb, '[]'::jsonb,
   :detail_repository_count * :detail_items_per_repository,
   :detail_repository_count * :detail_items_per_repository,
   :detail_repository_count * :detail_items_per_repository,
   0, 0, 0, NULL,
   TIMESTAMPTZ '2026-01-01 00:00:00Z', TIMESTAMPTZ '2026-01-01 00:01:00Z', NULL,
   TIMESTAMPTZ '2026-01-01 00:00:00Z', TIMESTAMPTZ '2026-01-01 00:01:00Z');

INSERT INTO cleanup_run_repository
  (id, run_id, repository_id, repository_name, format, repository_type, state,
   attempt_count, max_attempts, next_attempt_at, fencing_token, scanned_subjects,
   matched_subjects, would_delete_subjects, deleted_subjects, failed_subjects,
   truncated, started_at, completed_at, created_at, updated_at)
SELECT n,
       n,
       ((n - 1) % :repository_count) + 1,
       'cleanup-perf-maven-' || lpad((((n - 1) % :repository_count) + 1)::text, 3, '0'),
       'maven2',
       'hosted',
       'SUCCEEDED',
       1,
       3,
       TIMESTAMPTZ '2025-01-01 00:00:00Z',
       1,
       :history_items_per_run,
       :history_items_per_run,
       :history_items_per_run,
       0,
       0,
       FALSE,
       TIMESTAMPTZ '2025-01-01 00:00:00Z' + n * INTERVAL '1 second',
       TIMESTAMPTZ '2025-01-01 00:01:00Z' + n * INTERVAL '1 second',
       TIMESTAMPTZ '2025-01-01 00:00:00Z' + n * INTERVAL '1 second',
       TIMESTAMPTZ '2025-01-01 00:01:00Z' + n * INTERVAL '1 second'
FROM generate_series(1, :history_run_count) n;

INSERT INTO cleanup_run_repository
  (id, run_id, repository_id, repository_name, format, repository_type, state,
   attempt_count, max_attempts, next_attempt_at, fencing_token, scanned_subjects,
   matched_subjects, would_delete_subjects, deleted_subjects, failed_subjects,
   truncated, started_at, completed_at, created_at, updated_at)
SELECT :history_run_count + n,
       :history_run_count + 1,
       n,
       'cleanup-perf-maven-' || lpad(n::text, 3, '0'),
       'maven2',
       'hosted',
       'SUCCEEDED',
       1,
       3,
       TIMESTAMPTZ '2026-01-01 00:00:00Z',
       1,
       :detail_items_per_repository,
       :detail_items_per_repository,
       :detail_items_per_repository,
       0,
       0,
       FALSE,
       TIMESTAMPTZ '2026-01-01 00:00:00Z',
       TIMESTAMPTZ '2026-01-01 00:01:00Z',
       TIMESTAMPTZ '2026-01-01 00:00:00Z',
       TIMESTAMPTZ '2026-01-01 00:01:00Z'
FROM generate_series(1, :detail_repository_count) n;

INSERT INTO cleanup_run_item
  (id, run_repository_id, subject_kind, subject_key, subject_key_hash, family_key,
   display_name, version, delete_path, asset_count, estimated_bytes,
   expected_usage_revision, evaluated_at, decision, reason_json, created_at, updated_at)
SELECT (repository.id - 1) * :history_items_per_run + item.n,
       repository.id,
       'COMPONENT',
       'history:' || repository.id::text || ':' || item.n::text,
       decode(md5('history:' || repository.id::text || ':' || item.n::text)
         || md5('history:' || repository.id::text || ':' || item.n::text), 'hex'),
       'history-family-' || repository.id::text,
       'history-artifact-' || item.n::text,
       '1.' || (item.n % 10)::text || '.0',
       'history/' || repository.id::text || '/' || item.n::text,
       1,
       1024,
       0,
       repository.completed_at,
       'WOULD_DELETE',
       '{}'::jsonb,
       repository.completed_at,
       repository.completed_at
FROM cleanup_run_repository repository
CROSS JOIN generate_series(1, :history_items_per_run) item(n)
WHERE repository.id <= :history_run_count;

INSERT INTO cleanup_run_item
  (id, run_repository_id, subject_kind, subject_key, subject_key_hash, family_key,
   display_name, version, delete_path, asset_count, estimated_bytes,
   expected_usage_revision, evaluated_at, decision, reason_json, created_at, updated_at)
SELECT :history_run_count * :history_items_per_run
         + (repository.id - :history_run_count - 1) * :detail_items_per_repository
         + item.n,
       repository.id,
       'COMPONENT',
       'detail:' || repository.id::text || ':' || item.n::text,
       decode(md5('detail:' || repository.id::text || ':' || item.n::text)
         || md5('detail:' || repository.id::text || ':' || item.n::text), 'hex'),
       'detail-family-' || repository.id::text,
       'detail-artifact-' || item.n::text,
       '1.' || (item.n % 10)::text || '.0',
       'detail/' || repository.id::text || '/' || item.n::text,
       1,
       1024,
       0,
       repository.completed_at,
       'WOULD_DELETE',
       '{}'::jsonb,
       repository.completed_at,
       repository.completed_at
FROM cleanup_run_repository repository
CROSS JOIN generate_series(1, :detail_items_per_repository) item(n)
WHERE repository.run_id = :history_run_count + 1;

SELECT setval(pg_get_serial_sequence('component', 'id'), :component_count, TRUE);
SELECT setval(pg_get_serial_sequence('asset', 'id'), :component_count + :unbound_asset_count, TRUE);
SELECT setval(pg_get_serial_sequence('cleanup_policy', 'id'), :policy_count, TRUE);
SELECT setval(pg_get_serial_sequence('cleanup_run', 'id'), :history_run_count + 1, TRUE);
SELECT setval(
  pg_get_serial_sequence('cleanup_run_repository', 'id'),
  :history_run_count + :detail_repository_count,
  TRUE);
SELECT setval(
  pg_get_serial_sequence('cleanup_run_item', 'id'),
  :history_run_count * :history_items_per_run
    + :detail_repository_count * :detail_items_per_repository,
  TRUE);

ANALYZE component;
ANALYZE asset;
ANALYZE cleanup_policy;
ANALYZE repository_cleanup_policy;
ANALYZE cleanup_policy_schedule;
ANALYZE cleanup_run;
ANALYZE cleanup_run_repository;
ANALYZE cleanup_run_item;

SELECT 'seed-counts' AS section,
       (SELECT COUNT(*) FROM component) AS components,
       (SELECT COUNT(*) FROM asset) AS assets,
       (SELECT COUNT(*) FROM cleanup_policy) AS policies,
       (SELECT COUNT(*) FROM repository_cleanup_policy) AS targets,
       (SELECT COUNT(*) FROM cleanup_run) AS runs,
       (SELECT COUNT(*) FROM cleanup_run_repository) AS run_repositories,
       (SELECT COUNT(*) FROM cleanup_run_item) AS run_items;
