CREATE TABLE conan_recipe (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  component_id BIGINT UNSIGNED NULL,
  coordinate_hash BINARY(32) NOT NULL,
  name_key VARCHAR(101) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version_key VARCHAR(101) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_key VARCHAR(101) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
  channel_key VARCHAR(101) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
  latest_recipe_revision_id BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_conan_recipe_coordinate UNIQUE (repository_id, coordinate_hash),
  CONSTRAINT fk_conan_recipe_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_conan_recipe_component
    FOREIGN KEY (component_id) REFERENCES component(id) ON DELETE SET NULL,
  INDEX idx_conan_recipe_search
    (repository_id, name_key, user_key, channel_key, version_key, id),
  INDEX idx_conan_recipe_page (repository_id, id),
  INDEX idx_conan_recipe_name_page (repository_id, name_key, id),
  INDEX idx_conan_recipe_component (repository_id, component_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conan_recipe_revision (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  recipe_id BIGINT UNSIGNED NOT NULL,
  rrev VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  rrev_hash BINARY(32) NOT NULL,
  manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  repository_revision BIGINT NOT NULL,
  published_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_conan_rrev UNIQUE (recipe_id, rrev_hash),
  CONSTRAINT fk_conan_rrev_recipe
    FOREIGN KEY (recipe_id) REFERENCES conan_recipe(id) ON DELETE CASCADE,
  INDEX idx_conan_rrev_list (recipe_id, published_at, id),
  INDEX idx_conan_rrev_page (recipe_id, id),
  INDEX idx_conan_rrev_repository_revision (repository_revision, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE conan_recipe
  ADD CONSTRAINT fk_conan_recipe_latest_rrev
  FOREIGN KEY (latest_recipe_revision_id) REFERENCES conan_recipe_revision(id)
  ON DELETE SET NULL;

CREATE TABLE conan_package (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  recipe_revision_id BIGINT UNSIGNED NOT NULL,
  package_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  package_id_hash BINARY(32) NOT NULL,
  settings_json JSON NOT NULL,
  options_json JSON NOT NULL,
  requires_json JSON NOT NULL,
  setting_os VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  setting_arch VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  setting_compiler VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  setting_compiler_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  setting_build_type VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  latest_package_revision_id BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_conan_package UNIQUE (recipe_revision_id, package_id_hash),
  CONSTRAINT fk_conan_package_rrev
    FOREIGN KEY (recipe_revision_id) REFERENCES conan_recipe_revision(id) ON DELETE CASCADE,
  INDEX idx_conan_package_list (recipe_revision_id, id),
  INDEX idx_conan_package_os (recipe_revision_id, setting_os, id),
  INDEX idx_conan_package_arch (recipe_revision_id, setting_arch, id),
  INDEX idx_conan_package_compiler
    (recipe_revision_id, setting_compiler, setting_compiler_version, id),
  INDEX idx_conan_package_build_type (recipe_revision_id, setting_build_type, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conan_package_revision (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  conan_package_id BIGINT UNSIGNED NOT NULL,
  prev_value VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  prev_hash BINARY(32) NOT NULL,
  manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  source_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  repository_revision BIGINT NOT NULL,
  published_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_conan_prev UNIQUE (conan_package_id, prev_hash),
  CONSTRAINT fk_conan_prev_package
    FOREIGN KEY (conan_package_id) REFERENCES conan_package(id) ON DELETE CASCADE,
  INDEX idx_conan_prev_list (conan_package_id, published_at, id),
  INDEX idx_conan_prev_page (conan_package_id, id),
  INDEX idx_conan_prev_repository_revision (repository_revision, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE conan_package
  ADD CONSTRAINT fk_conan_package_latest_prev
  FOREIGN KEY (latest_package_revision_id) REFERENCES conan_package_revision(id)
  ON DELETE SET NULL;

CREATE TABLE conan_revision_file (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  owner_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner_id BIGINT UNSIGNED NOT NULL,
  path_value VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  path_hash BINARY(32) NOT NULL,
  asset_id BIGINT UNSIGNED NULL,
  md5 CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  sha1 CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  content_type VARCHAR(255) NULL,
  source_repository_id BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_conan_revision_file UNIQUE (owner_kind, owner_id, path_hash),
  CONSTRAINT uk_conan_revision_file_asset UNIQUE (asset_id),
  CONSTRAINT fk_conan_revision_file_asset
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  CONSTRAINT fk_conan_revision_file_source
    FOREIGN KEY (source_repository_id) REFERENCES repository(id) ON DELETE SET NULL,
  CONSTRAINT ck_conan_revision_file_size CHECK (size_bytes >= 0),
  INDEX idx_conan_file_list (owner_kind, owner_id, id),
  INDEX idx_conan_file_source (source_repository_id, owner_kind, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conan_repository_state (
  repository_id BIGINT UNSIGNED NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id),
  CONSTRAINT fk_conan_state_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conan_upload_session (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  owner_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  coordinate_key VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  coordinate_hash BINARY(32) NOT NULL,
  actor_key VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  actor_hash BINARY(32) NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner VARCHAR(128) NOT NULL,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  lease_until DATETIME(3) NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_conan_upload_session
    UNIQUE (repository_id, owner_kind, coordinate_hash, actor_hash),
  CONSTRAINT fk_conan_upload_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_conan_upload_claim (status, lease_until, id),
  INDEX idx_conan_upload_expiry (expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conan_upload_file (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_id BIGINT UNSIGNED NOT NULL,
  path_value VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  path_hash BINARY(32) NOT NULL,
  staging_asset_id BIGINT UNSIGNED NOT NULL,
  md5 CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sha1 CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  content_type VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_conan_upload_file UNIQUE (session_id, path_hash),
  CONSTRAINT uk_conan_upload_staging_asset UNIQUE (staging_asset_id),
  CONSTRAINT fk_conan_upload_file_session
    FOREIGN KEY (session_id) REFERENCES conan_upload_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_conan_upload_file_asset
    FOREIGN KEY (staging_asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  INDEX idx_conan_upload_file_list (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conan_coordinate_lease (
  repository_id BIGINT UNSIGNED NOT NULL,
  coordinate_key VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  coordinate_hash BINARY(32) NOT NULL,
  owner VARCHAR(128) NOT NULL,
  fencing_token BIGINT NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, coordinate_hash),
  CONSTRAINT fk_conan_lease_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_conan_lease_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conan_group_binding (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  group_repository_id BIGINT UNSIGNED NOT NULL,
  binding_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  coordinate_key VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  coordinate_hash BINARY(32) NOT NULL,
  member_repository_id BIGINT UNSIGNED NOT NULL,
  member_owner_id BIGINT UNSIGNED NOT NULL,
  member_revision BIGINT NOT NULL,
  group_config_revision BIGINT NOT NULL,
  expires_at DATETIME(3) NULL,
  bound_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_conan_group_binding
    UNIQUE (group_repository_id, binding_kind, coordinate_hash),
  CONSTRAINT fk_conan_group_repository
    FOREIGN KEY (group_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_conan_group_member
    FOREIGN KEY (member_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_conan_group_member (member_repository_id, member_revision, id),
  INDEX idx_conan_group_expiry (expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE conan_auth_token (
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  subject_source VARCHAR(128) NOT NULL,
  subject_user_id VARCHAR(255) NOT NULL,
  realm_id VARCHAR(255) NULL,
  api_key_id BIGINT UNSIGNED NULL,
  expires_at DATETIME(3) NOT NULL,
  last_used_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (token_hash),
  CONSTRAINT fk_conan_token_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_conan_token_expiry (expires_at, token_hash),
  INDEX idx_conan_token_subject (subject_source, subject_user_id, token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
