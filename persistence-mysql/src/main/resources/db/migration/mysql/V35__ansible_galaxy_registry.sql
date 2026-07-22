CREATE TABLE ansible_collection_version (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  component_id BIGINT UNSIGNED NOT NULL,
  artifact_asset_id BIGINT UNSIGNED NOT NULL,
  namespace_lc VARCHAR(64) NOT NULL,
  namespace_display VARCHAR(64) NOT NULL,
  name_lc VARCHAR(64) NOT NULL,
  name_display VARCHAR(64) NOT NULL,
  version_original VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version_normalized VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  artifact_filename VARCHAR(255) NOT NULL,
  artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  artifact_size BIGINT NOT NULL,
  metadata_json JSON NOT NULL,
  dependencies_json JSON NOT NULL,
  requires_ansible VARCHAR(255) NULL,
  source_kind VARCHAR(16) NOT NULL,
  revision BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'READY',
  published_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_ansible_version_component UNIQUE (component_id),
  CONSTRAINT uk_ansible_version_asset UNIQUE (artifact_asset_id),
  CONSTRAINT uk_ansible_version_coordinate
    UNIQUE (repository_id, namespace_lc, name_lc, version_normalized),
  CONSTRAINT uk_ansible_version_filename UNIQUE (repository_id, artifact_filename),
  CONSTRAINT fk_ansible_version_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_ansible_version_component
    FOREIGN KEY (component_id) REFERENCES component(id) ON DELETE CASCADE,
  CONSTRAINT fk_ansible_version_asset
    FOREIGN KEY (artifact_asset_id) REFERENCES asset(id) ON DELETE RESTRICT,
  INDEX idx_ansible_collection (repository_id, namespace_lc, name_lc, state, revision),
  INDEX idx_ansible_version_revision (repository_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ansible_collection_signature (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  collection_version_id BIGINT UNSIGNED NOT NULL,
  signature_asset_id BIGINT UNSIGNED NULL,
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  key_fingerprint VARCHAR(128) NULL,
  source_kind VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_ansible_signature UNIQUE (collection_version_id, sha256),
  CONSTRAINT fk_ansible_signature_version
    FOREIGN KEY (collection_version_id) REFERENCES ansible_collection_version(id) ON DELETE CASCADE,
  CONSTRAINT fk_ansible_signature_asset
    FOREIGN KEY (signature_asset_id) REFERENCES asset(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ansible_import_task (
  task_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  requester VARCHAR(255) NULL,
  state VARCHAR(16) NOT NULL,
  messages_json JSON NOT NULL,
  error_code VARCHAR(128) NULL,
  error_detail VARCHAR(2048) NULL,
  namespace_lc VARCHAR(64) NULL,
  name_lc VARCHAR(64) NULL,
  version_normalized VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  artifact_filename VARCHAR(255) NULL,
  expected_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  actual_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  staging_asset_id BIGINT UNSIGNED NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  lease_owner VARCHAR(128) NULL,
  lease_expires_at DATETIME(3) NULL,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (task_uuid),
  CONSTRAINT fk_ansible_task_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_ansible_task_staging_asset
    FOREIGN KEY (staging_asset_id) REFERENCES asset(id) ON DELETE SET NULL,
  INDEX idx_ansible_task_claim (state, lease_expires_at, created_at),
  INDEX idx_ansible_task_repository (repository_id, created_at),
  INDEX idx_ansible_task_terminal (state, finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ansible_proxy_version_state (
  repository_id BIGINT UNSIGNED NOT NULL,
  namespace_lc VARCHAR(64) NOT NULL,
  name_lc VARCHAR(64) NOT NULL,
  version_normalized VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  artifact_filename VARCHAR(255) NULL,
  upstream_href VARCHAR(2048) NULL,
  upstream_download_url VARCHAR(2048) NULL,
  artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  metadata_etag VARCHAR(512) NULL,
  metadata_last_modified VARCHAR(255) NULL,
  cache_until DATETIME(3) NULL,
  verified_at DATETIME(3) NULL,
  negative_status INT NULL,
  negative_expires_at DATETIME(3) NULL,
  protocol_metadata_json JSON NOT NULL,
  revision BIGINT NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (repository_id, namespace_lc, name_lc, version_normalized),
  CONSTRAINT fk_ansible_proxy_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_ansible_proxy_expiry (repository_id, cache_until),
  INDEX idx_ansible_proxy_artifact (repository_id, artifact_filename),
  INDEX idx_ansible_proxy_negative (negative_expires_at),
  INDEX idx_ansible_proxy_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ansible_proxy_inventory (
  repository_id BIGINT UNSIGNED NOT NULL,
  namespace_lc VARCHAR(64) NOT NULL,
  name_lc VARCHAR(64) NOT NULL,
  cache_until DATETIME(3) NOT NULL,
  checked_at DATETIME(3) NOT NULL,
  revision BIGINT NOT NULL,
  version_count INT NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (repository_id, namespace_lc, name_lc),
  CONSTRAINT fk_ansible_inventory_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_ansible_inventory_expiry (cache_until, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ansible_proxy_inventory_version (
  repository_id BIGINT UNSIGNED NOT NULL,
  namespace_lc VARCHAR(64) NOT NULL,
  name_lc VARCHAR(64) NOT NULL,
  version_normalized VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (repository_id, namespace_lc, name_lc, version_normalized),
  CONSTRAINT fk_ansible_inventory_version
    FOREIGN KEY (repository_id, namespace_lc, name_lc)
    REFERENCES ansible_proxy_inventory(repository_id, namespace_lc, name_lc)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ansible_group_binding (
  group_repository_id BIGINT UNSIGNED NOT NULL,
  namespace_lc VARCHAR(64) NOT NULL,
  name_lc VARCHAR(64) NOT NULL,
  version_normalized VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  member_repository_id BIGINT UNSIGNED NOT NULL,
  member_version_id BIGINT UNSIGNED NULL,
  artifact_filename VARCHAR(255) NOT NULL,
  member_revision BIGINT NOT NULL,
  group_config_revision BIGINT NOT NULL,
  artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  observed_count BIGINT NOT NULL DEFAULT 1,
  bound_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (group_repository_id, namespace_lc, name_lc, version_normalized),
  CONSTRAINT fk_ansible_group_repository
    FOREIGN KEY (group_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_ansible_group_member
    FOREIGN KEY (member_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_ansible_group_version
    FOREIGN KEY (member_version_id) REFERENCES ansible_collection_version(id) ON DELETE CASCADE,
  INDEX idx_ansible_group_member (member_repository_id, member_revision),
  INDEX idx_ansible_group_artifact (group_repository_id, artifact_filename)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ansible_registry_lease (
  lease_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner VARCHAR(128) NOT NULL,
  fencing_token BIGINT NOT NULL,
  attempt_count BIGINT NOT NULL DEFAULT 1,
  expires_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (lease_key),
  INDEX idx_ansible_lease_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
