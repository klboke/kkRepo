CREATE TABLE helm_proxy_legacy_cache_fence (
  repository_id BIGINT UNSIGNED NOT NULL,
  configuration_updated_at DATETIME(3) NOT NULL,
  activated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (repository_id),
  CONSTRAINT fk_helm_proxy_legacy_cache_fence_repository
    FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO helm_proxy_legacy_cache_fence
  (repository_id, configuration_updated_at, activated_at)
SELECT id, updated_at, CURRENT_TIMESTAMP(3)
FROM repository
WHERE format = 'helm'
  AND type = 'proxy'
  AND updated_at = created_at;
