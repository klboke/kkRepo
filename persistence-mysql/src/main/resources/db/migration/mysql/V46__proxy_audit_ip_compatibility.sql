-- Proxy cache writes now store the triggering client IP instead of the upstream URL. Keep the
-- audit columns wide enough for rolling upgrades and imported legacy values written by old nodes.
ALTER TABLE asset_blob
  MODIFY created_by_ip VARCHAR(1024) NULL;

ALTER TABLE repository_data_migration_asset
  MODIFY source_created_by_ip VARCHAR(1024) NULL;
