CREATE TABLE gitlfs_object (
  repository_id BIGINT NOT NULL,
  oid CHAR(64) NOT NULL,
  generation BIGINT NOT NULL,
  fencing_token BIGINT NOT NULL,
  owner_id VARCHAR(36) NULL,
  lease_until TIMESTAMP(3) NULL,
  asset_id BIGINT NULL,
  published BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (repository_id, oid),
  CONSTRAINT fk_gitlfs_object_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_gitlfs_object_asset FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE SET NULL,
  CONSTRAINT ck_gitlfs_generation CHECK (generation > 0)
);

-- Keep recovery records after repository deletion so cloud objects and multipart state are reaped.
CREATE TABLE gitlfs_upload (
  upload_id VARCHAR(36) NOT NULL,
  repository_id BIGINT NOT NULL,
  oid CHAR(64) NOT NULL,
  expected_size BIGINT NOT NULL,
  subject_key VARCHAR(64) NOT NULL,
  blob_store_id BIGINT NOT NULL,
  generation BIGINT NOT NULL,
  fencing_token BIGINT NOT NULL,
  repository_version TIMESTAMP(3) NOT NULL,
  expires_at TIMESTAMP(3) NOT NULL,
  state VARCHAR(16) NOT NULL,
  blob_ref VARCHAR(2048) NULL,
  object_key VARCHAR(1536) NULL,
  multipart_id VARCHAR(1024) NULL,
  cleanup_after TIMESTAMP(3) NULL,
  cleanup_token VARCHAR(36) NULL,
  PRIMARY KEY (upload_id),
  CONSTRAINT fk_gitlfs_upload_store FOREIGN KEY (blob_store_id) REFERENCES blob_store(id),
  CONSTRAINT ck_gitlfs_size CHECK (expected_size >= 0)
);

CREATE INDEX idx_gitlfs_object_owner ON gitlfs_object (owner_id, fencing_token);
CREATE INDEX idx_gitlfs_object_asset ON gitlfs_object (asset_id);
CREATE INDEX idx_gitlfs_upload_expiry ON gitlfs_upload (state, expires_at, upload_id);
CREATE INDEX idx_gitlfs_upload_object ON gitlfs_upload (repository_id, oid);
