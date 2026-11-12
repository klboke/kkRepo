CREATE TABLE ohpm_fetch_lease (
  lease_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  owner VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fencing_token BIGINT NOT NULL,
  expires_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
