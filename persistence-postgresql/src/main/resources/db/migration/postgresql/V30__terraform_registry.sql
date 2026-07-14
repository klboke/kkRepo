CREATE TABLE terraform_signing_key (
  id BIGSERIAL PRIMARY KEY,
  repository_id BIGINT NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
  revision INTEGER NOT NULL,
  key_id VARCHAR(32) NOT NULL,
  encrypted_private_key TEXT NOT NULL,
  public_key TEXT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_terraform_signing_revision UNIQUE (repository_id, revision)
);
CREATE INDEX idx_terraform_signing_active ON terraform_signing_key(repository_id, active);

CREATE TABLE terraform_provider_signing_state (
  repository_id BIGINT NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
  namespace VARCHAR(128) NOT NULL,
  provider_type VARCHAR(128) NOT NULL,
  version VARCHAR(128) NOT NULL,
  revision BIGINT NOT NULL,
  shasums_path VARCHAR(1024) NOT NULL,
  signature_path VARCHAR(1024) NOT NULL,
  signing_key_revision INTEGER NOT NULL,
  updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (repository_id, namespace, provider_type, version)
);

CREATE TABLE terraform_provider_platform (
  repository_id BIGINT NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
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
  updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (repository_id, namespace, provider_type, version, os, arch)
);
CREATE INDEX idx_terraform_platform_versions
  ON terraform_provider_platform(repository_id, namespace, provider_type, version, status);

CREATE TABLE terraform_source_binding (
  group_repository_id BIGINT NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
  binding_key VARCHAR(512) NOT NULL,
  member_repository_id BIGINT NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
  member_revision BIGINT NOT NULL,
  expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (group_repository_id, binding_key)
);
CREATE INDEX idx_terraform_binding_expiry ON terraform_source_binding(expires_at);

CREATE TABLE terraform_publish_lease (
  lease_key VARCHAR(512) PRIMARY KEY,
  owner VARCHAR(128) NOT NULL,
  expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_terraform_lease_expiry ON terraform_publish_lease(expires_at);
