ALTER TABLE cleanup_run
  ADD COLUMN would_delete_subjects BIGINT NOT NULL DEFAULT 0
    CHECK (would_delete_subjects >= 0);

ALTER TABLE cleanup_run_repository
  ADD COLUMN would_delete_subjects BIGINT NOT NULL DEFAULT 0
    CHECK (would_delete_subjects >= 0);
