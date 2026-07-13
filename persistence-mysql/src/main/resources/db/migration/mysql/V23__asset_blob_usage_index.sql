ALTER TABLE asset_blob
  ADD INDEX idx_asset_blob_usage (blob_store_id, deleted_at, size);
