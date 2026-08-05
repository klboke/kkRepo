ALTER TABLE cleanup_policy
  ADD COLUMN revision BIGINT UNSIGNED NOT NULL DEFAULT 1 AFTER criteria_json,
  ADD COLUMN state VARCHAR(32) NOT NULL DEFAULT 'PAUSED' AFTER revision,
  ADD COLUMN scan_limit_per_repository INT UNSIGNED NOT NULL DEFAULT 1000 AFTER state,
  ADD COLUMN delete_limit_per_repository INT UNSIGNED NOT NULL DEFAULT 100 AFTER scan_limit_per_repository;

ALTER TABLE cleanup_policy
  DROP INDEX uk_cleanup_policy_name,
  ADD COLUMN active_name VARCHAR(200)
    GENERATED ALWAYS AS (CASE WHEN state = 'DELETED' THEN NULL ELSE name END) STORED,
  ADD UNIQUE KEY uk_cleanup_policy_active_name (active_name);

ALTER TABLE repository_cleanup_policy
  DROP FOREIGN KEY fk_repository_cleanup_policy_repository;

ALTER TABLE repository_cleanup_policy
  ADD CONSTRAINT fk_repository_cleanup_policy_repository
  FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE RESTRICT;

CREATE TABLE cleanup_policy_schedule (
  policy_id BIGINT UNSIGNED NOT NULL,
  cron_expression VARCHAR(120) NOT NULL,
  time_zone VARCHAR(64) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (policy_id),
  CONSTRAINT fk_cleanup_policy_schedule_policy
    FOREIGN KEY (policy_id) REFERENCES cleanup_policy (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE cleanup_run (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  policy_id BIGINT UNSIGNED NOT NULL,
  policy_revision BIGINT UNSIGNED NOT NULL,
  mode VARCHAR(16) NOT NULL,
  trigger_kind VARCHAR(16) NOT NULL,
  state VARCHAR(32) NOT NULL,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  requested_by VARCHAR(255) NOT NULL,
  scheduled_for DATETIME(3) NULL,
  scan_limit_per_repository INT UNSIGNED NOT NULL,
  delete_limit_per_repository INT UNSIGNED NOT NULL,
  criteria_snapshot_json JSON NOT NULL,
  repository_snapshot_json JSON NOT NULL,
  scanned_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  matched_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  would_delete_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  deleted_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  failed_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  truncated_repositories INT UNSIGNED NOT NULL DEFAULT 0,
  error_summary VARCHAR(2048) NULL,
  started_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  cancelled_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cleanup_run_scheduled_fire (policy_id, scheduled_for),
  KEY idx_cleanup_run_policy (policy_id, id),
  KEY idx_cleanup_run_state (state, id),
  KEY idx_cleanup_run_retention (state, completed_at, id),
  CONSTRAINT fk_cleanup_run_policy
    FOREIGN KEY (policy_id) REFERENCES cleanup_policy (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE cleanup_run_repository (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  run_id BIGINT UNSIGNED NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  repository_name VARCHAR(200) NOT NULL,
  format VARCHAR(50) NOT NULL,
  repository_type VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL,
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  max_attempts INT UNSIGNED NOT NULL DEFAULT 3,
  scan_budget INT UNSIGNED NULL,
  scan_cursor_phase VARCHAR(16) NULL,
  scan_cursor_component_namespace VARCHAR(512) NULL,
  scan_cursor_component_name VARCHAR(512) NULL,
  scan_cursor_component_kind VARCHAR(50) NULL,
  scan_cursor_subject_id BIGINT UNSIGNED NULL,
  scan_cursor_revision BIGINT UNSIGNED NULL,
  scan_cursor_wrapped_count BIGINT UNSIGNED NULL,
  next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  lease_owner VARCHAR(255) NULL,
  lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until DATETIME(3) NULL,
  last_heartbeat_at DATETIME(3) NULL,
  fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
  scanned_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  matched_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  would_delete_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  deleted_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  failed_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  truncated BOOLEAN NOT NULL DEFAULT FALSE,
  error_summary VARCHAR(2048) NULL,
  last_error_code VARCHAR(64) NULL,
  started_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cleanup_run_repository (run_id, repository_id),
  KEY idx_cleanup_run_repository_run (run_id, id),
  KEY idx_cleanup_run_repository_claim (state, next_attempt_at, lease_until, id),
  KEY idx_cleanup_run_repository_lease (lease_until, id),
  KEY idx_cleanup_run_repository_repository_claim
    (repository_id, id, state, next_attempt_at),
  KEY idx_cleanup_run_repository_operational (state, created_at, lease_until, id),
  CONSTRAINT fk_cleanup_run_repository_run
    FOREIGN KEY (run_id) REFERENCES cleanup_run (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

CREATE TABLE cleanup_run_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  run_repository_id BIGINT UNSIGNED NOT NULL,
  subject_kind VARCHAR(32) NOT NULL,
  subject_key VARCHAR(2048) NOT NULL,
  subject_key_hash BINARY(32) NOT NULL,
  family_key TEXT NULL,
  display_name TEXT NOT NULL,
  version VARCHAR(255) NULL,
  delete_path TEXT NULL,
  last_downloaded_at DATETIME(3) NULL,
  published_at DATETIME(3) NULL,
  asset_count INT UNSIGNED NOT NULL DEFAULT 0,
  estimated_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0,
  expected_content_token VARCHAR(128) NULL,
  expected_usage_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
  protection_id BIGINT UNSIGNED NULL,
  evaluated_at DATETIME(3) NULL,
  decision VARCHAR(32) NOT NULL,
  reason_json JSON NOT NULL,
  error_summary VARCHAR(2048) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cleanup_run_item_subject (run_repository_id, subject_kind, subject_key_hash),
  KEY idx_cleanup_run_item_repository (run_repository_id, id),
  CONSTRAINT fk_cleanup_run_item_repository
    FOREIGN KEY (run_repository_id) REFERENCES cleanup_run_repository (id) ON DELETE CASCADE,
  CONSTRAINT fk_cleanup_run_item_protection
    FOREIGN KEY (protection_id) REFERENCES cleanup_protection (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE cleanup_policy_repository_cursor (
  policy_id BIGINT UNSIGNED NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  phase VARCHAR(16) NOT NULL,
  component_namespace VARCHAR(512) NULL,
  component_name VARCHAR(512) NULL,
  component_kind VARCHAR(50) NULL,
  subject_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
  wrapped_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (policy_id, repository_id),
  KEY idx_cleanup_policy_repository_cursor_repository (repository_id, policy_id),
  CONSTRAINT fk_cleanup_policy_repository_cursor_policy
    FOREIGN KEY (policy_id) REFERENCES cleanup_policy (id) ON DELETE CASCADE,
  CONSTRAINT fk_cleanup_policy_repository_cursor_repository
    FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO cache_version (name, version, updated_at)
VALUES ('cleanup-usage-tracking', 0, CURRENT_TIMESTAMP(3));
