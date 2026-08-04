CREATE TABLE cleanup_policy_repository_cursor (
  policy_id BIGINT NOT NULL CHECK (policy_id >= 0),
  repository_id BIGINT NOT NULL CHECK (repository_id >= 0),
  phase VARCHAR(16) NOT NULL,
  component_namespace VARCHAR(512),
  component_name VARCHAR(512),
  component_kind VARCHAR(50),
  subject_id BIGINT NOT NULL DEFAULT 0 CHECK (subject_id >= 0),
  revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
  wrapped_count BIGINT NOT NULL DEFAULT 0 CHECK (wrapped_count >= 0),
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_cleanup_policy_repository_cursor PRIMARY KEY (policy_id, repository_id),
  CONSTRAINT fk_cleanup_policy_repository_cursor_policy
    FOREIGN KEY (policy_id) REFERENCES cleanup_policy (id) ON DELETE CASCADE,
  CONSTRAINT fk_cleanup_policy_repository_cursor_repository
    FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

CREATE INDEX idx_cleanup_policy_repository_cursor_repository
  ON cleanup_policy_repository_cursor (repository_id, policy_id);

ALTER TABLE cleanup_run_repository
  ADD COLUMN scan_cursor_phase VARCHAR(16),
  ADD COLUMN scan_cursor_component_namespace VARCHAR(512),
  ADD COLUMN scan_cursor_component_name VARCHAR(512),
  ADD COLUMN scan_cursor_component_kind VARCHAR(50),
  ADD COLUMN scan_cursor_subject_id BIGINT CHECK (scan_cursor_subject_id >= 0),
  ADD COLUMN scan_cursor_revision BIGINT CHECK (scan_cursor_revision >= 0),
  ADD COLUMN scan_cursor_wrapped_count BIGINT CHECK (scan_cursor_wrapped_count >= 0);

CREATE INDEX idx_cleanup_run_retention
  ON cleanup_run (state, completed_at, id);

CREATE INDEX idx_cleanup_run_repository_operational
  ON cleanup_run_repository (state, created_at, lease_until, id);
