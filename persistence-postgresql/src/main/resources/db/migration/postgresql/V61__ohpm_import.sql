CREATE TABLE ohpm_import_job (
  job_id CHAR(36) NOT NULL,
  repository_id BIGINT NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  source_repository_id BIGINT NULL,
  requester VARCHAR(255) NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (job_id),
  CONSTRAINT fk_ohpm_import_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT ck_ohpm_import_source CHECK (source_type IN ('DIRECTORY', 'REGISTRY')),
  CONSTRAINT ck_ohpm_import_state CHECK (state IN ('PLANNING', 'READY', 'APPLYING', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX idx_ohpm_import_repository ON ohpm_import_job (repository_id, created_at, job_id);

CREATE TABLE ohpm_import_item (
  job_id CHAR(36) NOT NULL,
  item_key CHAR(64) NOT NULL,
  package_name VARCHAR(512) NULL,
  version VARCHAR(255) NULL,
  plan_json JSONB NOT NULL,
  plan_bytes INT NOT NULL,
  state VARCHAR(16) NOT NULL,
  detail VARCHAR(2048) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (job_id, item_key),
  CONSTRAINT fk_ohpm_import_item_job FOREIGN KEY (job_id) REFERENCES ohpm_import_job(job_id) ON DELETE CASCADE,
  CONSTRAINT ck_ohpm_import_item_bytes CHECK (plan_bytes > 0 AND plan_bytes <= 2097152),
  CONSTRAINT ck_ohpm_import_item_state CHECK (state IN ('PLANNED', 'IMPORTED', 'SKIPPED', 'MANUAL', 'FAILED'))
);
