CREATE TABLE staging_session (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  owner_source VARCHAR(100) NOT NULL,
  owner_user_id VARCHAR(200) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  opened_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  frozen_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  expires_at DATETIME(3) NULL,
  promotion_id BIGINT UNSIGNED NULL,
  attributes_json JSON NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_staging_session_name (name),
  INDEX idx_staging_session_state (state, expires_at),
  INDEX idx_staging_session_owner (owner_source, owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE staging_session_repository (
  session_id BIGINT UNSIGNED NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  format VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (session_id, repository_id),
  INDEX idx_staging_session_repository_repo (repository_id),
  CONSTRAINT fk_staging_session_repository_session
    FOREIGN KEY (session_id) REFERENCES staging_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_staging_session_repository_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE staging_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_id BIGINT UNSIGNED NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  format VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  logical_path VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  logical_path_hash BINARY(32) NOT NULL,
  action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  blob_store_id BIGINT UNSIGNED NULL,
  asset_blob_id BIGINT UNSIGNED NULL,
  content_type VARCHAR(255) NULL,
  size BIGINT UNSIGNED NULL,
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  md5 CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  sha1 CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  source_path VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  expected_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  expected_size BIGINT UNSIGNED NULL,
  attributes_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_staging_item_path (session_id, repository_id, logical_path_hash),
  INDEX idx_staging_item_status (session_id, status),
  INDEX idx_staging_item_repository (repository_id),
  INDEX idx_staging_item_blob (asset_blob_id),
  CONSTRAINT fk_staging_item_session
    FOREIGN KEY (session_id) REFERENCES staging_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_staging_item_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_staging_item_blob
    FOREIGN KEY (asset_blob_id) REFERENCES asset_blob(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE staging_promotion (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_id BIGINT UNSIGNED NOT NULL,
  state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fence_token VARCHAR(64) NULL,
  manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  report_json JSON NULL,
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  INDEX idx_staging_promotion_session (session_id, id),
  CONSTRAINT fk_staging_promotion_session
    FOREIGN KEY (session_id) REFERENCES staging_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
