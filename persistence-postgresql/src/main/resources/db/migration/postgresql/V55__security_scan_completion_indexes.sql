-- Concurrent builds preserve writes and can recover an interrupted invalid index.
DROP INDEX CONCURRENTLY IF EXISTS idx_security_scan_task_completion;
CREATE INDEX CONCURRENTLY idx_security_scan_task_completion ON security_scan_task (finished_at, id);
DROP INDEX CONCURRENTLY IF EXISTS idx_security_scan_task_repo_completion;
CREATE INDEX CONCURRENTLY idx_security_scan_task_repo_completion ON security_scan_task (repository_id, finished_at, id);
DROP INDEX CONCURRENTLY IF EXISTS idx_security_scan_run_completion;
CREATE INDEX CONCURRENTLY idx_security_scan_run_completion ON security_scan_run (completed_at, id);

DROP INDEX CONCURRENTLY IF EXISTS idx_security_scan_task_status_completion;
CREATE INDEX CONCURRENTLY idx_security_scan_task_status_completion ON security_scan_task (status, finished_at, id);

DROP INDEX CONCURRENTLY IF EXISTS idx_security_scan_task_repo_status_completion;
CREATE INDEX CONCURRENTLY idx_security_scan_task_repo_status_completion ON security_scan_task (repository_id, status, finished_at, id);
