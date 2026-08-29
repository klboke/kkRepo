CREATE TABLE helm_proxy_legacy_cache_fence (
  repository_id BIGINT NOT NULL CHECK (repository_id >= 0),
  configuration_updated_at TIMESTAMPTZ(3) NOT NULL,
  activated_at TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_helm_proxy_legacy_cache_fence PRIMARY KEY (repository_id),
  CONSTRAINT fk_helm_proxy_legacy_cache_fence_repository
    FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

INSERT INTO helm_proxy_legacy_cache_fence
  (repository_id, configuration_updated_at, activated_at)
SELECT id, updated_at, CURRENT_TIMESTAMP
FROM repository
WHERE format = 'helm'
  AND type = 'proxy'
  AND updated_at = created_at;
