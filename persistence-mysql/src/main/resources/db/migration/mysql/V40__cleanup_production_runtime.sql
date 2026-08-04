ALTER TABLE cleanup_run
  ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE AFTER state,
  ADD COLUMN cancelled_at DATETIME(3) NULL AFTER completed_at;

ALTER TABLE cleanup_run_repository
  ADD COLUMN attempt_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER state,
  ADD COLUMN max_attempts INT UNSIGNED NOT NULL DEFAULT 3 AFTER attempt_count,
  ADD COLUMN scan_budget INT UNSIGNED NULL AFTER max_attempts,
  ADD COLUMN next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER scan_budget,
  ADD COLUMN lease_owner VARCHAR(255) NULL AFTER next_attempt_at,
  ADD COLUMN lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER lease_owner,
  ADD COLUMN lease_until DATETIME(3) NULL AFTER lease_token,
  ADD COLUMN last_heartbeat_at DATETIME(3) NULL AFTER lease_until,
  ADD COLUMN fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER last_heartbeat_at,
  ADD COLUMN last_error_code VARCHAR(64) NULL AFTER error_summary,
  ADD INDEX idx_cleanup_run_repository_claim
    (state, next_attempt_at, lease_until, id),
  ADD INDEX idx_cleanup_run_repository_lease (lease_until, id);

CREATE INDEX idx_cleanup_run_repository_repository_claim
  ON cleanup_run_repository (repository_id, id, state, next_attempt_at);

ALTER TABLE repository_cleanup_policy
  DROP FOREIGN KEY fk_repository_cleanup_policy_repository;

ALTER TABLE repository_cleanup_policy
  ADD CONSTRAINT fk_repository_cleanup_policy_repository
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE RESTRICT;

CREATE TABLE cleanup_repository_lease (
  repository_id BIGINT UNSIGNED NOT NULL,
  run_repository_id BIGINT UNSIGNED NULL,
  lease_owner VARCHAR(255) NULL,
  lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
  expires_at DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id),
  UNIQUE KEY uk_cleanup_repository_lease_shard (run_repository_id),
  KEY idx_cleanup_repository_lease_expiry (expires_at, repository_id),
  CONSTRAINT fk_cleanup_repository_lease_repository
    FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  CONSTRAINT fk_cleanup_repository_lease_shard
    FOREIGN KEY (run_repository_id) REFERENCES cleanup_run_repository (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO cleanup_repository_lease (repository_id)
SELECT id FROM repository;

CREATE TABLE cleanup_usage_tracking_repository (
  repository_id BIGINT UNSIGNED NOT NULL,
  tracking_started_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id),
  CONSTRAINT fk_cleanup_usage_tracking_repository
    FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE cleanup_usage (
  asset_id BIGINT UNSIGNED NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  first_downloaded_at DATETIME(3) NOT NULL,
  last_downloaded_at DATETIME(3) NOT NULL,
  usage_revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (asset_id),
  KEY idx_cleanup_usage_repository (repository_id, asset_id),
  KEY idx_cleanup_usage_last_downloaded (repository_id, last_downloaded_at, asset_id),
  CONSTRAINT fk_cleanup_usage_asset
    FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE CASCADE,
  CONSTRAINT fk_cleanup_usage_repository
    FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE cleanup_protection (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  scope VARCHAR(16) NOT NULL,
  repository_id BIGINT UNSIGNED NULL,
  subject_kind VARCHAR(32) NULL,
  subject_key VARCHAR(2048) NULL,
  subject_key_hash BINARY(32) NULL,
  source VARCHAR(32) NOT NULL,
  external_id VARCHAR(255) NULL,
  reason VARCHAR(1024) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  expires_at DATETIME(3) NULL,
  freshness_at DATETIME(3) NULL,
  created_by VARCHAR(255) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cleanup_protection_external (source, external_id),
  KEY idx_cleanup_protection_match
    (repository_id, subject_kind, subject_key_hash, enabled, expires_at),
  KEY idx_cleanup_protection_expiry (enabled, expires_at, id),
  CONSTRAINT fk_cleanup_protection_repository
    FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE cleanup_run_item
  ADD COLUMN expected_content_token VARCHAR(128) NULL AFTER estimated_bytes,
  ADD COLUMN expected_usage_revision BIGINT UNSIGNED NOT NULL DEFAULT 0
    AFTER expected_content_token,
  ADD COLUMN protection_id BIGINT UNSIGNED NULL AFTER expected_usage_revision,
  ADD COLUMN evaluated_at DATETIME(3) NULL AFTER protection_id,
  ADD CONSTRAINT fk_cleanup_run_item_protection
    FOREIGN KEY (protection_id) REFERENCES cleanup_protection (id) ON DELETE SET NULL;
