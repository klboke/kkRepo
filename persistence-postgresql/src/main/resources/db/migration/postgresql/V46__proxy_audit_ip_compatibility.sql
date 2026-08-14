-- Proxy cache writes now store the triggering client IP instead of the upstream URL. Keep the
-- audit columns wide enough for rolling upgrades and imported legacy values written by old nodes.
ALTER TABLE asset_blob
  ALTER COLUMN created_by_ip TYPE VARCHAR(1024);

ALTER TABLE repository_data_migration_asset
  ALTER COLUMN source_created_by_ip TYPE VARCHAR(1024);
