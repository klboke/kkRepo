CREATE TABLE asset_public_identifier (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  repository_id BIGINT UNSIGNED NOT NULL,
  opaque_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  asset_id BIGINT UNSIGNED NULL,
  native_asset_id BIGINT UNSIGNED NULL,
  identifier_type VARCHAR(32) NOT NULL,
  source_instance VARCHAR(1024) NULL,
  migration_job_id BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_asset_public_identifier (repository_id, opaque_id),
  UNIQUE KEY uk_asset_public_identifier_native (native_asset_id),
  KEY idx_asset_public_identifier_asset (asset_id, identifier_type),
  CONSTRAINT fk_asset_public_identifier_repository FOREIGN KEY (repository_id)
      REFERENCES repository (id) ON DELETE CASCADE,
  CONSTRAINT fk_asset_public_identifier_asset FOREIGN KEY (asset_id)
      REFERENCES asset (id) ON DELETE SET NULL,
  CONSTRAINT fk_asset_public_identifier_native_asset FOREIGN KEY (native_asset_id)
      REFERENCES asset (id) ON DELETE SET NULL,
  CONSTRAINT ck_asset_public_identifier_type
      CHECK (identifier_type IN ('KKREPO_NATIVE', 'NEXUS_ALIAS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO asset_public_identifier
  (repository_id, opaque_id, asset_id, native_asset_id, identifier_type)
SELECT repository_id, LPAD(LOWER(HEX(id)), 32, '0'), id, id, 'KKREPO_NATIVE'
FROM asset;
