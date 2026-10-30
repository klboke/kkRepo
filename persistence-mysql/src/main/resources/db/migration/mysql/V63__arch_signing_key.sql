CREATE TABLE arch_signing_key (
  repository_id BIGINT UNSIGNED NOT NULL,
  revision INT NOT NULL,
  key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  encrypted_private_key MEDIUMTEXT NOT NULL,
  public_key MEDIUMTEXT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  active_slot TINYINT
    GENERATED ALWAYS AS (CASE WHEN active THEN 1 ELSE NULL END) STORED,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (repository_id, revision),
  CONSTRAINT uk_arch_signing_active UNIQUE (repository_id, active_slot),
  CONSTRAINT fk_arch_signing_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_arch_signing_active (repository_id, active, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
