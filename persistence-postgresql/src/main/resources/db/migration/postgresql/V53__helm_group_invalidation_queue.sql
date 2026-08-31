CREATE TABLE helm_group_invalidation_marker (
  repository_id BIGINT NOT NULL CHECK (repository_id >= 0),
  invalidation_kind VARCHAR(50) NOT NULL,
  requested_at TIMESTAMPTZ(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
  request_token VARCHAR(36) NOT NULL,
  attempts INTEGER DEFAULT 0 NOT NULL,
  last_attempted_at TIMESTAMPTZ(3),
  last_error TEXT,
  CONSTRAINT pk_helm_group_invalidation_marker
    PRIMARY KEY (repository_id, invalidation_kind),
  CONSTRAINT fk_helm_group_invalidation_repository
    FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
);

CREATE INDEX idx_helm_group_invalidation_requested_at
  ON helm_group_invalidation_marker (requested_at);
