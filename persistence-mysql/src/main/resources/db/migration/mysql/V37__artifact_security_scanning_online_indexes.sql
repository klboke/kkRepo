-- Explicit online DDL keeps uploads and download timestamp updates writable during the build.
ALTER TABLE asset
  ADD INDEX idx_asset_repository_id (repository_id, id),
  ALGORITHM=INPLACE,
  LOCK=NONE;

ALTER TABLE docker_manifest_reference
  ADD INDEX idx_docker_reference_policy_lookup (repository_id, digest_hash, manifest_id),
  ALGORITHM=INPLACE,
  LOCK=NONE;
