CREATE TABLE huggingface_model_revision (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  repo_id VARCHAR(193) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  repo_id_hash BINARY(32) NOT NULL,
  commit_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  component_id BIGINT UNSIGNED NULL,
  raw_metadata_asset_id BIGINT UNSIGNED NULL,
  author_name VARCHAR(255) NULL,
  committed_at DATETIME(3) NULL,
  private_model BOOLEAN NOT NULL DEFAULT FALSE,
  gated_model BOOLEAN NOT NULL DEFAULT FALSE,
  library_name VARCHAR(128) NULL,
  pipeline_tag VARCHAR(128) NULL,
  license_name VARCHAR(128) NULL,
  observed_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_hf_revision_identity UNIQUE (repository_id, repo_id_hash, commit_hash),
  CONSTRAINT uk_hf_revision_component UNIQUE (component_id),
  CONSTRAINT fk_hf_revision_repository FOREIGN KEY (repository_id)
    REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_hf_revision_component FOREIGN KEY (component_id)
    REFERENCES component(id) ON DELETE SET NULL,
  CONSTRAINT fk_hf_revision_raw_asset FOREIGN KEY (raw_metadata_asset_id)
    REFERENCES asset(id) ON DELETE SET NULL,
  INDEX idx_hf_revision_page (repository_id, repo_id_hash, observed_at, id),
  INDEX idx_hf_revision_commit (repository_id, commit_hash, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE huggingface_revision_ref (
  repository_id BIGINT UNSIGNED NOT NULL,
  repo_id VARCHAR(193) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  repo_id_hash BINARY(32) NOT NULL,
  requested_ref VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  ref_hash BINARY(32) NOT NULL,
  commit_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  binding_generation BIGINT NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  observed_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, repo_id_hash, ref_hash),
  CONSTRAINT fk_hf_ref_repository FOREIGN KEY (repository_id)
    REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT ck_hf_ref_generation CHECK (binding_generation > 0),
  INDEX idx_hf_ref_expiry (expires_at, repository_id),
  INDEX idx_hf_ref_commit (repository_id, repo_id_hash, commit_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE huggingface_file (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  revision_id BIGINT UNSIGNED NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  repo_id VARCHAR(193) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  repo_id_hash BINARY(32) NOT NULL,
  commit_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  file_path VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  path_hash BINARY(32) NOT NULL,
  asset_id BIGINT UNSIGNED NULL,
  component_id BIGINT UNSIGNED NULL,
  git_oid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lfs_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  xet_hash VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NULL,
  expected_size BIGINT UNSIGNED NULL,
  internal_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  content_type VARCHAR(255) NULL,
  file_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  file_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  next_attempt_at DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_hf_file_identity UNIQUE (repository_id, repo_id_hash, commit_hash, path_hash),
  CONSTRAINT uk_hf_file_asset UNIQUE (asset_id),
  CONSTRAINT fk_hf_file_revision FOREIGN KEY (revision_id)
    REFERENCES huggingface_model_revision(id) ON DELETE CASCADE,
  CONSTRAINT fk_hf_file_repository FOREIGN KEY (repository_id)
    REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_hf_file_asset FOREIGN KEY (asset_id)
    REFERENCES asset(id) ON DELETE SET NULL,
  CONSTRAINT fk_hf_file_component FOREIGN KEY (component_id)
    REFERENCES component(id) ON DELETE SET NULL,
  CONSTRAINT ck_hf_file_size CHECK (expected_size IS NULL OR expected_size >= 0),
  INDEX idx_hf_file_page (revision_id, id),
  INDEX idx_hf_file_state (file_state, next_attempt_at, id),
  INDEX idx_hf_file_component (component_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE huggingface_api_cache (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  route_path TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  route_hash BINARY(32) NOT NULL,
  query_string TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  query_hash BINARY(32) NOT NULL,
  request_fingerprint VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash BINARY(32) NOT NULL,
  raw_asset_id BIGINT UNSIGNED NULL,
  derived_asset_id BIGINT UNSIGNED NULL,
  upstream_etag VARCHAR(255) NULL,
  derived_etag VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  next_link TEXT NULL,
  transform_version INT NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uk_hf_api_identity UNIQUE (repository_id, route_hash, query_hash, request_hash),
  CONSTRAINT uk_hf_api_derived_asset UNIQUE (derived_asset_id),
  CONSTRAINT fk_hf_api_repository FOREIGN KEY (repository_id)
    REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_hf_api_raw_asset FOREIGN KEY (raw_asset_id)
    REFERENCES asset(id) ON DELETE SET NULL,
  CONSTRAINT fk_hf_api_derived_asset FOREIGN KEY (derived_asset_id)
    REFERENCES asset(id) ON DELETE SET NULL,
  INDEX idx_hf_api_expiry (expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE huggingface_route_projection (
  repository_id BIGINT UNSIGNED NOT NULL,
  route_path TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  route_hash BINARY(32) NOT NULL,
  file_id BIGINT UNSIGNED NOT NULL,
  requested_ref VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  ref_generation BIGINT NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, route_hash),
  CONSTRAINT fk_hf_projection_repository FOREIGN KEY (repository_id)
    REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_hf_projection_file FOREIGN KEY (file_id)
    REFERENCES huggingface_file(id) ON DELETE CASCADE,
  INDEX idx_hf_projection_file (file_id, repository_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE proxy_fetch_lease (
  repository_id BIGINT UNSIGNED NOT NULL,
  fetch_key TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  fetch_key_hash BINARY(32) NOT NULL,
  owner VARCHAR(128) NOT NULL,
  fencing_token BIGINT NOT NULL,
  attempt_count BIGINT NOT NULL DEFAULT 1,
  expires_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, fetch_key_hash),
  CONSTRAINT fk_proxy_fetch_lease_repository FOREIGN KEY (repository_id)
    REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_proxy_fetch_lease_expiry (expires_at, repository_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
