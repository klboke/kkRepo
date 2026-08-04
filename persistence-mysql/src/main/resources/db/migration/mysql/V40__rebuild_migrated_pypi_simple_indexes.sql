INSERT INTO repository_index_rebuild_marker
  (repository_id, index_kind, scope_key, requested_at, attempts, last_attempted_at, last_error)
SELECT id, 'PYPI_ROOT', '', CURRENT_TIMESTAMP(3), 0, NULL, NULL
FROM repository
WHERE format = 'pypi' AND type = 'hosted'
ON DUPLICATE KEY UPDATE
  requested_at = CURRENT_TIMESTAMP(3),
  attempts = 0,
  last_attempted_at = NULL,
  last_error = NULL;

INSERT INTO repository_index_rebuild_marker
  (repository_id, index_kind, scope_key, requested_at, attempts, last_attempted_at, last_error)
SELECT DISTINCT
  a.repository_id,
  'PYPI_PROJECT',
  LOWER(REGEXP_REPLACE(
      SUBSTRING(a.path, 10, LOCATE('/', a.path, 10) - 10),
      '[-_.]+',
      '-')),
  CURRENT_TIMESTAMP(3),
  0,
  NULL,
  NULL
FROM asset a
JOIN repository r ON r.id = a.repository_id
JOIN asset_blob b ON b.id = a.asset_blob_id
WHERE r.format = 'pypi'
  AND r.type = 'hosted'
  AND a.format = 'pypi'
  AND a.path LIKE 'packages/%/%'
  AND LOCATE('/', a.path, 10) > 10
  AND LOWER(a.kind) IN ('package', 'package-signature')
  AND b.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
  requested_at = CURRENT_TIMESTAMP(3),
  attempts = 0,
  last_attempted_at = NULL,
  last_error = NULL;
