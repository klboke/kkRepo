-- Existing high-cardinality tables remain writable while the scanning lookup indexes are built.
-- This migration is intentionally non-transactional; see the matching .sql.conf resource.
-- NOT VALID makes V36's catalog change brief. VALIDATE takes SHARE UPDATE EXCLUSIVE rather than
-- holding ACCESS EXCLUSIVE for the rest of the large transactional migration.
ALTER TABLE asset_blob
  VALIDATE CONSTRAINT ck_asset_blob_external_reference_nonnegative;
ALTER TABLE asset_blob
  VALIDATE CONSTRAINT ck_asset_blob_external_reference_live;

DROP INDEX CONCURRENTLY IF EXISTS idx_asset_repository_id;
CREATE INDEX CONCURRENTLY idx_asset_repository_id
  ON asset(repository_id, id);

DROP INDEX CONCURRENTLY IF EXISTS idx_asset_last_updated_id;
CREATE INDEX CONCURRENTLY idx_asset_last_updated_id
  ON asset(last_updated_at, id)
  WHERE asset_blob_id IS NOT NULL;

DROP INDEX CONCURRENTLY IF EXISTS idx_docker_reference_policy_lookup;
CREATE INDEX CONCURRENTLY idx_docker_reference_policy_lookup
  ON docker_manifest_reference(repository_id, digest_hash, manifest_id);
