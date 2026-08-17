-- Build replacements before swapping names so component writes remain available throughout the
-- potentially long index scans. The _v2 names also make a repaired Flyway run deterministic.
DROP INDEX CONCURRENTLY IF EXISTS idx_component_last_updated_v2;
CREATE INDEX CONCURRENTLY idx_component_last_updated_v2
  ON component (last_updated_at DESC NULLS LAST, id DESC, repository_id, format);

DROP INDEX CONCURRENTLY IF EXISTS idx_component_format_last_updated_v2;
CREATE INDEX CONCURRENTLY idx_component_format_last_updated_v2
  ON component (format, last_updated_at DESC NULLS LAST, id DESC, repository_id);

DROP INDEX CONCURRENTLY IF EXISTS idx_component_repo_format_updated_v2;
CREATE INDEX CONCURRENTLY idx_component_repo_format_updated_v2
  ON component (repository_id, format, last_updated_at DESC NULLS LAST, id DESC);

DROP INDEX CONCURRENTLY IF EXISTS idx_component_repo_last_updated;
CREATE INDEX CONCURRENTLY idx_component_repo_last_updated
  ON component (repository_id, last_updated_at DESC NULLS LAST, id DESC);

DROP INDEX CONCURRENTLY IF EXISTS idx_component_last_updated;
ALTER INDEX idx_component_last_updated_v2 RENAME TO idx_component_last_updated;

DROP INDEX CONCURRENTLY IF EXISTS idx_component_format_last_updated;
ALTER INDEX idx_component_format_last_updated_v2 RENAME TO idx_component_format_last_updated;

DROP INDEX CONCURRENTLY IF EXISTS idx_component_repo_format_updated;
ALTER INDEX idx_component_repo_format_updated_v2 RENAME TO idx_component_repo_format_updated;
