-- R / CRAN million-row query-plan gate for an already migrated kkRepo MySQL database.
-- Stop kkRepo workers before running this destructive, performance-only fixture.
-- The live compatibility setup must have created r-hosted and r-group first.

SET @hosted_id = (SELECT id FROM repository WHERE name = 'r-hosted');
SET @group_id = (SELECT id FROM repository WHERE name = 'r-group');

DELETE FROM r_group_snapshot_stage WHERE group_repository_id = @group_id;
DELETE FROM r_snapshot WHERE repository_id IN (@hosted_id, @group_id);
DELETE FROM r_suite_state WHERE repository_id IN (@hosted_id, @group_id);
DELETE FROM r_package_tombstone WHERE repository_id = @hosted_id;
DELETE FROM r_package_record WHERE repository_id = @hosted_id;
DELETE FROM r_publish_lease WHERE lease_key LIKE 'r:perf:%';

DROP TEMPORARY TABLE IF EXISTS r_perf_number;
CREATE TEMPORARY TABLE r_perf_number (n BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO r_perf_number (n)
SELECT ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000
       + ten_thousands.n * 10000 + hundred_thousands.n * 100000
FROM
  (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
   UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
CROSS JOIN
  (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
   UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
CROSS JOIN
  (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
   UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
CROSS JOIN
  (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
   UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
CROSS JOIN
  (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
   UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ten_thousands
CROSS JOIN
  (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
   UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundred_thousands;

-- Keep the synthetic hashes uniformly sized but sequential so fixture creation exercises the
-- same indexes without turning random B-tree insertion into the dominant benchmark. Commit in
-- bounded batches so the gate does not need a million-row undo transaction.
SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;
DROP PROCEDURE IF EXISTS load_r_perf_packages;
DELIMITER //
CREATE PROCEDURE load_r_perf_packages()
BEGIN
  DECLARE batch_start BIGINT DEFAULT 0;
  WHILE batch_start < 999900 DO
    INSERT INTO r_package_record
      (repository_id, coordinate_hash, distribution_name, component_name, architecture,
       package_name, package_version, version_order_key, package_architecture, filename,
       asset_path, asset_path_hash, control_fields, package_identity, data_sha256, sha256,
       size_bytes, source_kind, revision, indexed_at, created_at, updated_at)
    SELECT @hosted_id,
           UNHEX(LPAD(HEX(n + 1), 64, '0')),
           'src/contrib', 'source', 'source', CONCAT('pkg', LPAD(n, 7, '0')), '1.0.0',
           UNHEX(CONCAT('01', LPAD(HEX(n), 14, '0'))), 'source',
           CONCAT('pkg', LPAD(n, 7, '0'), '_1.0.0.tar.gz'),
           CONCAT('src/contrib/pkg', LPAD(n, 7, '0'), '_1.0.0.tar.gz'),
           UNHEX(LPAD(HEX(n + 1), 64, '0')), JSON_OBJECT('License', 'MIT'),
           LPAD(HEX(n + 1), 64, '0'), LPAD(HEX(n + 1), 64, '0'),
           LPAD(HEX(n + 1), 64, '0'), 4096 + MOD(n, 4096), 'HOSTED', n + 1,
           NOW(3), NOW(3), NOW(3)
    FROM r_perf_number
    WHERE n >= batch_start AND n < LEAST(batch_start + 50000, 999900);
    SET batch_start = batch_start + 50000;
  END WHILE;
END//
DELIMITER ;
CALL load_r_perf_packages();
DROP PROCEDURE load_r_perf_packages;
SET UNIQUE_CHECKS = 1;
SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO r_package_record
  (repository_id, coordinate_hash, distribution_name, component_name, architecture,
   package_name, package_version, version_order_key, package_architecture, filename,
   asset_path, asset_path_hash, control_fields, package_identity, data_sha256, sha256,
   size_bytes, source_kind, revision, indexed_at, created_at, updated_at)
SELECT @hosted_id,
       UNHEX(SHA2(CONCAT('hot-coordinate:', n), 256)),
       'src/contrib', 'source', 'source', 'hotpkg', CONCAT('1.0.', n),
       UNHEX(CONCAT('02', LPAD(HEX(n), 14, '0'))), 'source',
       CONCAT('hotpkg_1.0.', n, '.tar.gz'),
       CONCAT('src/contrib/hotpkg_1.0.', n, '.tar.gz'),
       UNHEX(SHA2(CONCAT('hot-path:', n), 256)), JSON_OBJECT('License', 'MIT'),
       MD5(CONCAT('hot-identity:', n)), SHA2(CONCAT('hot-data:', n), 256),
       SHA2(CONCAT('hot-blob:', n), 256), 8192, 'HOSTED', 1000001 + n,
       NOW(3), NOW(3), NOW(3)
FROM r_perf_number
WHERE n < 100;

INSERT INTO r_package_relation
  (repository_id, package_id, relation_kind, token_value, token_hash, expression_value)
SELECT repository_id, id, 'IMPORTS', CONCAT('dep', LPAD(MOD(id, 1000), 4, '0')),
       UNHEX(SHA2(CONCAT('dep', LPAD(MOD(id, 1000), 4, '0')), 256)),
       CONCAT('dep', LPAD(MOD(id, 1000), 4, '0'), ' (>= 1.0.0)')
FROM r_package_record
WHERE repository_id = @hosted_id AND package_name LIKE 'pkg%'
ORDER BY id
LIMIT 100000;

INSERT INTO r_suite_state
  (repository_id, distribution_name, desired_revision, desired_at, pending_since,
   published_revision, codec_revision, updated_at)
SELECT @hosted_id, CONCAT('perf/worker/', LPAD(n, 5, '0')),
       IF(n < 100, 2, 1), TIMESTAMPADD(SECOND, n, '2026-01-01 00:00:00.000'),
       IF(n < 100, '2026-01-01 00:00:00.000', NULL), 1, 1, NOW(3)
FROM r_perf_number WHERE n < 10000;

INSERT INTO r_suite_state
  (repository_id, distribution_name, desired_revision, desired_at, published_revision,
   codec_revision, last_published_at, updated_at)
VALUES
  (@hosted_id, 'perf/snapshots', 100001, NOW(3), 100001, 1, NOW(3), NOW(3)),
  (@group_id, 'perf/group', 1, NOW(3), 1, 1, NOW(3), NOW(3));

INSERT INTO r_snapshot
  (repository_id, distribution_name, revision, codec_revision, manifest_json,
   index_sha256, created_at, published_at)
SELECT @hosted_id, 'perf/snapshots', n + 1, 1,
       JSON_OBJECT('path', CONCAT('.r/perf/', n + 1, '/PACKAGES.gz')),
       SHA2(CONCAT('snapshot:', n), 256),
       TIMESTAMPADD(SECOND, n - 100001, NOW(3)),
       TIMESTAMPADD(SECOND, n - 100001, NOW(3))
FROM r_perf_number WHERE n < 100001;

INSERT INTO r_group_snapshot_stage
  (group_repository_id, distribution_name, snapshot_revision, binding_token, created_at)
VALUES (@group_id, 'perf/group', 1, 1, NOW(3));
INSERT INTO r_snapshot
  (repository_id, distribution_name, revision, codec_revision, manifest_json,
   index_sha256, group_binding_token, created_at, published_at)
VALUES (@group_id, 'perf/group', 1, 1, JSON_OBJECT('path', '.r/perf/group/PACKAGES.gz'),
        SHA2('group-snapshot', 256), 1, NOW(3), NOW(3));
INSERT INTO r_group_binding
  (group_repository_id, distribution_name, snapshot_revision, binding_token,
   path_value, path_hash, member_repository_id, member_snapshot_revision, member_path,
   package_identity, sha256, size_bytes, created_at)
SELECT @group_id, 'perf/group', 1, 1,
       CONCAT('src/contrib/pkg', LPAD(n, 7, '0'), '_1.0.0.tar.gz'),
       UNHEX(SHA2(CONCAT('group-path:', n), 256)), @hosted_id, 100001,
       CONCAT('src/contrib/pkg', LPAD(n, 7, '0'), '_1.0.0.tar.gz'),
       MD5(CONCAT('group-identity:', n)), SHA2(CONCAT('group-blob:', n), 256),
       4096 + MOD(n, 4096), NOW(3)
FROM r_perf_number WHERE n < 100000;

INSERT INTO r_package_tombstone
  (repository_id, coordinate_hash, distribution_name, component_name, architecture,
   package_name, package_version, asset_path, reason, revision, deleted_at)
SELECT @hosted_id, UNHEX(SHA2(CONCAT('tombstone:', n), 256)), 'perf/tombstone',
       'source', 'source', CONCAT('removed', LPAD(n, 7, '0')), '1.0.0',
       CONCAT('src/contrib/removed', LPAD(n, 7, '0'), '_1.0.0.tar.gz'),
       'performance fixture', 2000000 + n, TIMESTAMPADD(SECOND, n - 100000, NOW(3))
FROM r_perf_number WHERE n < 100000;

INSERT INTO r_publish_lease
  (lease_key, owner, fencing_token, attempt_count, expires_at, updated_at)
SELECT CONCAT('r:perf:', LPAD(n, 7, '0')), 'perf', 1, 1,
       IF(n < 100, TIMESTAMPADD(DAY, -1, NOW(3)), TIMESTAMPADD(DAY, 1, NOW(3))), NOW(3)
FROM r_perf_number WHERE n < 100000;

ANALYZE TABLE r_package_record, r_package_relation, r_suite_state, r_snapshot,
  r_group_snapshot_stage, r_group_binding, r_package_tombstone, r_publish_lease;

SELECT 'exact coordinate (1 of 1,000,000)' AS r_query_plan;
EXPLAIN ANALYZE FORMAT=TREE
SELECT * FROM r_package_record
WHERE repository_id = @hosted_id
  AND coordinate_hash = UNHEX(LPAD(HEX(900001), 64, '0'));

SELECT 'exact path (1 of 1,000,000)' AS r_query_plan;
EXPLAIN ANALYZE FORMAT=TREE
SELECT * FROM r_package_record
WHERE repository_id = @hosted_id
  AND asset_path_hash = UNHEX(LPAD(HEX(900001), 64, '0'))
  AND asset_path = 'src/contrib/pkg0900000_1.0.0.tar.gz';

SELECT 'late package keyset page (2,048 of 1,000,000)' AS r_query_plan;
EXPLAIN ANALYZE FORMAT=TREE
SELECT * FROM r_package_record
WHERE repository_id = @hosted_id AND distribution_name = 'src/contrib'
  AND component_name = 'source' AND architecture = 'source'
  AND (package_name > 'pkg0900000' OR (package_name = 'pkg0900000' AND id > 0))
ORDER BY package_name, id LIMIT 2048;

SELECT 'latest version (1 of 100)' AS r_query_plan;
EXPLAIN ANALYZE FORMAT=TREE
SELECT * FROM r_package_record FORCE INDEX (idx_r_package_name)
WHERE repository_id = @hosted_id AND distribution_name = 'src/contrib'
  AND package_name = 'hotpkg'
ORDER BY version_order_key DESC, id DESC LIMIT 1;

SELECT 'relation lookup (100 of 100,000)' AS r_query_plan;
EXPLAIN ANALYZE FORMAT=TREE
SELECT package_row.* FROM r_package_relation relation_row
JOIN r_package_record package_row ON package_row.id = relation_row.package_id
WHERE relation_row.repository_id = @hosted_id
  AND package_row.repository_id = @hosted_id
  AND relation_row.relation_kind = 'IMPORTS'
  AND relation_row.token_hash = UNHEX(SHA2('dep0001', 256))
  AND relation_row.token_value = 'dep0001' AND package_row.id > 0
ORDER BY package_row.id LIMIT 256;

SELECT 'pending publication worker (100 of 10,000)' AS r_query_plan;
EXPLAIN ANALYZE FORMAT=TREE
SELECT r_suite_state.*
FROM r_suite_state FORCE INDEX (idx_r_suite_worker)
STRAIGHT_JOIN repository repository_row ON repository_row.id = r_suite_state.repository_id
WHERE r_suite_state.publish_pending = TRUE
  AND r_suite_state.desired_at <= NOW(3)
  AND (r_suite_state.last_error_at IS NULL OR r_suite_state.last_error_at <= NOW(3))
  AND repository_row.online = TRUE AND repository_row.format = 'r'
  AND repository_row.type IN ('hosted', 'proxy', 'group')
ORDER BY r_suite_state.desired_at, r_suite_state.repository_id, r_suite_state.distribution_name
LIMIT 256;

SELECT 'snapshot cleanup candidates (256 of 100,001)' AS r_query_plan;
EXPLAIN ANALYZE FORMAT=TREE
SELECT candidate.* FROM r_snapshot candidate FORCE INDEX (idx_r_snapshot_cleanup)
JOIN r_suite_state suite ON suite.repository_id = candidate.repository_id
  AND suite.distribution_name = candidate.distribution_name
WHERE candidate.published_at IS NOT NULL AND candidate.created_at < NOW(3)
  AND candidate.revision <> suite.published_revision
  AND candidate.revision < (
    SELECT newer.revision FROM r_snapshot newer FORCE INDEX (idx_r_snapshot_retention)
    WHERE newer.repository_id = candidate.repository_id
      AND newer.distribution_name = candidate.distribution_name
      AND newer.publish_complete = TRUE
    ORDER BY newer.repository_id, newer.distribution_name,
      newer.publish_complete, newer.revision DESC LIMIT 1 OFFSET 2)
ORDER BY candidate.created_at, candidate.repository_id, candidate.distribution_name,
  candidate.revision
LIMIT 256;

SELECT 'exact group binding (1 of 100,000)' AS r_query_plan;
EXPLAIN ANALYZE FORMAT=TREE
SELECT * FROM r_group_binding
WHERE group_repository_id = @group_id AND distribution_name = 'perf/group'
  AND snapshot_revision = 1 AND binding_token = 1
  AND path_hash = UNHEX(SHA2('group-path:90000', 256))
  AND path_value = 'src/contrib/pkg0090000_1.0.0.tar.gz';

SELECT 'late group binding page (2,048 of 100,000)' AS r_query_plan;
EXPLAIN ANALYZE FORMAT=TREE
SELECT * FROM r_group_binding FORCE INDEX (idx_r_group_page)
WHERE group_repository_id = @group_id AND distribution_name = 'perf/group'
  AND snapshot_revision = 1 AND binding_token = 1 AND id > 90000
ORDER BY id LIMIT 2048;

SELECT 'expired lease page (100 of 100,000)' AS r_query_plan;
EXPLAIN ANALYZE FORMAT=TREE
SELECT * FROM r_publish_lease
WHERE expires_at < NOW(3)
ORDER BY expires_at LIMIT 256;
