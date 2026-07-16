CREATE TABLE swift_release (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  component_id BIGINT UNSIGNED NOT NULL,
  scope_lc VARCHAR(64) NOT NULL,
  scope_display VARCHAR(64) NOT NULL,
  name_lc VARCHAR(128) NOT NULL,
  name_display VARCHAR(128) NOT NULL,
  version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  published_at DATETIME(3) NOT NULL,
  metadata_json JSON NOT NULL,
  archive_sha256 CHAR(64) NOT NULL,
  archive_asset_id BIGINT UNSIGNED NOT NULL,
  signature_format VARCHAR(64) NULL,
  source_signature_asset_id BIGINT UNSIGNED NULL,
  metadata_signature_asset_id BIGINT UNSIGNED NULL,
  source_kind VARCHAR(16) NOT NULL,
  revision BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'READY',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_swift_release_component UNIQUE (component_id),
  CONSTRAINT uk_swift_release_coordinate UNIQUE (repository_id, scope_lc, name_lc, version),
  CONSTRAINT fk_swift_release_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_swift_release_component FOREIGN KEY (component_id) REFERENCES component(id) ON DELETE CASCADE,
  CONSTRAINT fk_swift_release_archive FOREIGN KEY (archive_asset_id) REFERENCES asset(id) ON DELETE RESTRICT,
  CONSTRAINT fk_swift_release_source_sig FOREIGN KEY (source_signature_asset_id) REFERENCES asset(id) ON DELETE SET NULL,
  CONSTRAINT fk_swift_release_metadata_sig FOREIGN KEY (metadata_signature_asset_id) REFERENCES asset(id) ON DELETE SET NULL,
  INDEX idx_swift_release_package (repository_id, scope_lc, name_lc, status, version),
  INDEX idx_swift_release_revision (repository_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE swift_manifest (
  release_id BIGINT UNSIGNED NOT NULL,
  filename VARCHAR(255) NOT NULL,
  tools_version VARCHAR(32) NOT NULL DEFAULT '',
  asset_id BIGINT UNSIGNED NOT NULL,
  sha256 CHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (release_id, tools_version),
  CONSTRAINT uk_swift_manifest_filename UNIQUE (release_id, filename),
  CONSTRAINT uk_swift_manifest_asset UNIQUE (asset_id),
  CONSTRAINT fk_swift_manifest_release FOREIGN KEY (release_id) REFERENCES swift_release(id) ON DELETE CASCADE,
  CONSTRAINT fk_swift_manifest_asset FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE swift_repository_url (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  release_id BIGINT UNSIGNED NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  scope_lc VARCHAR(64) NOT NULL,
  name_lc VARCHAR(128) NOT NULL,
  normalized_url VARCHAR(1024) NOT NULL,
  normalized_url_hash BINARY(32) NOT NULL,
  display_url VARCHAR(1024) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_swift_repository_url UNIQUE (release_id, normalized_url_hash),
  CONSTRAINT fk_swift_url_release FOREIGN KEY (release_id) REFERENCES swift_release(id) ON DELETE CASCADE,
  CONSTRAINT fk_swift_url_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_swift_url_lookup (repository_id, normalized_url_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE swift_proxy_source (
  repository_id BIGINT UNSIGNED NOT NULL,
  scope_lc VARCHAR(64) NOT NULL,
  name_lc VARCHAR(128) NOT NULL,
  version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  upstream_repository_url VARCHAR(1024) NOT NULL,
  upstream_tag VARCHAR(255) NOT NULL,
  commit_sha VARCHAR(64) NOT NULL,
  generation_profile VARCHAR(128) NOT NULL,
  archive_sha256 CHAR(64) NULL,
  cache_state VARCHAR(16) NOT NULL,
  release_id BIGINT UNSIGNED NULL,
  verified_at DATETIME(3) NULL,
  revision BIGINT NOT NULL,
  observed_count BIGINT NOT NULL DEFAULT 1,
  last_checked_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, scope_lc, name_lc, version),
  CONSTRAINT fk_swift_proxy_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_swift_proxy_release FOREIGN KEY (release_id) REFERENCES swift_release(id) ON DELETE SET NULL,
  INDEX idx_swift_proxy_commit (repository_id, commit_sha),
  INDEX idx_swift_proxy_state (repository_id, cache_state, last_checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE swift_group_source_binding (
  group_repository_id BIGINT UNSIGNED NOT NULL,
  scope_lc VARCHAR(64) NOT NULL,
  name_lc VARCHAR(128) NOT NULL,
  version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  member_repository_id BIGINT UNSIGNED NOT NULL,
  member_release_id BIGINT UNSIGNED NOT NULL,
  member_revision BIGINT NOT NULL,
  group_config_revision BIGINT NOT NULL,
  observed_count BIGINT NOT NULL DEFAULT 1,
  bound_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (group_repository_id, scope_lc, name_lc, version),
  CONSTRAINT fk_swift_group_repository FOREIGN KEY (group_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_swift_group_member FOREIGN KEY (member_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_swift_group_release FOREIGN KEY (member_release_id) REFERENCES swift_release(id) ON DELETE CASCADE,
  INDEX idx_swift_group_member (member_repository_id, member_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE swift_coordinate_lease (
  lease_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner VARCHAR(128) NOT NULL,
  fencing_token BIGINT NOT NULL,
  attempt_count BIGINT NOT NULL DEFAULT 1,
  expires_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (lease_key),
  INDEX idx_swift_lease_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE swift_release_tombstone (
  repository_id BIGINT UNSIGNED NOT NULL,
  scope_lc VARCHAR(64) NOT NULL,
  name_lc VARCHAR(128) NOT NULL,
  version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(255) NULL,
  revision BIGINT NOT NULL,
  deleted_at DATETIME(3) NOT NULL,
  PRIMARY KEY (repository_id, scope_lc, name_lc, version),
  CONSTRAINT fk_swift_tombstone_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_swift_tombstone_revision (repository_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE swift_proxy_negative_cache (
  repository_id BIGINT UNSIGNED NOT NULL,
  cache_key VARCHAR(512) NOT NULL,
  cache_key_hash BINARY(32) NOT NULL,
  status_code INT NOT NULL,
  retry_after DATETIME(3) NULL,
  expires_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, cache_key_hash),
  CONSTRAINT fk_swift_negative_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_swift_negative_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
