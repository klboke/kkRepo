CREATE TABLE kkg_organization (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  external_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(1024) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kkg_organization_external_id (external_id),
  UNIQUE KEY uk_kkg_organization_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkg_application (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  organization_id BIGINT UNSIGNED NOT NULL,
  external_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  public_id VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  name VARCHAR(255) NOT NULL,
  repository_id BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kkg_application_external_id (external_id),
  UNIQUE KEY uk_kkg_application_public_id (organization_id, public_id),
  INDEX idx_kkg_application_organization (organization_id),
  INDEX idx_kkg_application_repository (repository_id),
  CONSTRAINT fk_kkg_application_organization
    FOREIGN KEY (organization_id) REFERENCES kkg_organization(id) ON DELETE CASCADE,
  CONSTRAINT fk_kkg_application_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkg_scan (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  application_id BIGINT UNSIGNED NOT NULL,
  scan_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  stage VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  source_id VARCHAR(255) NULL,
  sbom_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  report_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  component_count INT NOT NULL DEFAULT 0,
  violation_count INT NOT NULL DEFAULT 0,
  error TEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  started_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kkg_scan_scan_id (scan_id),
  INDEX idx_kkg_scan_application (application_id, created_at),
  INDEX idx_kkg_scan_status (status, created_at),
  CONSTRAINT fk_kkg_scan_application
    FOREIGN KEY (application_id) REFERENCES kkg_application(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkg_scan_component (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  scan_id BIGINT UNSIGNED NOT NULL,
  component_ref VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  purl VARCHAR(1024) NULL,
  name VARCHAR(512) NULL,
  version VARCHAR(255) NULL,
  license VARCHAR(512) NULL,
  highest_severity VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (id),
  INDEX idx_kkg_scan_component_scan (scan_id, component_ref(191)),
  CONSTRAINT fk_kkg_scan_component_scan
    FOREIGN KEY (scan_id) REFERENCES kkg_scan(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkg_scan_finding (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  scan_id BIGINT UNSIGNED NOT NULL,
  component_ref VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  vulnerability_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  severity VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  cvss_score DECIMAL(4,1) NULL,
  source VARCHAR(128) NULL,
  description TEXT NULL,
  url VARCHAR(1024) NULL,
  PRIMARY KEY (id),
  INDEX idx_kkg_scan_finding_scan (scan_id, vulnerability_id),
  INDEX idx_kkg_scan_finding_component (scan_id, component_ref(191)),
  CONSTRAINT fk_kkg_scan_finding_scan
    FOREIGN KEY (scan_id) REFERENCES kkg_scan(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkg_policy (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  organization_id BIGINT UNSIGNED NOT NULL,
  external_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  public_id VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  name VARCHAR(255) NOT NULL,
  threat_level INT NOT NULL DEFAULT 5,
  constraints_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kkg_policy_external_id (external_id),
  UNIQUE KEY uk_kkg_policy_public_id (organization_id, public_id),
  CONSTRAINT fk_kkg_policy_organization
    FOREIGN KEY (organization_id) REFERENCES kkg_organization(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkg_violation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  scan_id BIGINT UNSIGNED NOT NULL,
  policy_id BIGINT UNSIGNED NOT NULL,
  component_ref VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  threat_level INT NOT NULL,
  constraint_json JSON NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_kkg_violation_scan (scan_id, policy_id),
  CONSTRAINT fk_kkg_violation_scan
    FOREIGN KEY (scan_id) REFERENCES kkg_scan(id) ON DELETE CASCADE,
  CONSTRAINT fk_kkg_violation_policy
    FOREIGN KEY (policy_id) REFERENCES kkg_policy(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
