CREATE INDEX idx_asset_repository_id
  ON asset(repository_id, id);

CREATE TABLE artifact_change_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  asset_id BIGINT UNSIGNED NOT NULL,
  previous_asset_blob_id BIGINT UNSIGNED NULL,
  asset_blob_id BIGINT UNSIGNED NOT NULL,
  change_kind VARCHAR(32) NOT NULL,
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  INDEX idx_artifact_change_repository (repository_id, id),
  INDEX idx_artifact_change_asset (asset_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE blob_reference (
  owner_type VARCHAR(64) NOT NULL,
  owner_id BIGINT UNSIGNED NOT NULL,
  blob_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (owner_type, owner_id, blob_id),
  CONSTRAINT fk_blob_reference_blob
    FOREIGN KEY (blob_id) REFERENCES asset_blob(id) ON DELETE RESTRICT,
  INDEX idx_blob_reference_blob (blob_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_docker_reference_policy_lookup
  ON docker_manifest_reference(repository_id, digest_hash, image_name, manifest_id);

CREATE TABLE security_scan_profile (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  catalog_engine VARCHAR(64) NOT NULL,
  matcher_engine VARCHAR(64) NOT NULL,
  scanner_types_json JSON NOT NULL,
  target_rules_json JSON NOT NULL,
  max_input_bytes BIGINT NOT NULL,
  max_archive_entries INT NOT NULL,
  max_uncompressed_bytes BIGINT NOT NULL,
  max_single_file_bytes BIGINT NOT NULL,
  max_nested_depth INT NOT NULL,
  timeout_seconds INT NOT NULL,
  oci_platform_policy VARCHAR(32) NOT NULL,
  required_platforms_json JSON NOT NULL,
  configuration_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_security_scan_profile_name UNIQUE (name),
  CONSTRAINT uk_security_scan_profile_digest UNIQUE (configuration_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_scan_policy (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  name_normalized VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  block_severity VARCHAR(16) NOT NULL,
  only_fixable BOOLEAN NOT NULL DEFAULT FALSE,
  block_unknown_severity BOOLEAN NOT NULL DEFAULT FALSE,
  require_complete_inventory BOOLEAN NOT NULL DEFAULT FALSE,
  max_result_age_seconds BIGINT NULL,
  required_platforms_json JSON NOT NULL,
  revision BIGINT NOT NULL,
  created_by VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_security_scan_policy_name_revision UNIQUE (name_normalized, revision),
  INDEX idx_security_scan_policy_active (enabled, name, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE repository_security_scan_config (
  repository_id BIGINT UNSIGNED NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  profile_id BIGINT UNSIGNED NOT NULL,
  scan_hosted_content BOOLEAN NOT NULL DEFAULT TRUE,
  scan_proxy_content BOOLEAN NOT NULL DEFAULT TRUE,
  enforcement_mode VARCHAR(16) NOT NULL DEFAULT 'AUDIT',
  pending_action VARCHAR(16) NOT NULL DEFAULT 'ALLOW',
  failure_action VARCHAR(16) NOT NULL DEFAULT 'ALLOW',
  partial_action VARCHAR(16) NOT NULL DEFAULT 'ALLOW',
  max_result_age_seconds BIGINT NULL,
  policy_id BIGINT UNSIGNED NULL,
  config_revision BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (repository_id),
  CONSTRAINT fk_repository_security_scan_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_repository_security_scan_profile
    FOREIGN KEY (profile_id) REFERENCES security_scan_profile(id) ON DELETE RESTRICT,
  CONSTRAINT fk_repository_security_scan_policy
    FOREIGN KEY (policy_id) REFERENCES security_scan_policy(id) ON DELETE SET NULL,
  INDEX idx_repository_security_scan_enabled (enabled, repository_id),
  INDEX idx_repository_security_scan_profile (profile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_scan_candidate (
  asset_id BIGINT UNSIGNED NOT NULL,
  asset_blob_id BIGINT UNSIGNED NULL,
  content_generation BIGINT NOT NULL,
  enqueued_generation BIGINT NOT NULL DEFAULT 0,
  pending BOOLEAN GENERATED ALWAYS AS (content_generation > enqueued_generation) STORED,
  changed_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (asset_id),
  CONSTRAINT fk_security_scan_candidate_asset
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  CONSTRAINT fk_security_scan_candidate_blob
    FOREIGN KEY (asset_blob_id) REFERENCES asset_blob(id) ON DELETE SET NULL,
  INDEX idx_security_scan_candidate_queue
    (pending, changed_at, asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_scanner_snapshot (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  adapter_name VARCHAR(128) NOT NULL,
  adapter_api_version VARCHAR(32) NOT NULL,
  engine_name VARCHAR(128) NOT NULL,
  engine_version VARCHAR(128) NOT NULL,
  vulnerability_database_revision VARCHAR(255) NULL,
  vulnerability_database_updated_at DATETIME(3) NULL,
  capability_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  observed_at DATETIME(3) NOT NULL,
  ready BOOLEAN NOT NULL,
  details_json JSON NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_security_scanner_snapshot UNIQUE (snapshot_fingerprint),
  INDEX idx_security_scanner_snapshot_observed (observed_at, id),
  INDEX idx_security_scanner_snapshot_ready (ready, observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_scan_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  asset_id BIGINT UNSIGNED NULL,
  subject_kind VARCHAR(32) NOT NULL,
  subject_key VARCHAR(1024) NOT NULL,
  subject_key_hash BINARY(32) NOT NULL,
  content_generation BIGINT NOT NULL,
  profile_id BIGINT UNSIGNED NOT NULL,
  profile_revision BIGINT NOT NULL,
  requested_scanner_snapshot_id BIGINT UNSIGNED NULL,
  stage VARCHAR(32) NOT NULL,
  request_reason VARCHAR(48) NOT NULL,
  priority INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL,
  next_attempt_at DATETIME(3) NOT NULL,
  claimed_by VARCHAR(128) NULL,
  lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until DATETIME(3) NULL,
  last_heartbeat_at DATETIME(3) NULL,
  last_error_code VARCHAR(128) NULL,
  last_error_summary VARCHAR(2048) NULL,
  requested_by VARCHAR(255) NULL,
  request_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  idempotency_key_hash BINARY(32) NULL,
  requested_at DATETIME(3) NOT NULL,
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  dedupe_key BINARY(32) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_security_scan_task_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_security_scan_task_asset
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE SET NULL,
  CONSTRAINT fk_security_scan_task_profile
    FOREIGN KEY (profile_id) REFERENCES security_scan_profile(id) ON DELETE RESTRICT,
  CONSTRAINT fk_security_scan_task_snapshot
    FOREIGN KEY (requested_scanner_snapshot_id) REFERENCES security_scanner_snapshot(id)
    ON DELETE SET NULL,
  CONSTRAINT uk_security_scan_task_dedupe UNIQUE (dedupe_key),
  CONSTRAINT uk_security_scan_task_idempotency UNIQUE (repository_id, idempotency_key_hash),
  INDEX idx_security_scan_task_claim
    (status, next_attempt_at, lease_until, priority, requested_at, id),
  INDEX idx_security_scan_task_requested_snapshot (requested_scanner_snapshot_id),
  INDEX idx_security_scan_task_asset (asset_id, created_at, id),
  INDEX idx_security_scan_task_repository (repository_id, created_at, id),
  INDEX idx_security_scan_task_repository_profile (repository_id, profile_id, id),
  INDEX idx_security_scan_task_repository_status (repository_id, status, id),
  INDEX idx_security_scan_task_terminal (status, finished_at),
  INDEX idx_security_scan_task_pending_age (status, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_sbom (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  subject_kind VARCHAR(32) NOT NULL,
  subject_identity VARCHAR(1024) NOT NULL,
  subject_identity_hash BINARY(32) NOT NULL,
  catalog_engine VARCHAR(128) NOT NULL,
  catalog_engine_version VARCHAR(128) NOT NULL,
  catalog_configuration_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  catalog_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  document_blob_id BIGINT UNSIGNED NOT NULL,
  document_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  spec_name VARCHAR(64) NOT NULL,
  spec_version VARCHAR(32) NOT NULL,
  component_count INT NOT NULL,
  dependency_count INT NOT NULL,
  inventory_complete BOOLEAN NOT NULL,
  created_at DATETIME(3) NOT NULL,
  last_accessed_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_security_sbom_document_blob
    FOREIGN KEY (document_blob_id) REFERENCES asset_blob(id) ON DELETE RESTRICT,
  CONSTRAINT uk_security_sbom_catalog_fingerprint UNIQUE (catalog_fingerprint),
  INDEX idx_security_sbom_subject (subject_identity_hash, created_at, id),
  INDEX idx_security_sbom_retention (last_accessed_at, id),
  INDEX idx_security_sbom_document_blob (document_blob_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_sbom_component (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  sbom_id BIGINT UNSIGNED NOT NULL,
  component_ref VARCHAR(1024) NOT NULL,
  component_ref_hash BINARY(32) NOT NULL,
  package_url VARCHAR(2048) NULL,
  package_url_hash BINARY(32) NULL,
  component_type VARCHAR(64) NULL,
  namespace VARCHAR(512) NULL,
  name VARCHAR(512) NOT NULL,
  version VARCHAR(512) NULL,
  directness VARCHAR(32) NULL,
  locations_json JSON NOT NULL,
  licenses_json JSON NOT NULL,
  properties_json JSON NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_security_sbom_component_sbom
    FOREIGN KEY (sbom_id) REFERENCES security_sbom(id) ON DELETE CASCADE,
  CONSTRAINT uk_security_sbom_component_ref UNIQUE (sbom_id, component_ref_hash),
  INDEX idx_security_sbom_component_purl (package_url_hash),
  INDEX idx_security_sbom_component_name (name(191), version(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_scan_run (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id BIGINT UNSIGNED NULL,
  sbom_id BIGINT UNSIGNED NOT NULL,
  scanner_snapshot_id BIGINT UNSIGNED NOT NULL,
  match_configuration_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  match_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(24) NOT NULL,
  scan_completeness VARCHAR(24) NOT NULL,
  raw_report_blob_id BIGINT UNSIGNED NOT NULL,
  raw_report_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  finding_count INT NOT NULL,
  fixable_finding_count INT NOT NULL,
  critical_count INT NOT NULL,
  high_count INT NOT NULL,
  medium_count INT NOT NULL,
  low_count INT NOT NULL,
  unknown_count INT NOT NULL,
  max_severity VARCHAR(16) NOT NULL,
  scanned_platforms_json JSON NOT NULL,
  missing_platforms_json JSON NOT NULL,
  started_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  last_accessed_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_security_scan_run_task
    FOREIGN KEY (task_id) REFERENCES security_scan_task(id) ON DELETE SET NULL,
  CONSTRAINT fk_security_scan_run_sbom
    FOREIGN KEY (sbom_id) REFERENCES security_sbom(id) ON DELETE RESTRICT,
  CONSTRAINT fk_security_scan_run_snapshot
    FOREIGN KEY (scanner_snapshot_id) REFERENCES security_scanner_snapshot(id) ON DELETE RESTRICT,
  CONSTRAINT fk_security_scan_run_report_blob
    FOREIGN KEY (raw_report_blob_id) REFERENCES asset_blob(id) ON DELETE RESTRICT,
  CONSTRAINT uk_security_scan_run_match_fingerprint UNIQUE (match_fingerprint),
  INDEX idx_security_scan_run_sbom (sbom_id, created_at, id),
  INDEX idx_security_scan_run_task (task_id),
  INDEX idx_security_scan_run_snapshot (scanner_snapshot_id),
  INDEX idx_security_scan_run_report_blob (raw_report_blob_id),
  INDEX idx_security_scan_run_status (status, completed_at),
  INDEX idx_security_scan_run_retention (last_accessed_at, completed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_scan_run_subject (
  scan_run_id BIGINT UNSIGNED NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  asset_id BIGINT UNSIGNED NOT NULL,
  profile_id BIGINT UNSIGNED NOT NULL,
  content_generation BIGINT NOT NULL,
  associated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (scan_run_id, repository_id, asset_id, profile_id, content_generation),
  CONSTRAINT fk_security_scan_run_subject_run
    FOREIGN KEY (scan_run_id) REFERENCES security_scan_run(id) ON DELETE CASCADE,
  CONSTRAINT fk_security_scan_run_subject_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_security_scan_run_subject_asset
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  CONSTRAINT fk_security_scan_run_subject_profile
    FOREIGN KEY (profile_id) REFERENCES security_scan_profile(id) ON DELETE RESTRICT,
  INDEX idx_security_scan_run_subject_repository (repository_id, scan_run_id),
  INDEX idx_security_scan_run_subject_asset (asset_id, profile_id, associated_at),
  INDEX idx_security_scan_run_subject_retention (associated_at, scan_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_scan_finding (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  scan_run_id BIGINT UNSIGNED NOT NULL,
  finding_key VARCHAR(2048) NOT NULL,
  finding_key_hash BINARY(32) NOT NULL,
  advisory_id VARCHAR(255) NOT NULL,
  aliases_json JSON NOT NULL,
  data_source VARCHAR(2048) NULL,
  package_url VARCHAR(2048) NULL,
  package_name VARCHAR(512) NOT NULL,
  installed_version VARCHAR(512) NULL,
  fixed_versions_json JSON NOT NULL,
  severity VARCHAR(16) NOT NULL,
  severity_source VARCHAR(128) NULL,
  cvss_vector VARCHAR(255) NULL,
  cvss_score DECIMAL(4,1) NULL,
  title VARCHAR(1024) NULL,
  description TEXT NULL,
  primary_url VARCHAR(2048) NULL,
  locations_json JSON NOT NULL,
  source_status VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_security_scan_finding_run
    FOREIGN KEY (scan_run_id) REFERENCES security_scan_run(id) ON DELETE CASCADE,
  CONSTRAINT uk_security_scan_finding_key UNIQUE (scan_run_id, finding_key_hash),
  INDEX idx_security_scan_finding_advisory (advisory_id, severity),
  INDEX idx_security_scan_finding_package (package_name(191), installed_version(191)),
  INDEX idx_security_scan_finding_severity (severity, scan_run_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE asset_security_state (
  asset_id BIGINT UNSIGNED NOT NULL,
  profile_id BIGINT UNSIGNED NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  content_generation BIGINT NOT NULL,
  subject_identity_hash BINARY(32) NOT NULL,
  latest_scan_run_id BIGINT UNSIGNED NULL,
  scan_state VARCHAR(24) NOT NULL,
  scan_completeness VARCHAR(24) NOT NULL,
  inventory_complete BOOLEAN NOT NULL,
  max_severity VARCHAR(16) NOT NULL,
  finding_counts_json JSON NOT NULL,
  policy_id BIGINT UNSIGNED NULL,
  policy_revision BIGINT NULL,
  policy_decision VARCHAR(32) NOT NULL,
  policy_reason_code VARCHAR(128) NOT NULL,
  stale_at DATETIME(3) NULL,
  last_evaluated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL,
  PRIMARY KEY (asset_id, profile_id),
  CONSTRAINT fk_asset_security_state_asset
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  CONSTRAINT fk_asset_security_state_profile
    FOREIGN KEY (profile_id) REFERENCES security_scan_profile(id) ON DELETE RESTRICT,
  CONSTRAINT fk_asset_security_state_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_asset_security_state_run
    FOREIGN KEY (latest_scan_run_id) REFERENCES security_scan_run(id) ON DELETE SET NULL,
  CONSTRAINT fk_asset_security_state_policy
    FOREIGN KEY (policy_id) REFERENCES security_scan_policy(id) ON DELETE SET NULL,
  INDEX idx_asset_security_state_run (latest_scan_run_id),
  INDEX idx_asset_security_state_decision (policy_decision, scan_state, asset_id),
  INDEX idx_asset_security_state_stale (stale_at, scan_state),
  INDEX idx_asset_security_state_scan (scan_state, asset_id),
  INDEX idx_asset_security_state_repository_summary
    (repository_id, scan_state, policy_decision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE asset_security_policy_state (
  asset_id BIGINT UNSIGNED NOT NULL,
  profile_id BIGINT UNSIGNED NOT NULL,
  repository_id BIGINT UNSIGNED NOT NULL,
  content_generation BIGINT NOT NULL,
  latest_scan_run_id BIGINT UNSIGNED NULL,
  policy_id BIGINT UNSIGNED NULL,
  policy_revision BIGINT NULL,
  config_revision BIGINT NOT NULL,
  waiver_revision BIGINT NOT NULL DEFAULT 0,
  policy_decision VARCHAR(32) NOT NULL,
  policy_reason_code VARCHAR(128) NOT NULL,
  waived_findings INT NOT NULL DEFAULT 0,
  stale_at DATETIME(3) NULL,
  next_waiver_expiry DATETIME(3) NULL,
  last_evaluated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL,
  PRIMARY KEY (asset_id, profile_id, repository_id),
  CONSTRAINT fk_asset_security_policy_state_asset
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  CONSTRAINT fk_asset_security_policy_state_profile
    FOREIGN KEY (profile_id) REFERENCES security_scan_profile(id) ON DELETE RESTRICT,
  CONSTRAINT fk_asset_security_policy_state_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_asset_security_policy_state_run
    FOREIGN KEY (latest_scan_run_id) REFERENCES security_scan_run(id) ON DELETE SET NULL,
  CONSTRAINT fk_asset_security_policy_state_policy
    FOREIGN KEY (policy_id) REFERENCES security_scan_policy(id) ON DELETE SET NULL,
  INDEX idx_asset_security_policy_run (latest_scan_run_id),
  INDEX idx_asset_security_policy_context
    (repository_id, profile_id, config_revision, waiver_revision, asset_id),
  INDEX idx_asset_security_policy_summary
    (repository_id, policy_decision, asset_id, profile_id),
  INDEX idx_asset_security_policy_expiry (next_waiver_expiry, repository_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_scan_waiver_revision (
  singleton_id TINYINT UNSIGNED NOT NULL,
  current_revision BIGINT NOT NULL,
  global_invalidation_revision BIGINT NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (singleton_id),
  CONSTRAINT ck_security_scan_waiver_revision_singleton CHECK (singleton_id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO security_scan_waiver_revision
  (singleton_id, current_revision, global_invalidation_revision, updated_at)
VALUES (1, 0, 0, CURRENT_TIMESTAMP(3));

CREATE TABLE security_scan_waiver (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  scope_type VARCHAR(24) NOT NULL,
  repository_id BIGINT UNSIGNED NULL,
  asset_id BIGINT UNSIGNED NULL,
  finding_id BIGINT UNSIGNED NULL,
  advisory_selector VARCHAR(255) NULL,
  package_selector VARCHAR(2048) NULL,
  selector_json JSON NOT NULL,
  reason VARCHAR(2048) NOT NULL,
  policy_id BIGINT UNSIGNED NULL,
  policy_revision BIGINT NULL,
  created_by VARCHAR(255) NOT NULL,
  approved_by VARCHAR(255) NULL,
  expires_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_security_scan_waiver_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_security_scan_waiver_asset
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
  CONSTRAINT fk_security_scan_waiver_finding
    FOREIGN KEY (finding_id) REFERENCES security_scan_finding(id) ON DELETE CASCADE,
  CONSTRAINT fk_security_scan_waiver_policy
    FOREIGN KEY (policy_id) REFERENCES security_scan_policy(id) ON DELETE SET NULL,
  INDEX idx_security_scan_waiver_active (repository_id, asset_id, id, expires_at),
  INDEX idx_security_scan_waiver_finding (finding_id),
  INDEX idx_security_scan_waiver_advisory (advisory_selector),
  INDEX idx_security_scan_waiver_package (package_selector(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE security_scan_backfill_job (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(24) NOT NULL,
  cursor_asset_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  scanned_assets BIGINT NOT NULL DEFAULT 0,
  marked_assets BIGINT NOT NULL DEFAULT 0,
  attempts INT NOT NULL DEFAULT 0,
  claimed_by VARCHAR(128) NULL,
  lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until DATETIME(3) NULL,
  next_attempt_at DATETIME(3) NULL,
  last_error_summary VARCHAR(2048) NULL,
  created_by VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_security_scan_backfill_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  INDEX idx_security_scan_backfill_claim_pending
    (status, next_attempt_at, created_at, id),
  INDEX idx_security_scan_backfill_claim_running
    (status, lease_until, created_at, id),
  INDEX idx_security_scan_backfill_repository (repository_id, created_at, id),
  INDEX idx_security_scan_backfill_retention (status, completed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO security_scan_profile
  (id, name, enabled, catalog_engine, matcher_engine, scanner_types_json,
   target_rules_json, max_input_bytes, max_archive_entries, max_uncompressed_bytes,
   max_single_file_bytes, max_nested_depth, timeout_seconds, oci_platform_policy,
   required_platforms_json, configuration_digest, revision, created_at, updated_at)
VALUES
  (1, 'syft-grype-v1', TRUE, 'syft', 'grype', JSON_ARRAY('vuln'), JSON_OBJECT(),
   1073741824, 100000, 10737418240, 1073741824, 3, 900, 'REQUIRED_SET',
   JSON_ARRAY('linux/amd64'),
   'a939b553200d01acf4f7a5f7ff122ff045714827dc7266b1e8f05cba7a2c32ca',
   1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

INSERT INTO security_scan_policy
  (id, name, name_normalized, enabled, block_severity, only_fixable,
   block_unknown_severity,
   require_complete_inventory, max_result_age_seconds, required_platforms_json,
   revision, created_by, created_at, updated_at)
VALUES
  (1, 'default-audit', 'default-audit', TRUE, 'CRITICAL', FALSE, FALSE, FALSE, 604800,
   JSON_ARRAY('linux/amd64'), 1, 'system', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

INSERT INTO maintenance_cursor (task_name, last_seen_id)
VALUES ('artifact_change:security_scan', 0);
