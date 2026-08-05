-- A failed concurrent build can leave an invalid index behind. Drop before each create so a
-- repaired Flyway migration is safe to resume while keeping writes available.
DROP INDEX CONCURRENTLY IF EXISTS idx_component_cleanup_scan;
CREATE INDEX CONCURRENTLY idx_component_cleanup_scan
  ON component (
    repository_id,
    (COALESCE(namespace, '') COLLATE "C"),
    (name COLLATE "C"),
    (COALESCE(kind, '') COLLATE "C"),
    id
  );

DROP INDEX CONCURRENTLY IF EXISTS idx_asset_cleanup_unbound;
CREATE INDEX CONCURRENTLY idx_asset_cleanup_unbound
  ON asset ((CASE WHEN component_id IS NULL THEN repository_id END), id)
  WHERE component_id IS NULL;

-- The expression has index-local distribution statistics for unbound assets. Plain
-- (repository_id, id) inherits the whole table's repository skew and can make PostgreSQL scan
-- every NULL entry through idx_asset_component after a large write burst.
ANALYZE asset;

DROP INDEX CONCURRENTLY IF EXISTS idx_docker_manifest_cleanup;
CREATE INDEX CONCURRENTLY idx_docker_manifest_cleanup
  ON docker_manifest (repository_id, asset_id)
  WHERE deleted_at IS NULL;

DROP INDEX CONCURRENTLY IF EXISTS idx_cleanup_protection_scan;
CREATE INDEX CONCURRENTLY idx_cleanup_protection_scan
  ON cleanup_protection (repository_id, subject_key_hash, expires_at, id)
  WHERE enabled = TRUE;
