ALTER TABLE cleanup_run
  ADD COLUMN would_delete_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0
    AFTER matched_subjects;

ALTER TABLE cleanup_run_repository
  ADD COLUMN would_delete_subjects BIGINT UNSIGNED NOT NULL DEFAULT 0
    AFTER matched_subjects;
