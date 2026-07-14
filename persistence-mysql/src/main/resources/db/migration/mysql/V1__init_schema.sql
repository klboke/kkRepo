-- kkrepo initial MySQL schema.
-- Target database: MySQL 8, InnoDB, utf8mb4.
-- This file is intentionally only stored as a resource for now. Flyway is not
-- wired into the server startup yet, so local UI preview does not require a DB.

CREATE TABLE blob_store (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL,
  type VARCHAR(50) NOT NULL,
  endpoint VARCHAR(512) NULL,
  region VARCHAR(100) NULL,
  bucket VARCHAR(255) NULL,
  prefix VARCHAR(512) NULL,
  attributes_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_blob_store_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE routing_rule (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL,
  mode VARCHAR(32) NOT NULL,
  description VARCHAR(1024) NULL,
  matchers_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_routing_rule_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE cleanup_policy (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL,
  format VARCHAR(50) NOT NULL,
  mode VARCHAR(50) NOT NULL,
  notes VARCHAR(2048) NULL,
  criteria_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cleanup_policy_name (name),
  KEY idx_cleanup_policy_format (format)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE content_selector (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL,
  type VARCHAR(50) NOT NULL,
  description VARCHAR(1024) NULL,
  attributes_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_content_selector_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE repository (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL,
  format VARCHAR(50) NOT NULL,
  type VARCHAR(50) NOT NULL,
  recipe_name VARCHAR(100) NOT NULL,
  online TINYINT(1) NOT NULL DEFAULT 1,
  blob_store_id BIGINT UNSIGNED NULL,
  routing_rule_id BIGINT UNSIGNED NULL,
  proxy_remote_url VARCHAR(1024) NULL,
  version_policy VARCHAR(50) NULL,
  layout_policy VARCHAR(50) NULL,
  write_policy VARCHAR(50) NULL,
  strict_content_type_validation TINYINT(1) NOT NULL DEFAULT 1,
  attributes_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_repository_name (name),
  KEY idx_repository_format_type (format, type),
  KEY idx_repository_blob_store (blob_store_id),
  KEY idx_repository_routing_rule (routing_rule_id),
  CONSTRAINT fk_repository_blob_store FOREIGN KEY (blob_store_id) REFERENCES blob_store (id) ON DELETE RESTRICT,
  CONSTRAINT fk_repository_routing_rule FOREIGN KEY (routing_rule_id) REFERENCES routing_rule (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE repository_member (
  repository_id BIGINT UNSIGNED NOT NULL,
  member_repository_id BIGINT UNSIGNED NOT NULL,
  sort_order INT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, member_repository_id),
  UNIQUE KEY uk_repository_member_order (repository_id, sort_order),
  KEY idx_repository_member_target (member_repository_id),
  CONSTRAINT fk_repository_member_group FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  CONSTRAINT fk_repository_member_target FOREIGN KEY (member_repository_id) REFERENCES repository (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE repository_cleanup_policy (
  repository_id BIGINT UNSIGNED NOT NULL,
  cleanup_policy_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, cleanup_policy_id),
  KEY idx_repository_cleanup_policy_policy (cleanup_policy_id),
  CONSTRAINT fk_repository_cleanup_policy_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  CONSTRAINT fk_repository_cleanup_policy_policy FOREIGN KEY (cleanup_policy_id) REFERENCES cleanup_policy (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE component (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  format VARCHAR(50) NOT NULL,
  namespace VARCHAR(512) NULL,
  name VARCHAR(512) NOT NULL,
  version VARCHAR(255) NULL,
  kind VARCHAR(50) NULL,
  coordinate_hash BINARY(32) NOT NULL,
  attributes_json JSON NOT NULL,
  last_updated_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_component_coordinate (repository_id, coordinate_hash),
  KEY idx_component_repository_format (repository_id, format),
  KEY idx_component_name_version (format, name(191), version),
  CONSTRAINT fk_component_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE asset_blob (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  blob_store_id BIGINT UNSIGNED NOT NULL,
  blob_ref VARCHAR(1024) NOT NULL,
  blob_ref_hash BINARY(32) NOT NULL,
  object_key VARCHAR(2048) NOT NULL,
  object_key_hash BINARY(32) NOT NULL,
  sha1 CHAR(40) NULL,
  sha256 CHAR(64) NULL,
  md5 CHAR(32) NULL,
  size BIGINT UNSIGNED NOT NULL,
  content_type VARCHAR(255) NULL,
  created_by VARCHAR(255) NULL,
  created_by_ip VARCHAR(64) NULL,
  blob_created_at DATETIME(3) NULL,
  blob_updated_at DATETIME(3) NULL,
  attributes_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_asset_blob_ref (blob_store_id, blob_ref_hash),
  UNIQUE KEY uk_asset_blob_object (blob_store_id, object_key_hash),
  KEY idx_asset_blob_checksum_sha1 (sha1),
  KEY idx_asset_blob_checksum_sha256 (sha256),
  CONSTRAINT fk_asset_blob_blob_store FOREIGN KEY (blob_store_id) REFERENCES blob_store (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE asset (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  component_id BIGINT UNSIGNED NULL,
  asset_blob_id BIGINT UNSIGNED NULL,
  format VARCHAR(50) NOT NULL,
  path VARCHAR(2048) NOT NULL,
  path_hash BINARY(32) NOT NULL,
  name VARCHAR(512) NOT NULL,
  kind VARCHAR(50) NULL,
  content_type VARCHAR(255) NULL,
  size BIGINT UNSIGNED NULL,
  last_downloaded_at DATETIME(3) NULL,
  last_updated_at DATETIME(3) NULL,
  attributes_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_asset_path (repository_id, path_hash),
  KEY idx_asset_component (component_id),
  KEY idx_asset_blob (asset_blob_id),
  KEY idx_asset_repository_format (repository_id, format),
  KEY idx_asset_name (format, name(191)),
  CONSTRAINT fk_asset_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  CONSTRAINT fk_asset_component FOREIGN KEY (component_id) REFERENCES component (id) ON DELETE SET NULL,
  CONSTRAINT fk_asset_blob FOREIGN KEY (asset_blob_id) REFERENCES asset_blob (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE browse_node (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  parent_id BIGINT UNSIGNED NULL,
  component_id BIGINT UNSIGNED NULL,
  asset_id BIGINT UNSIGNED NULL,
  path VARCHAR(2048) NOT NULL,
  path_hash BINARY(32) NOT NULL,
  display_name VARCHAR(512) NOT NULL,
  package_url VARCHAR(2048) NULL,
  depth INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_browse_node_path (repository_id, path_hash),
  UNIQUE KEY uk_browse_node_asset (asset_id),
  KEY idx_browse_node_parent (parent_id),
  KEY idx_browse_node_component (component_id),
  CONSTRAINT fk_browse_node_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
  CONSTRAINT fk_browse_node_parent FOREIGN KEY (parent_id) REFERENCES browse_node (id) ON DELETE CASCADE,
  CONSTRAINT fk_browse_node_component FOREIGN KEY (component_id) REFERENCES component (id) ON DELETE SET NULL,
  CONSTRAINT fk_browse_node_asset FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE component_search (
  component_id BIGINT UNSIGNED NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  format VARCHAR(50) NOT NULL,
  namespace VARCHAR(512) NULL,
  name VARCHAR(512) NOT NULL,
  version VARCHAR(255) NULL,
  keywords TEXT NOT NULL,
  refreshed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (component_id),
  KEY idx_component_search_repository (repository_id),
  FULLTEXT KEY ft_component_search (namespace, name, version, keywords),
  CONSTRAINT fk_component_search_component FOREIGN KEY (component_id) REFERENCES component (id) ON DELETE CASCADE,
  CONSTRAINT fk_component_search_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  source VARCHAR(100) NOT NULL,
  user_id VARCHAR(255) NOT NULL,
  first_name VARCHAR(255) NULL,
  last_name VARCHAR(255) NULL,
  email VARCHAR(512) NULL,
  password_hash VARCHAR(1024) NULL,
  status VARCHAR(50) NOT NULL,
  external_id VARCHAR(255) NULL,
  attributes_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_security_user_source_user (source, user_id),
  KEY idx_security_user_email (email(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_role (
  role_id VARCHAR(255) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(1024) NULL,
  read_only TINYINT(1) NOT NULL DEFAULT 0,
  attributes_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (role_id),
  KEY idx_security_role_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_privilege (
  privilege_id VARCHAR(255) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(1024) NULL,
  type VARCHAR(100) NOT NULL,
  properties_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (privilege_id),
  UNIQUE KEY uk_security_privilege_name (name),
  KEY idx_security_privilege_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_role_privilege (
  role_id VARCHAR(255) NOT NULL,
  privilege_id VARCHAR(255) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (role_id, privilege_id),
  KEY idx_security_role_privilege_privilege (privilege_id),
  CONSTRAINT fk_security_role_privilege_role FOREIGN KEY (role_id) REFERENCES security_role (role_id) ON DELETE CASCADE,
  CONSTRAINT fk_security_role_privilege_privilege FOREIGN KEY (privilege_id) REFERENCES security_privilege (privilege_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_role_inheritance (
  role_id VARCHAR(255) NOT NULL,
  child_role_id VARCHAR(255) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (role_id, child_role_id),
  KEY idx_security_role_inheritance_child (child_role_id),
  CONSTRAINT fk_security_role_inheritance_role FOREIGN KEY (role_id) REFERENCES security_role (role_id) ON DELETE CASCADE,
  CONSTRAINT fk_security_role_inheritance_child FOREIGN KEY (child_role_id) REFERENCES security_role (role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_user_role (
  user_id BIGINT UNSIGNED NOT NULL,
  role_id VARCHAR(255) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, role_id),
  KEY idx_security_user_role_role (role_id),
  CONSTRAINT fk_security_user_role_user FOREIGN KEY (user_id) REFERENCES security_user (id) ON DELETE CASCADE,
  CONSTRAINT fk_security_user_role_role FOREIGN KEY (role_id) REFERENCES security_role (role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_anonymous_config (
  id TINYINT UNSIGNED NOT NULL,
  enabled TINYINT(1) NOT NULL,
  user_source VARCHAR(100) NOT NULL,
  user_id VARCHAR(255) NOT NULL,
  realm_name VARCHAR(255) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_realm_config (
  id TINYINT UNSIGNED NOT NULL,
  realms_json JSON NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE api_key (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  domain VARCHAR(100) NOT NULL,
  owner_source VARCHAR(100) NOT NULL,
  owner_user_id VARCHAR(255) NOT NULL,
  api_key_hash VARCHAR(255) NOT NULL,
  encrypted_payload TEXT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_api_key_domain_owner (domain, owner_source, owner_user_id),
  UNIQUE KEY uk_api_key_hash (api_key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE migration_job (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  source_nexus_version VARCHAR(100) NOT NULL,
  source_data_path VARCHAR(2048) NOT NULL,
  status VARCHAR(50) NOT NULL,
  options_json JSON NOT NULL,
  summary_json JSON NULL,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_migration_job_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE migration_checkpoint (
  job_id BIGINT UNSIGNED NOT NULL,
  source_database VARCHAR(100) NOT NULL,
  source_class VARCHAR(200) NOT NULL,
  source_rid VARCHAR(100) NOT NULL,
  target_table VARCHAR(200) NOT NULL,
  target_id VARCHAR(255) NOT NULL,
  source_checksum CHAR(64) NULL,
  migrated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (job_id, source_database, source_class, source_rid),
  KEY idx_migration_checkpoint_target (target_table, target_id),
  CONSTRAINT fk_migration_checkpoint_job FOREIGN KEY (job_id) REFERENCES migration_job (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE migration_validation_result (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  job_id BIGINT UNSIGNED NOT NULL,
  scope VARCHAR(200) NOT NULL,
  source_count BIGINT UNSIGNED NULL,
  target_count BIGINT UNSIGNED NULL,
  status VARCHAR(50) NOT NULL,
  details_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_migration_validation_job_scope (job_id, scope),
  CONSTRAINT fk_migration_validation_job FOREIGN KEY (job_id) REFERENCES migration_job (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
