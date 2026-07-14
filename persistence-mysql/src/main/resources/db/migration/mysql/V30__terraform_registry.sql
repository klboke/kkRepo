CREATE TABLE terraform_signing_key (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  repository_id BIGINT UNSIGNED NOT NULL,
  revision INT NOT NULL,
  key_id VARCHAR(32) NOT NULL,
  encrypted_private_key LONGTEXT NOT NULL,
  public_key LONGTEXT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_terraform_signing_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT uk_terraform_signing_revision UNIQUE (repository_id, revision),
  INDEX idx_terraform_signing_active (repository_id, active)
);

CREATE TABLE terraform_provider_signing_state (
  repository_id BIGINT UNSIGNED NOT NULL,
  namespace VARCHAR(128) NOT NULL,
  provider_type VARCHAR(128) NOT NULL,
  version VARCHAR(128) NOT NULL,
  revision BIGINT NOT NULL,
  shasums_path VARCHAR(1024) NOT NULL,
  signature_path VARCHAR(1024) NOT NULL,
  signing_key_revision INT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (repository_id, namespace, provider_type, version),
  CONSTRAINT fk_terraform_state_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE
);

CREATE TABLE terraform_provider_platform (
  repository_id BIGINT UNSIGNED NOT NULL,
  namespace VARCHAR(128) NOT NULL,
  provider_type VARCHAR(128) NOT NULL,
  version VARCHAR(128) NOT NULL,
  os VARCHAR(64) NOT NULL,
  arch VARCHAR(64) NOT NULL,
  filename VARCHAR(255) NOT NULL,
  asset_path VARCHAR(1024) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  protocols VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL,
  revision BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (repository_id, namespace, provider_type, version, os, arch),
  CONSTRAINT fk_terraform_platform_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_terraform_platform_versions (repository_id, namespace, provider_type, version, status)
);

CREATE TABLE terraform_source_binding (
  group_repository_id BIGINT UNSIGNED NOT NULL,
  binding_key VARCHAR(512) NOT NULL,
  member_repository_id BIGINT UNSIGNED NOT NULL,
  member_revision BIGINT NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (group_repository_id, binding_key),
  CONSTRAINT fk_terraform_binding_group FOREIGN KEY (group_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_terraform_binding_member FOREIGN KEY (member_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_terraform_binding_expiry (expires_at)
);

CREATE TABLE terraform_publish_lease (
  lease_key VARCHAR(512) NOT NULL PRIMARY KEY,
  owner VARCHAR(128) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_terraform_lease_expiry (expires_at)
);
