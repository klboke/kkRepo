CREATE TABLE helm_group_invalidation_marker (
  repository_id BIGINT UNSIGNED NOT NULL,
  invalidation_kind VARCHAR(50) NOT NULL,
  requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  request_token VARCHAR(36) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_attempted_at DATETIME(3) NULL,
  last_error TEXT NULL,
  PRIMARY KEY (repository_id, invalidation_kind),
  KEY idx_helm_group_invalidation_requested_at (requested_at),
  CONSTRAINT fk_helm_group_invalidation_repository
    FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
