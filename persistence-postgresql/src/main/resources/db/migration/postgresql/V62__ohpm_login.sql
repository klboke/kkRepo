CREATE TABLE ohpm_login_key (
  publish_id CHAR(32) NOT NULL,
  user_numeric_id BIGINT NOT NULL,
  public_key TEXT NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (publish_id),
  CONSTRAINT uk_ohpm_login_owner UNIQUE (user_numeric_id),
  CONSTRAINT fk_ohpm_login_user FOREIGN KEY (user_numeric_id) REFERENCES security_user(id) ON DELETE CASCADE
);

CREATE TABLE ohpm_login_nonce (
  publish_id CHAR(32) NOT NULL,
  nonce CHAR(32) NOT NULL,
  expires_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (publish_id, nonce),
  CONSTRAINT fk_ohpm_nonce_key FOREIGN KEY (publish_id) REFERENCES ohpm_login_key(publish_id) ON DELETE CASCADE
);

CREATE INDEX idx_ohpm_nonce_expiry ON ohpm_login_nonce (expires_at);
