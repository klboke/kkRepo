CREATE TABLE IF NOT EXISTS winget_manifest (
  id BIGINT NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  package_identifier VARCHAR(128) NOT NULL,
  package_version VARCHAR(128) NOT NULL,
  channel VARCHAR(128) NOT NULL DEFAULT '',
  default_locale VARCHAR(64) NOT NULL,
  package_name VARCHAR(512) NOT NULL,
  publisher VARCHAR(512) NOT NULL,
  moniker VARCHAR(128) NOT NULL DEFAULT '',
  search_text TEXT NOT NULL,
  summary_json TEXT NOT NULL,
  manifest_json LONGTEXT NOT NULL,
  manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_winget_manifest_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT uk_winget_manifest_version
    UNIQUE (repository_id, package_identifier, package_version, channel),
  INDEX idx_winget_manifest_package (repository_id, package_identifier),
  INDEX idx_winget_manifest_updated (repository_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
