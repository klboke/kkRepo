-- Nexus-compatible security management model.
-- V1 already captured the core user/role/privilege facts. This migration adds
-- management metadata and the remaining structures needed for repository
-- target, realm-priority, and API-key administration.

ALTER TABLE security_role
  ADD COLUMN source VARCHAR(100) NOT NULL DEFAULT 'default' AFTER role_id;

ALTER TABLE security_privilege
  ADD COLUMN read_only TINYINT(1) NOT NULL DEFAULT 0 AFTER type;

ALTER TABLE api_key
  ADD COLUMN display_name VARCHAR(255) NULL AFTER owner_user_id,
  ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE' AFTER display_name,
  ADD COLUMN token_prefix VARCHAR(32) NULL AFTER api_key_hash,
  ADD COLUMN scopes_json JSON NULL AFTER token_prefix,
  ADD COLUMN expires_at DATETIME(3) NULL AFTER encrypted_payload,
  ADD COLUMN last_used_at DATETIME(3) NULL AFTER expires_at;

ALTER TABLE api_key
  DROP INDEX uk_api_key_hash,
  ADD KEY idx_api_key_hash (api_key_hash),
  ADD UNIQUE KEY uk_api_key_domain_hash (domain, api_key_hash);

CREATE TABLE security_realm (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  realm_id VARCHAR(100) NOT NULL,
  type VARCHAR(50) NOT NULL,
  name VARCHAR(255) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 0,
  priority INT NOT NULL DEFAULT 0,
  attributes_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_security_realm_id (realm_id),
  KEY idx_security_realm_enabled_priority (enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_repository_target (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  target_id VARCHAR(255) NOT NULL,
  name VARCHAR(255) NOT NULL,
  format VARCHAR(50) NOT NULL,
  content_expression TEXT NULL,
  path_patterns_json JSON NOT NULL,
  attributes_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_security_repository_target_id (target_id),
  KEY idx_security_repository_target_format (format)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO security_realm
  (realm_id, type, name, enabled, priority, attributes_json)
VALUES
  ('local', 'LOCAL', 'Local', 1, 0, JSON_OBJECT('source', 'default', 'nexusRealm', 'NexusAuthenticatingRealm')),
  ('ldap', 'LDAP', 'LDAP', 0, 10, JSON_OBJECT('source', 'LDAP', 'nexusRealm', 'LdapRealm')),
  ('oidc', 'OIDC', 'OIDC', 0, 20, JSON_OBJECT('source', 'OIDC', 'nexusRealm', 'OidcRealm'))
ON DUPLICATE KEY UPDATE
  type = VALUES(type),
  name = VALUES(name),
  attributes_json = JSON_MERGE_PATCH(attributes_json, VALUES(attributes_json));

INSERT INTO security_realm_config (id, realms_json)
VALUES (1, JSON_OBJECT('realms', JSON_ARRAY('local')))
ON DUPLICATE KEY UPDATE realms_json = realms_json;

INSERT INTO security_privilege
  (privilege_id, name, description, type, read_only, properties_json)
VALUES
  ('nx-all', 'nx-all', 'All Nexus permissions', 'wildcard', 1, JSON_OBJECT('pattern', 'nexus:*'))
ON DUPLICATE KEY UPDATE privilege_id = privilege_id;

INSERT INTO security_role
  (role_id, source, name, description, read_only, attributes_json)
VALUES
  ('nx-admin', 'default', 'nx-admin', 'Administrator role with all Nexus privileges', 1, JSON_OBJECT())
ON DUPLICATE KEY UPDATE role_id = role_id;

INSERT IGNORE INTO security_role_privilege (role_id, privilege_id)
VALUES ('nx-admin', 'nx-all');
