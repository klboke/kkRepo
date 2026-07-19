CREATE TABLE npm_release_index_revision (
  package_root_asset_id BIGINT PRIMARY KEY REFERENCES asset(id) ON DELETE CASCADE,
  source_blob_id BIGINT NOT NULL REFERENCES asset_blob(id) ON DELETE CASCADE,
  complete_publish_times BOOLEAN NOT NULL,
  release_count INTEGER NOT NULL,
  indexed_at TIMESTAMPTZ(3) NOT NULL
);

CREATE INDEX idx_npm_release_revision_blob
  ON npm_release_index_revision(source_blob_id);

CREATE TABLE npm_release_index_entry (
  package_root_asset_id BIGINT NOT NULL
    REFERENCES npm_release_index_revision(package_root_asset_id) ON DELETE CASCADE,
  source_blob_id BIGINT NOT NULL,
  ordinal INTEGER NOT NULL,
  version VARCHAR(512) NOT NULL,
  version_hash BYTEA NOT NULL,
  published_at TIMESTAMPTZ(3) NULL,
  invalid_reason VARCHAR(128) NULL,
  tarball_name VARCHAR(1024) NULL,
  tarball_name_hash BYTEA NULL,
  PRIMARY KEY (package_root_asset_id, version_hash)
);

CREATE INDEX idx_npm_release_tarball
  ON npm_release_index_entry(package_root_asset_id, source_blob_id, tarball_name_hash);

CREATE INDEX idx_npm_release_published
  ON npm_release_index_entry(package_root_asset_id, source_blob_id, published_at);
