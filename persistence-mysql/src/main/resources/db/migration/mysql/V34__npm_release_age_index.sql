CREATE TABLE npm_release_index_revision (
  package_root_asset_id BIGINT UNSIGNED NOT NULL,
  source_blob_id BIGINT UNSIGNED NOT NULL,
  complete_publish_times BOOLEAN NOT NULL,
  release_count INT NOT NULL,
  indexed_at DATETIME(3) NOT NULL,
  PRIMARY KEY (package_root_asset_id),
  KEY idx_npm_release_revision_blob (source_blob_id),
  CONSTRAINT fk_npm_release_revision_asset
    FOREIGN KEY (package_root_asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  CONSTRAINT fk_npm_release_revision_blob
    FOREIGN KEY (source_blob_id) REFERENCES asset_blob(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE npm_release_index_entry (
  package_root_asset_id BIGINT UNSIGNED NOT NULL,
  source_blob_id BIGINT UNSIGNED NOT NULL,
  ordinal INT NOT NULL,
  version VARCHAR(512) NOT NULL,
  version_hash BINARY(32) NOT NULL,
  published_at DATETIME(3) NULL,
  invalid_reason VARCHAR(128) NULL,
  tarball_name VARCHAR(1024) NULL,
  tarball_name_hash BINARY(32) NULL,
  PRIMARY KEY (package_root_asset_id, version_hash),
  KEY idx_npm_release_tarball
    (package_root_asset_id, source_blob_id, tarball_name_hash),
  KEY idx_npm_release_published
    (package_root_asset_id, source_blob_id, published_at),
  CONSTRAINT fk_npm_release_entry_revision
    FOREIGN KEY (package_root_asset_id)
    REFERENCES npm_release_index_revision(package_root_asset_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
