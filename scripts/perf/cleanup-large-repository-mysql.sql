-- Session variables are supplied by run-cleanup-large-repository.sh.
SET SESSION foreign_key_checks = 0;
SET SESSION unique_checks = 0;
SET @number_count = GREATEST(
  @component_count,
  @unbound_asset_count,
  @repository_count,
  @policy_count,
  @history_run_count,
  @history_items_per_run,
  @detail_repository_count,
  @detail_items_per_repository);

CREATE TABLE cleanup_perf_number (
  n INT NOT NULL,
  PRIMARY KEY (n)
) ENGINE=InnoDB;

INSERT INTO cleanup_perf_number (n)
SELECT value.n + 1
FROM (
  SELECT units.n
       + tens.n * 10
       + hundreds.n * 100
       + thousands.n * 1000
       + ten_thousands.n * 10000
       + hundred_thousands.n * 100000 AS n
  FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
        UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
        UNION ALL SELECT 8 UNION ALL SELECT 9) units
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
              UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
              UNION ALL SELECT 8 UNION ALL SELECT 9) tens
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
              UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
              UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
              UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
              UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
              UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
              UNION ALL SELECT 8 UNION ALL SELECT 9) ten_thousands
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
              UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
              UNION ALL SELECT 8 UNION ALL SELECT 9) hundred_thousands
) value
WHERE value.n < @number_count;

INSERT INTO blob_store (id, name, type, attributes_json)
VALUES (1, 'cleanup-perf-store', 'FILE', JSON_OBJECT());

INSERT INTO repository
  (id, name, format, type, recipe_name, online, blob_store_id,
   strict_content_type_validation, attributes_json)
SELECT n,
       CONCAT('cleanup-perf-maven-', LPAD(n, 3, '0')),
       'maven2',
       'hosted',
       'maven2-hosted',
       TRUE,
       1,
       TRUE,
       JSON_OBJECT()
FROM cleanup_perf_number
WHERE n <= @repository_count;

INSERT INTO component
  (id, repository_id, format, namespace, name, version, kind, coordinate_hash,
   attributes_json, last_updated_at, created_at, updated_at)
SELECT n,
       1,
       'maven2',
       CONCAT('com.example.', LPAD(FLOOR((n - 1) / 10000), 3, '0')),
       CONCAT('artifact-', LPAD(FLOOR((n - 1) / 10), 7, '0')),
       CONCAT('1.', MOD(n - 1, 10), '.0'),
       'release',
       UNHEX(LPAD(HEX(n), 64, '0')),
       JSON_OBJECT(),
       TIMESTAMP('2025-01-01 00:00:00') + INTERVAL MOD(n, 365) DAY,
       TIMESTAMP('2025-01-01 00:00:00'),
       TIMESTAMP('2025-01-01 00:00:00')
FROM cleanup_perf_number
WHERE n <= @component_count;

INSERT INTO asset
  (id, repository_id, component_id, asset_blob_id, format, path, path_hash, name,
   kind, content_type, size, last_downloaded_at, last_updated_at, attributes_json,
   created_at, updated_at)
SELECT n,
       1,
       n,
       NULL,
       'maven2',
       CONCAT('com/example/artifact/', LPAD(n, 7, '0'), '/artifact.jar'),
       UNHEX(LPAD(HEX(n), 64, '0')),
       'artifact.jar',
       'asset',
       'application/java-archive',
       1024,
       NULL,
       TIMESTAMP('2025-01-01 00:00:00') + INTERVAL MOD(n, 365) DAY,
       JSON_OBJECT(),
       TIMESTAMP('2025-01-01 00:00:00'),
       TIMESTAMP('2025-01-01 00:00:00')
FROM cleanup_perf_number
WHERE n <= @component_count;

INSERT INTO asset
  (id, repository_id, component_id, asset_blob_id, format, path, path_hash, name,
   kind, content_type, size, last_downloaded_at, last_updated_at, attributes_json,
   created_at, updated_at)
SELECT @component_count + n,
       MOD(n - 1, @repository_count) + 1,
       NULL,
       NULL,
       'maven2',
       CONCAT('unbound/', LPAD(n, 7, '0'), '/metadata.xml'),
       UNHEX(LPAD(HEX(@component_count + n), 64, '0')),
       'metadata.xml',
       'metadata',
       'application/xml',
       512,
       NULL,
       TIMESTAMP('2025-01-01 00:00:00') + INTERVAL MOD(n, 365) DAY,
       JSON_OBJECT(),
       TIMESTAMP('2025-01-01 00:00:00'),
       TIMESTAMP('2025-01-01 00:00:00')
FROM cleanup_perf_number
WHERE n <= @unbound_asset_count;

INSERT INTO cleanup_policy
  (id, name, format, mode, notes, criteria_json, revision, state,
   scan_limit_per_repository, delete_limit_per_repository, created_at, updated_at)
SELECT n,
       CONCAT('cleanup-perf-policy-', LPAD(n, 3, '0')),
       'maven2',
       'DELETE',
       NULL,
       JSON_OBJECT('publishedOlderThanDays', 30, 'lastDownloadedOlderThanDays', 30),
       1,
       CASE WHEN MOD(n, 2) = 0 THEN 'ACTIVE' ELSE 'PAUSED' END,
       10000,
       100,
       TIMESTAMP('2026-01-01 00:00:00'),
       TIMESTAMP('2026-01-01 00:00:00')
FROM cleanup_perf_number
WHERE n <= @policy_count;

INSERT INTO repository_cleanup_policy
  (repository_id, cleanup_policy_id, created_at)
SELECT MOD(n - 1, @repository_count) + 1,
       n,
       TIMESTAMP('2026-01-01 00:00:00')
FROM cleanup_perf_number
WHERE n <= @policy_count;

INSERT IGNORE INTO repository_cleanup_policy
  (repository_id, cleanup_policy_id, created_at)
SELECT n, 1, TIMESTAMP('2026-01-01 00:00:00')
FROM cleanup_perf_number
WHERE n <= @repository_count;

INSERT INTO cleanup_policy_schedule
  (policy_id, cron_expression, time_zone, enabled, created_at, updated_at)
SELECT n,
       CONCAT('0 ', MOD(n, 60), ' ', MOD(n, 24), ' * * ?'),
       'Asia/Shanghai',
       MOD(n, 2) = 0,
       TIMESTAMP('2026-01-01 00:00:00'),
       TIMESTAMP('2026-01-01 00:00:00')
FROM cleanup_perf_number
WHERE n <= @policy_count;

INSERT INTO cleanup_run
  (id, policy_id, policy_revision, mode, trigger_kind, state, cancel_requested,
   requested_by, scheduled_for, scan_limit_per_repository, delete_limit_per_repository,
   criteria_snapshot_json, repository_snapshot_json, scanned_subjects, matched_subjects,
   would_delete_subjects, deleted_subjects, failed_subjects, truncated_repositories,
   error_summary, started_at, completed_at, cancelled_at, created_at, updated_at)
SELECT n,
       MOD(n - 1, @policy_count) + 1,
       1,
       'TRY_RUN',
       'MANUAL',
       'SUCCEEDED',
       FALSE,
       'cleanup-perf',
       NULL,
       10000,
       100,
       JSON_OBJECT('publishedOlderThanDays', 30),
       JSON_ARRAY(),
       @history_items_per_run,
       @history_items_per_run,
       @history_items_per_run,
       0,
       0,
       0,
       NULL,
       TIMESTAMP('2025-01-01 00:00:00') + INTERVAL n SECOND,
       TIMESTAMP('2025-01-01 00:01:00') + INTERVAL n SECOND,
       NULL,
       TIMESTAMP('2025-01-01 00:00:00') + INTERVAL n SECOND,
       TIMESTAMP('2025-01-01 00:01:00') + INTERVAL n SECOND
FROM cleanup_perf_number
WHERE n <= @history_run_count;

SET @detail_run_id = @history_run_count + 1;
INSERT INTO cleanup_run
  (id, policy_id, policy_revision, mode, trigger_kind, state, cancel_requested,
   requested_by, scheduled_for, scan_limit_per_repository, delete_limit_per_repository,
   criteria_snapshot_json, repository_snapshot_json, scanned_subjects, matched_subjects,
   would_delete_subjects, deleted_subjects, failed_subjects, truncated_repositories,
   error_summary, started_at, completed_at, cancelled_at, created_at, updated_at)
VALUES
  (@detail_run_id, 1, 1, 'TRY_RUN', 'MANUAL', 'SUCCEEDED', FALSE, 'cleanup-perf', NULL,
   10000, 100, JSON_OBJECT('publishedOlderThanDays', 30), JSON_ARRAY(),
   @detail_repository_count * @detail_items_per_repository,
   @detail_repository_count * @detail_items_per_repository,
   @detail_repository_count * @detail_items_per_repository,
   0, 0, 0, NULL,
   TIMESTAMP('2026-01-01 00:00:00'), TIMESTAMP('2026-01-01 00:01:00'), NULL,
   TIMESTAMP('2026-01-01 00:00:00'), TIMESTAMP('2026-01-01 00:01:00'));

INSERT INTO cleanup_run_repository
  (id, run_id, repository_id, repository_name, format, repository_type, state,
   attempt_count, max_attempts, next_attempt_at, fencing_token, scanned_subjects,
   matched_subjects, would_delete_subjects, deleted_subjects, failed_subjects,
   truncated, started_at, completed_at, created_at, updated_at)
SELECT n,
       n,
       MOD(n - 1, @repository_count) + 1,
       CONCAT('cleanup-perf-maven-', LPAD(MOD(n - 1, @repository_count) + 1, 3, '0')),
       'maven2',
       'hosted',
       'SUCCEEDED',
       1,
       3,
       TIMESTAMP('2025-01-01 00:00:00'),
       1,
       @history_items_per_run,
       @history_items_per_run,
       @history_items_per_run,
       0,
       0,
       FALSE,
       TIMESTAMP('2025-01-01 00:00:00') + INTERVAL n SECOND,
       TIMESTAMP('2025-01-01 00:01:00') + INTERVAL n SECOND,
       TIMESTAMP('2025-01-01 00:00:00') + INTERVAL n SECOND,
       TIMESTAMP('2025-01-01 00:01:00') + INTERVAL n SECOND
FROM cleanup_perf_number
WHERE n <= @history_run_count;

INSERT INTO cleanup_run_repository
  (id, run_id, repository_id, repository_name, format, repository_type, state,
   attempt_count, max_attempts, next_attempt_at, fencing_token, scanned_subjects,
   matched_subjects, would_delete_subjects, deleted_subjects, failed_subjects,
   truncated, started_at, completed_at, created_at, updated_at)
SELECT @history_run_count + n,
       @detail_run_id,
       n,
       CONCAT('cleanup-perf-maven-', LPAD(n, 3, '0')),
       'maven2',
       'hosted',
       'SUCCEEDED',
       1,
       3,
       TIMESTAMP('2026-01-01 00:00:00'),
       1,
       @detail_items_per_repository,
       @detail_items_per_repository,
       @detail_items_per_repository,
       0,
       0,
       FALSE,
       TIMESTAMP('2026-01-01 00:00:00'),
       TIMESTAMP('2026-01-01 00:01:00'),
       TIMESTAMP('2026-01-01 00:00:00'),
       TIMESTAMP('2026-01-01 00:01:00')
FROM cleanup_perf_number
WHERE n <= @detail_repository_count;

INSERT INTO cleanup_run_item
  (id, run_repository_id, subject_kind, subject_key, subject_key_hash, family_key,
   display_name, version, delete_path, asset_count, estimated_bytes,
   expected_usage_revision, evaluated_at, decision, reason_json, created_at, updated_at)
SELECT (repository.id - 1) * @history_items_per_run + item.n,
       repository.id,
       'COMPONENT',
       CONCAT('history:', repository.id, ':', item.n),
       UNHEX(SHA2(CONCAT('history:', repository.id, ':', item.n), 256)),
       CONCAT('history-family-', repository.id),
       CONCAT('history-artifact-', item.n),
       CONCAT('1.', MOD(item.n, 10), '.0'),
       CONCAT('history/', repository.id, '/', item.n),
       1,
       1024,
       0,
       repository.completed_at,
       'WOULD_DELETE',
       JSON_OBJECT(),
       repository.completed_at,
       repository.completed_at
FROM cleanup_run_repository repository
JOIN cleanup_perf_number item ON item.n <= @history_items_per_run
WHERE repository.id <= @history_run_count;

SET @detail_item_offset = @history_run_count * @history_items_per_run;
INSERT INTO cleanup_run_item
  (id, run_repository_id, subject_kind, subject_key, subject_key_hash, family_key,
   display_name, version, delete_path, asset_count, estimated_bytes,
   expected_usage_revision, evaluated_at, decision, reason_json, created_at, updated_at)
SELECT @detail_item_offset
         + (repository.id - @history_run_count - 1) * @detail_items_per_repository
         + item.n,
       repository.id,
       'COMPONENT',
       CONCAT('detail:', repository.id, ':', item.n),
       UNHEX(SHA2(CONCAT('detail:', repository.id, ':', item.n), 256)),
       CONCAT('detail-family-', repository.id),
       CONCAT('detail-artifact-', item.n),
       CONCAT('1.', MOD(item.n, 10), '.0'),
       CONCAT('detail/', repository.id, '/', item.n),
       1,
       1024,
       0,
       repository.completed_at,
       'WOULD_DELETE',
       JSON_OBJECT(),
       repository.completed_at,
       repository.completed_at
FROM cleanup_run_repository repository
JOIN cleanup_perf_number item ON item.n <= @detail_items_per_repository
WHERE repository.run_id = @detail_run_id;

SET SESSION foreign_key_checks = 1;
SET SESSION unique_checks = 1;

ANALYZE TABLE component, asset, cleanup_policy, repository_cleanup_policy,
  cleanup_policy_schedule, cleanup_run, cleanup_run_repository, cleanup_run_item;

SELECT 'seed-counts' AS section,
       (SELECT COUNT(*) FROM component) AS components,
       (SELECT COUNT(*) FROM asset) AS assets,
       (SELECT COUNT(*) FROM cleanup_policy) AS policies,
       (SELECT COUNT(*) FROM repository_cleanup_policy) AS targets,
       (SELECT COUNT(*) FROM cleanup_run) AS runs,
       (SELECT COUNT(*) FROM cleanup_run_repository) AS run_repositories,
       (SELECT COUNT(*) FROM cleanup_run_item) AS run_items;
