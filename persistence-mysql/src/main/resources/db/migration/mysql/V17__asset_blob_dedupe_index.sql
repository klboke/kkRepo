ALTER TABLE asset_blob
  ADD INDEX idx_asset_blob_reusable (blob_store_id, sha256, size, deleted_at);
