-- Keep repository authorization and newest-first pagination indexable at large component counts.
-- Replacing the three prefix-equivalent indexes avoids permanent duplicate write amplification.
ALTER TABLE component
  DROP INDEX idx_component_last_updated,
  DROP INDEX idx_component_format_last_updated,
  DROP INDEX idx_component_repo_format_updated,
  ADD INDEX idx_component_last_updated
    (last_updated_at, id, repository_id, format),
  ADD INDEX idx_component_format_last_updated
    (format, last_updated_at, id, repository_id),
  ADD INDEX idx_component_repo_format_updated
    (repository_id, format, last_updated_at, id),
  ADD INDEX idx_component_repo_last_updated
    (repository_id, last_updated_at, id),
  ALGORITHM=INPLACE,
  LOCK=NONE;
