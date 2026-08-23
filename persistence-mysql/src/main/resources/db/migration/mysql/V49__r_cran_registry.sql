CREATE TABLE r_package_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  coordinate_hash BINARY(32) NOT NULL,
  distribution_name VARCHAR(384) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  component_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  architecture VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  package_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  package_version VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  version_order_key VARBINARY(1024) NOT NULL,
  package_architecture VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  filename VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  asset_path VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  asset_path_hash BINARY(32) NOT NULL,
  control_fields JSON NOT NULL,
  package_identity VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  data_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
  CONSTRAINT uk_r_package_repository_id UNIQUE (repository_id, id),
  CONSTRAINT uk_r_package_coordinate UNIQUE (repository_id, coordinate_hash),
  CONSTRAINT uk_r_package_path UNIQUE (repository_id, asset_path_hash),
  CONSTRAINT uk_r_package_asset UNIQUE (repository_id, asset_id),
  CONSTRAINT fk_r_package_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_r_package_asset
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  CONSTRAINT fk_r_package_component
    FOREIGN KEY (component_id) REFERENCES component(id) ON DELETE SET NULL,
  CONSTRAINT chk_r_package_source CHECK (source_kind IN ('HOSTED', 'PROXY')),
  INDEX idx_r_package_index_page
    (repository_id, distribution_name, component_name, architecture, package_name, id),
  INDEX idx_r_package_namespace_page
    (repository_id, distribution_name, package_name, id),
  INDEX idx_r_package_name
    (repository_id, distribution_name, package_name, version_order_key, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE r_package_tombstone (
  repository_id BIGINT UNSIGNED NOT NULL,
  coordinate_hash BINARY(32) NOT NULL,
  distribution_name VARCHAR(384) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  component_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  architecture VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  package_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  package_version VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  asset_path VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  reason VARCHAR(255) NULL,
  revision BIGINT NOT NULL,
  deleted_at DATETIME(3) NOT NULL,
  PRIMARY KEY (repository_id, coordinate_hash),
  CONSTRAINT fk_r_tombstone_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_r_tombstone_revision (repository_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE r_suite_state (
  repository_id BIGINT UNSIGNED NOT NULL,
  distribution_name VARCHAR(384) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  desired_revision BIGINT NOT NULL DEFAULT 0,
  desired_at DATETIME(3) NOT NULL,
  pending_since DATETIME(3) NULL,
  published_revision BIGINT NOT NULL DEFAULT 0,
  codec_revision INT NOT NULL DEFAULT 0,
  last_published_at DATETIME(3) NULL,
  last_error VARCHAR(2048) NULL,
  last_error_at DATETIME(3) NULL,
  publish_pending BOOLEAN
    GENERATED ALWAYS AS (desired_revision > published_revision) STORED,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, distribution_name),
  CONSTRAINT fk_r_suite_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_r_suite_pending (repository_id, desired_revision, published_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE r_snapshot (
  repository_id BIGINT UNSIGNED NOT NULL,
  distribution_name VARCHAR(384) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  revision BIGINT NOT NULL,
  codec_revision INT NOT NULL,
  manifest_json JSON NOT NULL,
  index_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  group_binding_token BIGINT NULL,
  created_at DATETIME(3) NOT NULL,
  published_at DATETIME(3) NULL,
  publish_complete BOOLEAN
    GENERATED ALWAYS AS (published_at IS NOT NULL) STORED,
  PRIMARY KEY (repository_id, distribution_name, revision),
  CONSTRAINT fk_r_snapshot_suite
    FOREIGN KEY (repository_id, distribution_name)
    REFERENCES r_suite_state(repository_id, distribution_name) ON DELETE CASCADE,
  INDEX idx_r_snapshot_created (repository_id, distribution_name, created_at),
  INDEX idx_r_snapshot_retention
    (repository_id, distribution_name, publish_complete, revision DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE r_proxy_distribution (
  repository_id BIGINT UNSIGNED NOT NULL,
  distribution_name VARCHAR(384) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  release_identity VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  release_manifest_json JSON NOT NULL,
  projection_verified BOOLEAN NOT NULL DEFAULT FALSE,
  observed_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, distribution_name),
  CONSTRAINT fk_r_proxy_distribution_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE r_publish_lease (
  lease_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner VARCHAR(128) NOT NULL,
  fencing_token BIGINT NOT NULL,
  attempt_count BIGINT NOT NULL DEFAULT 1,
  expires_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (lease_key),
  INDEX idx_r_lease_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE r_package_relation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  package_id BIGINT UNSIGNED NOT NULL,
  relation_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token_value VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  token_hash BINARY(32) NOT NULL,
  expression_value VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_r_relation UNIQUE (package_id, relation_kind, token_hash),
  CONSTRAINT fk_r_relation_package
    FOREIGN KEY (repository_id, package_id)
    REFERENCES r_package_record(repository_id, id) ON DELETE CASCADE,
  INDEX idx_r_relation_package (repository_id, package_id),
  INDEX idx_r_relation_lookup
    (repository_id, relation_kind, token_hash, package_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE r_group_snapshot_stage (
  group_repository_id BIGINT UNSIGNED NOT NULL,
  distribution_name VARCHAR(384) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  snapshot_revision BIGINT NOT NULL,
  binding_token BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (
    group_repository_id, distribution_name, snapshot_revision, binding_token),
  CONSTRAINT fk_r_group_stage_repository
    FOREIGN KEY (group_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_r_group_stage_cleanup
    (created_at, group_repository_id, distribution_name, snapshot_revision, binding_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE r_group_binding (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  group_repository_id BIGINT UNSIGNED NOT NULL,
  distribution_name VARCHAR(384) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  snapshot_revision BIGINT NOT NULL,
  binding_token BIGINT NOT NULL,
  path_value VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  path_hash BINARY(32) NOT NULL,
  member_repository_id BIGINT UNSIGNED NOT NULL,
  member_snapshot_revision BIGINT NOT NULL,
  member_path VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  package_identity VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_r_group_binding
    UNIQUE (
      group_repository_id, distribution_name, snapshot_revision, binding_token, path_hash),
  CONSTRAINT fk_r_group_binding_stage
    FOREIGN KEY (
      group_repository_id, distribution_name, snapshot_revision, binding_token)
    REFERENCES r_group_snapshot_stage(
      group_repository_id, distribution_name, snapshot_revision, binding_token)
    ON DELETE CASCADE,
  CONSTRAINT fk_r_group_member
    FOREIGN KEY (member_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_r_group_member
    (member_repository_id, member_snapshot_revision, id),
  INDEX idx_r_group_page
    (group_repository_id, distribution_name, snapshot_revision, binding_token, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_r_tombstone_cleanup
  ON r_package_tombstone(deleted_at, repository_id, revision);
CREATE INDEX idx_r_suite_worker
  ON r_suite_state(
    publish_pending, desired_at, repository_id, distribution_name);
CREATE INDEX idx_r_suite_force_publish
  ON r_suite_state(
    publish_pending, pending_since, repository_id, distribution_name);
CREATE INDEX idx_r_snapshot_cleanup
  ON r_snapshot(created_at, repository_id, distribution_name, revision);
