CREATE TABLE IF NOT EXISTS gitlfs_object (
  repository_id BIGINT UNSIGNED NOT NULL,
  oid CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  generation BIGINT NOT NULL,
  fencing_token BIGINT NOT NULL,
  owner_id VARCHAR(36) NULL,
  lease_until DATETIME(3) NULL,
  asset_id BIGINT UNSIGNED NULL,
  published BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (repository_id, oid),
  CONSTRAINT fk_gitlfs_object_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_gitlfs_object_asset FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE SET NULL,
  CONSTRAINT ck_gitlfs_generation CHECK (generation > 0),
  INDEX idx_gitlfs_object_owner (owner_id, fencing_token),
  INDEX idx_gitlfs_object_asset (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Keep recovery records after repository deletion so cloud objects and multipart state are reaped.
CREATE TABLE IF NOT EXISTS gitlfs_upload (
  upload_id VARCHAR(36) NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  oid CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expected_size BIGINT NOT NULL,
  subject_key VARCHAR(64) NOT NULL,
  blob_store_id BIGINT UNSIGNED NOT NULL,
  generation BIGINT NOT NULL,
  fencing_token BIGINT NOT NULL,
  repository_version DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  state VARCHAR(16) NOT NULL,
  blob_ref VARCHAR(2048) NULL,
  object_key VARCHAR(1536) NULL,
  multipart_id VARCHAR(1024) NULL,
  cleanup_after DATETIME(3) NULL,
  cleanup_token VARCHAR(36) NULL,
  PRIMARY KEY (upload_id),
  CONSTRAINT fk_gitlfs_upload_store FOREIGN KEY (blob_store_id) REFERENCES blob_store(id),
  CONSTRAINT ck_gitlfs_size CHECK (expected_size >= 0),
  INDEX idx_gitlfs_upload_expiry (state, expires_at, upload_id),
  INDEX idx_gitlfs_upload_object (repository_id, oid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
