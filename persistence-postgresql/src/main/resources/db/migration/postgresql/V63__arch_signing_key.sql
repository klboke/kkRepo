CREATE TABLE arch_signing_key (
  repository_id BIGINT NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
  revision INTEGER NOT NULL,
  key_id VARCHAR(32) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  encrypted_private_key TEXT NOT NULL,
  public_key TEXT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ(3) NOT NULL,
  PRIMARY KEY (repository_id, revision)
);
CREATE INDEX idx_arch_signing_active ON arch_signing_key(repository_id, active, revision);
CREATE UNIQUE INDEX uk_arch_signing_active
  ON arch_signing_key(repository_id) WHERE active;
