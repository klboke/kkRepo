CREATE TABLE apt_package_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  coordinate_hash BINARY(32) NOT NULL,
  distribution_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  component_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  architecture VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  package_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  package_version VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  source_package VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  filename VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  asset_path VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  control_fields JSON NOT NULL,
  md5 CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sha1 CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  asset_id BIGINT UNSIGNED NULL,
  component_id BIGINT UNSIGNED NULL,
  source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  indexed_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_apt_package_coordinate UNIQUE (repository_id, coordinate_hash),
  CONSTRAINT uk_apt_package_asset UNIQUE (repository_id, asset_id),
  CONSTRAINT fk_apt_package_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_apt_package_asset
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  CONSTRAINT fk_apt_package_component
    FOREIGN KEY (component_id) REFERENCES component(id) ON DELETE SET NULL,
  INDEX idx_apt_package_index
    (repository_id, distribution_name, component_name, architecture, package_name),
  INDEX idx_apt_package_path (repository_id, asset_path(255)),
  INDEX idx_apt_package_revision (repository_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE apt_package_tombstone (
  repository_id BIGINT UNSIGNED NOT NULL,
  coordinate_hash BINARY(32) NOT NULL,
  distribution_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  component_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  architecture VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  package_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  package_version VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  asset_path VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  reason VARCHAR(255) NULL,
  revision BIGINT NOT NULL,
  deleted_at DATETIME(3) NOT NULL,
  PRIMARY KEY (repository_id, coordinate_hash),
  CONSTRAINT fk_apt_tombstone_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_apt_tombstone_revision (repository_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE apt_suite_state (
  repository_id BIGINT UNSIGNED NOT NULL,
  distribution_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  desired_revision BIGINT NOT NULL DEFAULT 0,
  desired_at DATETIME(3) NOT NULL,
  published_revision BIGINT NOT NULL DEFAULT 0,
  signing_key_revision INT NOT NULL DEFAULT 0,
  last_published_at DATETIME(3) NULL,
  last_error VARCHAR(2048) NULL,
  last_error_at DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, distribution_name),
  CONSTRAINT fk_apt_suite_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_apt_suite_pending (repository_id, desired_revision, published_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE apt_snapshot (
  repository_id BIGINT UNSIGNED NOT NULL,
  distribution_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  revision BIGINT NOT NULL,
  signing_key_revision INT NOT NULL,
  manifest_json JSON NOT NULL,
  release_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL,
  published_at DATETIME(3) NULL,
  PRIMARY KEY (repository_id, distribution_name, revision),
  CONSTRAINT fk_apt_snapshot_suite
    FOREIGN KEY (repository_id, distribution_name)
    REFERENCES apt_suite_state(repository_id, distribution_name) ON DELETE CASCADE,
  INDEX idx_apt_snapshot_created (repository_id, distribution_name, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE apt_signing_key (
  repository_id BIGINT UNSIGNED NOT NULL,
  revision INT NOT NULL,
  key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  encrypted_private_key MEDIUMTEXT NOT NULL,
  public_key MEDIUMTEXT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (repository_id, revision),
  CONSTRAINT fk_apt_signing_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_apt_signing_active (repository_id, active, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE apt_proxy_distribution (
  repository_id BIGINT UNSIGNED NOT NULL,
  distribution_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  release_identity VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  release_manifest_json JSON NOT NULL,
  signature_verified BOOLEAN NOT NULL DEFAULT FALSE,
  observed_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, distribution_name),
  CONSTRAINT fk_apt_proxy_distribution_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE apt_publish_lease (
  lease_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner VARCHAR(128) NOT NULL,
  fencing_token BIGINT NOT NULL,
  attempt_count BIGINT NOT NULL DEFAULT 1,
  expires_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (lease_key),
  INDEX idx_apt_lease_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
