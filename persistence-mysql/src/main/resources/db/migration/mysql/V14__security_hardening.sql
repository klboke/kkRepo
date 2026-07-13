CREATE TABLE IF NOT EXISTS security_audit_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  actor_source VARCHAR(100) NULL,
  actor_user_id VARCHAR(255) NULL,
  actor_realm_id VARCHAR(100) NULL,
  actor_api_key_id BIGINT UNSIGNED NULL,
  remote_addr VARCHAR(100) NULL,
  method VARCHAR(16) NOT NULL,
  path VARCHAR(2048) NOT NULL,
  permission VARCHAR(512) NULL,
  status INT NULL,
  outcome VARCHAR(50) NOT NULL,
  details_json JSON NULL,
  PRIMARY KEY (id),
  KEY idx_security_audit_log_occurred_at (occurred_at),
  KEY idx_security_audit_log_actor (actor_source, actor_user_id),
  KEY idx_security_audit_log_path (path(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
