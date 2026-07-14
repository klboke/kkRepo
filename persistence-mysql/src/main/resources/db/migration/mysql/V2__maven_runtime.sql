-- kkrepo V2: Maven runtime tables and indexes.
-- Adds DB-backed circuit breaker for proxy repositories and a queue marker
-- for metadata rebuilds. Round 1 rebuilds inline; the marker is reserved for
-- a future async rebuilder.

CREATE TABLE proxy_remote_state (
  repository_id BIGINT UNSIGNED NOT NULL,
  blocked_until DATETIME(3) NULL,
  fail_count INT NOT NULL DEFAULT 0,
  last_success_at DATETIME(3) NULL,
  last_failure_at DATETIME(3) NULL,
  last_error VARCHAR(1024) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id),
  CONSTRAINT fk_proxy_remote_state_repository FOREIGN KEY (repository_id)
    REFERENCES repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE metadata_rebuild_marker (
  repository_id BIGINT UNSIGNED NOT NULL,
  scope_key VARCHAR(512) NOT NULL,
  requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, scope_key),
  CONSTRAINT fk_metadata_rebuild_marker_repository FOREIGN KEY (repository_id)
    REFERENCES repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE asset
  ADD INDEX idx_asset_repository_kind (repository_id, kind);
