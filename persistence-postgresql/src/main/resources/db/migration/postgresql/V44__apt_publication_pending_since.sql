ALTER TABLE apt_suite_state
  ADD COLUMN pending_since TIMESTAMPTZ(3);

UPDATE apt_suite_state
SET pending_since = desired_at
WHERE desired_revision > published_revision;

CREATE INDEX idx_apt_suite_force_publish
  ON apt_suite_state(pending_since, repository_id)
  WHERE desired_revision > published_revision;
