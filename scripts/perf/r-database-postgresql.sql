-- R / CRAN million-row query-plan gate for an already migrated kkRepo PostgreSQL database.
-- Stop kkRepo workers before running this destructive, performance-only fixture.
-- The live compatibility setup must have created r-hosted and r-group first.

\set ON_ERROR_STOP on
SELECT id AS hosted_id FROM repository WHERE name = 'r-hosted' \gset
SELECT id AS group_id FROM repository WHERE name = 'r-group' \gset

DELETE FROM r_group_snapshot_stage WHERE group_repository_id = :group_id;
DELETE FROM r_snapshot WHERE repository_id IN (:hosted_id, :group_id);
DELETE FROM r_suite_state WHERE repository_id IN (:hosted_id, :group_id);
DELETE FROM r_package_tombstone WHERE repository_id = :hosted_id;
DELETE FROM r_package_record WHERE repository_id = :hosted_id;
DELETE FROM r_publish_lease WHERE lease_key LIKE 'r:perf:%';

INSERT INTO r_package_record
  (repository_id, coordinate_hash, distribution_name, component_name, architecture,
   package_name, package_version, version_order_key, package_architecture, filename,
   asset_path, asset_path_hash, control_fields, package_identity, data_sha256, sha256,
   size_bytes, source_kind, revision, indexed_at, created_at, updated_at)
SELECT :hosted_id,
       decode(lpad(to_hex(n + 1), 64, '0'), 'hex'),
       'src/contrib', 'source', 'source', 'pkg' || lpad(n::text, 7, '0'), '1.0.0',
       decode('01' || lpad(to_hex(n), 14, '0'), 'hex'), 'source',
       'pkg' || lpad(n::text, 7, '0') || '_1.0.0.tar.gz',
       'src/contrib/pkg' || lpad(n::text, 7, '0') || '_1.0.0.tar.gz',
       decode(lpad(to_hex(n + 1), 64, '0'), 'hex'),
       '{"License":"MIT"}'::jsonb, lpad(to_hex(n + 1), 64, '0'),
       lpad(to_hex(n + 1), 64, '0'), lpad(to_hex(n + 1), 64, '0'),
       4096 + n % 4096, 'HOSTED', n + 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM generate_series(0, 999899) AS n;

INSERT INTO r_package_record
  (repository_id, coordinate_hash, distribution_name, component_name, architecture,
   package_name, package_version, version_order_key, package_architecture, filename,
   asset_path, asset_path_hash, control_fields, package_identity, data_sha256, sha256,
   size_bytes, source_kind, revision, indexed_at, created_at, updated_at)
SELECT :hosted_id,
       decode(md5('hot-coordinate:' || n::text) || md5('hot-coordinate-2:' || n::text), 'hex'),
       'src/contrib', 'source', 'source', 'hotpkg', '1.0.' || n::text,
       decode('02' || lpad(to_hex(n), 14, '0'), 'hex'), 'source',
       'hotpkg_1.0.' || n::text || '.tar.gz',
       'src/contrib/hotpkg_1.0.' || n::text || '.tar.gz',
       decode(md5('hot-path:' || n::text) || md5('hot-path-2:' || n::text), 'hex'),
       '{"License":"MIT"}'::jsonb, md5('hot-identity:' || n::text),
       md5('hot-data:' || n::text) || md5('hot-data-2:' || n::text),
       md5('hot-blob:' || n::text) || md5('hot-blob-2:' || n::text),
       8192, 'HOSTED', 1000001 + n, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM generate_series(0, 99) AS n;

INSERT INTO r_package_relation
  (repository_id, package_id, relation_kind, token_value, token_hash, expression_value)
SELECT repository_id, id, 'IMPORTS', 'dep' || lpad((id % 1000)::text, 4, '0'),
       decode(md5('dep' || lpad((id % 1000)::text, 4, '0'))
         || md5('token:' || lpad((id % 1000)::text, 4, '0')), 'hex'),
       'dep' || lpad((id % 1000)::text, 4, '0') || ' (>= 1.0.0)'
FROM r_package_record
WHERE repository_id = :hosted_id AND package_name LIKE 'pkg%'
ORDER BY id LIMIT 100000;

INSERT INTO r_suite_state
  (repository_id, distribution_name, desired_revision, desired_at, pending_since,
   published_revision, codec_revision, updated_at)
SELECT :hosted_id, 'perf/worker/' || lpad(n::text, 5, '0'),
       CASE WHEN n < 100 THEN 2 ELSE 1 END,
       TIMESTAMPTZ '2026-01-01 00:00:00+00' + n * INTERVAL '1 second',
       CASE WHEN n < 100 THEN TIMESTAMPTZ '2026-01-01 00:00:00+00' END,
       1, 1, CURRENT_TIMESTAMP
FROM generate_series(0, 9999) AS n;

INSERT INTO r_suite_state
  (repository_id, distribution_name, desired_revision, desired_at, published_revision,
   codec_revision, last_published_at, updated_at)
VALUES
  (:hosted_id, 'perf/snapshots', 100001, CURRENT_TIMESTAMP, 100001, 1,
   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  (:group_id, 'perf/group', 1, CURRENT_TIMESTAMP, 1, 1,
   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO r_snapshot
  (repository_id, distribution_name, revision, codec_revision, manifest_json,
   index_sha256, created_at, published_at)
SELECT :hosted_id, 'perf/snapshots', n + 1, 1,
       jsonb_build_object('path', '.r/perf/' || (n + 1)::text || '/PACKAGES.gz'),
       md5('snapshot:' || n::text) || md5('snapshot-2:' || n::text),
       CURRENT_TIMESTAMP + (n - 100001) * INTERVAL '1 second',
       CURRENT_TIMESTAMP + (n - 100001) * INTERVAL '1 second'
FROM generate_series(0, 100000) AS n;

INSERT INTO r_group_snapshot_stage
  (group_repository_id, distribution_name, snapshot_revision, binding_token, created_at)
VALUES (:group_id, 'perf/group', 1, 1, CURRENT_TIMESTAMP);
INSERT INTO r_snapshot
  (repository_id, distribution_name, revision, codec_revision, manifest_json,
   index_sha256, group_binding_token, created_at, published_at)
VALUES (:group_id, 'perf/group', 1, 1,
        '{"path":".r/perf/group/PACKAGES.gz"}'::jsonb,
        md5('group-snapshot') || md5('group-snapshot-2'), 1,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO r_group_binding
  (group_repository_id, distribution_name, snapshot_revision, binding_token,
   path_value, path_hash, member_repository_id, member_snapshot_revision, member_path,
   package_identity, sha256, size_bytes, created_at)
SELECT :group_id, 'perf/group', 1, 1,
       'src/contrib/pkg' || lpad(n::text, 7, '0') || '_1.0.0.tar.gz',
       decode(md5('group-path:' || n::text) || md5('group-path-2:' || n::text), 'hex'),
       :hosted_id, 100001,
       'src/contrib/pkg' || lpad(n::text, 7, '0') || '_1.0.0.tar.gz',
       md5('group-identity:' || n::text),
       md5('group-blob:' || n::text) || md5('group-blob-2:' || n::text),
       4096 + n % 4096, CURRENT_TIMESTAMP
FROM generate_series(0, 99999) AS n;

INSERT INTO r_package_tombstone
  (repository_id, coordinate_hash, distribution_name, component_name, architecture,
   package_name, package_version, asset_path, reason, revision, deleted_at)
SELECT :hosted_id,
       decode(md5('tombstone:' || n::text) || md5('tombstone-2:' || n::text), 'hex'),
       'perf/tombstone', 'source', 'source', 'removed' || lpad(n::text, 7, '0'),
       '1.0.0', 'src/contrib/removed' || lpad(n::text, 7, '0') || '_1.0.0.tar.gz',
       'performance fixture', 2000000 + n,
       CURRENT_TIMESTAMP + (n - 100000) * INTERVAL '1 second'
FROM generate_series(0, 99999) AS n;

INSERT INTO r_publish_lease
  (lease_key, owner, fencing_token, attempt_count, expires_at, updated_at)
SELECT 'r:perf:' || lpad(n::text, 7, '0'), 'perf', 1, 1,
       CASE WHEN n < 100 THEN CURRENT_TIMESTAMP - INTERVAL '1 day'
            ELSE CURRENT_TIMESTAMP + INTERVAL '1 day' END,
       CURRENT_TIMESTAMP
FROM generate_series(0, 99999) AS n;

VACUUM (ANALYZE) r_package_record;
VACUUM (ANALYZE) r_package_relation;
VACUUM (ANALYZE) r_suite_state;
VACUUM (ANALYZE) r_snapshot;
VACUUM (ANALYZE) r_group_snapshot_stage;
VACUUM (ANALYZE) r_group_binding;
VACUUM (ANALYZE) r_package_tombstone;
VACUUM (ANALYZE) r_publish_lease;

\echo 'exact coordinate (1 of 1,000,000)'
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT * FROM r_package_record
WHERE repository_id = :hosted_id
  AND coordinate_hash = decode(lpad(to_hex(900001), 64, '0'), 'hex');

\echo 'exact path (1 of 1,000,000)'
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT * FROM r_package_record
WHERE repository_id = :hosted_id
  AND asset_path_hash = decode(lpad(to_hex(900001), 64, '0'), 'hex')
  AND asset_path = 'src/contrib/pkg0900000_1.0.0.tar.gz';

\echo 'late package keyset page (2,048 of 1,000,000)'
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT * FROM r_package_record
WHERE repository_id = :hosted_id AND distribution_name = 'src/contrib'
  AND component_name = 'source' AND architecture = 'source'
  AND (package_name, id) > ('pkg0900000', 0)
ORDER BY package_name, id LIMIT 2048;

\echo 'latest version (1 of 100)'
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT * FROM r_package_record
WHERE repository_id = :hosted_id AND distribution_name = 'src/contrib'
  AND package_name = 'hotpkg'
ORDER BY version_order_key DESC, id DESC LIMIT 1;

\echo 'relation lookup (100 of 100,000)'
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT package_row.* FROM r_package_relation relation_row
JOIN r_package_record package_row ON package_row.id = relation_row.package_id
WHERE relation_row.repository_id = :hosted_id
  AND package_row.repository_id = :hosted_id
  AND relation_row.relation_kind = 'IMPORTS'
  AND relation_row.token_hash = decode(md5('dep0001') || md5('token:0001'), 'hex')
  AND relation_row.token_value = 'dep0001' AND package_row.id > 0
ORDER BY package_row.id LIMIT 256;

\echo 'pending publication worker (100 of 10,000)'
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT suite.* FROM r_suite_state suite
JOIN repository repository_row ON repository_row.id = suite.repository_id
WHERE suite.publish_pending = TRUE AND suite.desired_at <= CURRENT_TIMESTAMP
  AND (suite.last_error_at IS NULL OR suite.last_error_at <= CURRENT_TIMESTAMP)
  AND repository_row.online = TRUE AND repository_row.format = 'r'
  AND repository_row.type IN ('hosted', 'proxy', 'group')
ORDER BY suite.desired_at, suite.repository_id, suite.distribution_name LIMIT 256;

\echo 'snapshot cleanup candidates (256 of 100,001)'
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT candidate.* FROM r_snapshot candidate
WHERE candidate.published_at IS NOT NULL AND candidate.created_at < CURRENT_TIMESTAMP
  AND candidate.revision <> (
    SELECT suite.published_revision FROM r_suite_state suite
    WHERE suite.repository_id = candidate.repository_id
      AND suite.distribution_name = candidate.distribution_name)
  AND candidate.revision < (
    SELECT newer.revision FROM r_snapshot newer
    WHERE newer.repository_id = candidate.repository_id
      AND newer.distribution_name = candidate.distribution_name
      AND newer.published_at IS NOT NULL
    ORDER BY newer.revision DESC LIMIT 1 OFFSET 2)
ORDER BY candidate.created_at, candidate.repository_id, candidate.distribution_name,
  candidate.revision LIMIT 256;

\echo 'exact group binding (1 of 100,000)'
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT * FROM r_group_binding
WHERE group_repository_id = :group_id AND distribution_name = 'perf/group'
  AND snapshot_revision = 1 AND binding_token = 1
  AND path_hash = decode(md5('group-path:90000') || md5('group-path-2:90000'), 'hex')
  AND path_value = 'src/contrib/pkg0090000_1.0.0.tar.gz';

\echo 'late group binding page (2,048 of 100,000)'
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT * FROM r_group_binding
WHERE group_repository_id = :group_id AND distribution_name = 'perf/group'
  AND snapshot_revision = 1 AND binding_token = 1 AND id > 90000
ORDER BY id LIMIT 2048;

\echo 'expired lease page (100 of 100,000)'
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT * FROM r_publish_lease
WHERE expires_at < CURRENT_TIMESTAMP
ORDER BY expires_at LIMIT 256;
