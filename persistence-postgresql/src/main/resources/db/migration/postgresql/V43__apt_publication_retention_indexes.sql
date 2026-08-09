CREATE INDEX idx_apt_tombstone_cleanup
  ON apt_package_tombstone(deleted_at, repository_id, revision);

CREATE INDEX idx_apt_suite_worker
  ON apt_suite_state(desired_at, last_error_at, repository_id)
  WHERE desired_revision > published_revision;

CREATE INDEX idx_apt_snapshot_cleanup
  ON apt_snapshot(published_at, created_at, repository_id, distribution_name)
  WHERE published_at IS NOT NULL;
