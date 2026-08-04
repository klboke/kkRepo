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

ALTER TABLE cleanup_run_repository
  ADD COLUMN scan_cursor_phase VARCHAR(16) NULL AFTER scan_budget,
  ADD COLUMN scan_cursor_component_namespace VARCHAR(512) NULL AFTER scan_cursor_phase,
  ADD COLUMN scan_cursor_component_name VARCHAR(512) NULL
    AFTER scan_cursor_component_namespace,
  ADD COLUMN scan_cursor_component_kind VARCHAR(50) NULL AFTER scan_cursor_component_name,
  ADD COLUMN scan_cursor_subject_id BIGINT UNSIGNED NULL AFTER scan_cursor_component_kind,
  ADD COLUMN scan_cursor_revision BIGINT UNSIGNED NULL AFTER scan_cursor_subject_id,
  ADD COLUMN scan_cursor_wrapped_count BIGINT UNSIGNED NULL AFTER scan_cursor_revision;

CREATE INDEX idx_cleanup_run_retention
  ON cleanup_run (state, completed_at, id);

CREATE INDEX idx_cleanup_run_repository_operational
  ON cleanup_run_repository (state, created_at, lease_until, id);
