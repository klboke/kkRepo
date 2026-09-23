-- Restart-safe online indexes for bounded completion-time and unfinished-task pages.
SET @kkrepo_scan_completion_index_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE security_scan_task ADD INDEX idx_security_scan_task_completion (finished_at, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'security_scan_task'
    AND index_name = 'idx_security_scan_task_completion'
);
PREPARE kkrepo_scan_completion_index_statement FROM @kkrepo_scan_completion_index_sql;
EXECUTE kkrepo_scan_completion_index_statement;
DEALLOCATE PREPARE kkrepo_scan_completion_index_statement;

SET @kkrepo_scan_completion_index_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE security_scan_task ADD INDEX idx_security_scan_task_repo_completion (repository_id, finished_at, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'security_scan_task'
    AND index_name = 'idx_security_scan_task_repo_completion'
);
PREPARE kkrepo_scan_completion_index_statement FROM @kkrepo_scan_completion_index_sql;
EXECUTE kkrepo_scan_completion_index_statement;
DEALLOCATE PREPARE kkrepo_scan_completion_index_statement;

SET @kkrepo_scan_completion_index_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE security_scan_run ADD INDEX idx_security_scan_run_completion (completed_at, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'security_scan_run'
    AND index_name = 'idx_security_scan_run_completion'
);
PREPARE kkrepo_scan_completion_index_statement FROM @kkrepo_scan_completion_index_sql;
EXECUTE kkrepo_scan_completion_index_statement;
DEALLOCATE PREPARE kkrepo_scan_completion_index_statement;


SET @kkrepo_scan_completion_index_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE security_scan_task ADD INDEX idx_security_scan_task_status_completion (status, finished_at, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'security_scan_task'
    AND index_name = 'idx_security_scan_task_status_completion'
);
PREPARE kkrepo_scan_completion_index_statement FROM @kkrepo_scan_completion_index_sql;
EXECUTE kkrepo_scan_completion_index_statement;
DEALLOCATE PREPARE kkrepo_scan_completion_index_statement;

SET @kkrepo_scan_completion_index_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE security_scan_task ADD INDEX idx_security_scan_task_repo_status_completion (repository_id, status, finished_at, id), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'security_scan_task'
    AND index_name = 'idx_security_scan_task_repo_status_completion'
);
PREPARE kkrepo_scan_completion_index_statement FROM @kkrepo_scan_completion_index_sql;
EXECUTE kkrepo_scan_completion_index_statement;
DEALLOCATE PREPARE kkrepo_scan_completion_index_statement;
