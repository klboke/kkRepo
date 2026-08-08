CREATE TABLE conda_package_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  channel_key VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  channel_key_hash BINARY(32) NOT NULL,
  subdir VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  filename VARCHAR(211) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  build_string VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  build_number BIGINT NOT NULL,
  archive_format VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  record_json JSON NOT NULL,
  record_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  md5 CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  asset_id BIGINT UNSIGNED NULL,
  component_id BIGINT UNSIGNED NULL,
  source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  indexed_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_conda_package_path
    UNIQUE (repository_id, channel_key_hash, subdir, filename),
  CONSTRAINT fk_conda_package_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_conda_package_asset
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  CONSTRAINT fk_conda_package_component
    FOREIGN KEY (component_id) REFERENCES component(id) ON DELETE SET NULL,
  CONSTRAINT ck_conda_package_build_number CHECK (build_number >= 0),
  INDEX idx_conda_package_coordinate
    (repository_id, channel_key_hash, subdir, name),
  INDEX idx_conda_package_channel_name
    (repository_id, channel_key_hash, name, subdir, filename),
  INDEX idx_conda_package_metadata
    (repository_id, channel_key_hash, subdir, archive_format, filename),
  INDEX idx_conda_package_revision (repository_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conda_channel_state (
  repository_id BIGINT UNSIGNED NOT NULL,
  channel_key VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  channel_key_hash BINARY(32) NOT NULL,
  subdir VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  metadata_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  package_base_url VARCHAR(2048) NULL,
  revision BIGINT NOT NULL,
  indexed_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, channel_key_hash, subdir),
  CONSTRAINT fk_conda_channel_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_conda_channel_revision (repository_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conda_package_tombstone (
  repository_id BIGINT UNSIGNED NOT NULL,
  channel_key VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  channel_key_hash BINARY(32) NOT NULL,
  subdir VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  filename VARCHAR(211) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(255) NULL,
  revision BIGINT NOT NULL,
  deleted_at DATETIME(3) NOT NULL,
  PRIMARY KEY (repository_id, channel_key_hash, subdir, filename),
  CONSTRAINT fk_conda_tombstone_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_conda_tombstone_revision (repository_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conda_group_source_binding (
  group_repository_id BIGINT UNSIGNED NOT NULL,
  channel_key VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  channel_key_hash BINARY(32) NOT NULL,
  subdir VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  filename VARCHAR(211) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  member_repository_id BIGINT UNSIGNED NOT NULL,
  member_revision BIGINT NOT NULL,
  sha256 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  group_config_revision BIGINT NOT NULL,
  bound_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (group_repository_id, channel_key_hash, subdir, filename),
  CONSTRAINT fk_conda_group_repository
    FOREIGN KEY (group_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_conda_group_member
    FOREIGN KEY (member_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_conda_group_member (member_repository_id, member_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conda_coordinate_lease (
  lease_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner VARCHAR(128) NOT NULL,
  fencing_token BIGINT NOT NULL,
  attempt_count BIGINT NOT NULL DEFAULT 1,
  expires_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (lease_key),
  INDEX idx_conda_lease_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
