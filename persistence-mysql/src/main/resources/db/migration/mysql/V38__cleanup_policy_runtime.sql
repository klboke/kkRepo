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
  requested_by VARCHAR(255) NOT NULL,
  scheduled_for DATETIME(3) NULL,
  scan_limit_per_repository INT UNSIGNED NOT NULL,
  delete_limit_per_repository INT UNSIGNED NOT NULL,
  criteria_snapshot_json JSON NOT NULL,
  repository_snapshot_json JSON NOT NULL,
  scanned_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  matched_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  deleted_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  failed_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  truncated_repositories INT UNSIGNED NOT NULL DEFAULT 0,
  error_summary VARCHAR(2048) NULL,
  started_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cleanup_run_scheduled_fire (policy_id, scheduled_for),
  KEY idx_cleanup_run_policy (policy_id, id),
  KEY idx_cleanup_run_state (state, id),
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
  scanned_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  matched_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  deleted_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  failed_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0,
  truncated BOOLEAN NOT NULL DEFAULT FALSE,
  error_summary VARCHAR(2048) NULL,
  started_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cleanup_run_repository (run_id, repository_id),
  KEY idx_cleanup_run_repository_run (run_id, id),
  CONSTRAINT fk_cleanup_run_repository_run
    FOREIGN KEY (run_id) REFERENCES cleanup_run (id) ON DELETE CASCADE
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
  decision VARCHAR(32) NOT NULL,
  reason_json JSON NOT NULL,
  error_summary VARCHAR(2048) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cleanup_run_item_subject (run_repository_id, subject_kind, subject_key_hash),
  KEY idx_cleanup_run_item_repository (run_repository_id, id),
  CONSTRAINT fk_cleanup_run_item_repository
    FOREIGN KEY (run_repository_id) REFERENCES cleanup_run_repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
