-- Existing high-cardinality tables remain writable while the scanning lookup indexes are built.
-- This migration is intentionally non-transactional; see the matching .sql.conf resource.
DROP INDEX CONCURRENTLY IF EXISTS idx_asset_repository_id;
CREATE INDEX CONCURRENTLY idx_asset_repository_id
  ON asset(repository_id, id);

DROP INDEX CONCURRENTLY IF EXISTS idx_docker_reference_policy_lookup;
CREATE INDEX CONCURRENTLY idx_docker_reference_policy_lookup
  ON docker_manifest_reference(repository_id, digest_hash, manifest_id);
