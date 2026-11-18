CREATE TABLE IF NOT EXISTS ohpm_login_key (
  publish_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_numeric_id BIGINT UNSIGNED NOT NULL,
  public_key TEXT NOT NULL,
  fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (publish_id),
  UNIQUE KEY uk_ohpm_login_owner (user_numeric_id),
  CONSTRAINT fk_ohpm_login_user FOREIGN KEY (user_numeric_id) REFERENCES security_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ohpm_login_nonce (
  publish_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  nonce CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY (publish_id, nonce),
  CONSTRAINT fk_ohpm_nonce_key FOREIGN KEY (publish_id) REFERENCES ohpm_login_key(publish_id) ON DELETE CASCADE,
  INDEX idx_ohpm_nonce_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
