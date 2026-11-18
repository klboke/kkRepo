CREATE TABLE ohpm_fetch_lease (
  lease_key CHAR(64) NOT NULL PRIMARY KEY,
  owner VARCHAR(36) NOT NULL,
  fencing_token BIGINT NOT NULL,
  expires_at TIMESTAMP(3) NOT NULL
);
