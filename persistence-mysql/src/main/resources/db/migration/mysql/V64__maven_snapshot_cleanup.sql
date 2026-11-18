-- Derived inventory only: no change to component coordinates, assets or blob storage.
CREATE TABLE IF NOT EXISTS maven_snapshot_family (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 repository_id BIGINT UNSIGNED NOT NULL,
 path_hash BINARY(32) NOT NULL,
 path VARCHAR(2048) NOT NULL,
 group_id VARCHAR(2048) NOT NULL,
 artifact_id VARCHAR(2048) NOT NULL,
 base_version VARCHAR(2048) NOT NULL,
 UNIQUE (repository_id, path_hash),
 FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS maven_snapshot_build (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 family_id BIGINT UNSIGNED NOT NULL,
 repository_id BIGINT UNSIGNED NOT NULL,
 version VARCHAR(2048) NOT NULL,
 snapshot_timestamp BIGINT NOT NULL,
 build_number INTEGER NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 asset_count INTEGER NOT NULL DEFAULT 0,
 primary_count INTEGER NOT NULL DEFAULT 0,
 has_primary BOOLEAN GENERATED ALWAYS AS (primary_count > 0) STORED,
 mutated_at DATETIME(3) NOT NULL,
 UNIQUE (family_id, snapshot_timestamp, build_number),
 KEY idx_maven_snapshot_page (repository_id, id),
 KEY idx_maven_snapshot_retained (family_id, has_primary, snapshot_timestamp, build_number),
 FOREIGN KEY (family_id) REFERENCES maven_snapshot_family(id) ON DELETE CASCADE,
 FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS maven_snapshot_asset (
 asset_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 build_id BIGINT UNSIGNED NOT NULL,
 family_id BIGINT UNSIGNED NOT NULL,
 variant_hash BINARY(32) NOT NULL,
 primary_asset BOOLEAN NOT NULL,
 snapshot_timestamp BIGINT NOT NULL,
 build_number INTEGER NOT NULL,
 KEY idx_maven_snapshot_assets (build_id, asset_id),
 KEY idx_maven_snapshot_variant (family_id, variant_hash, primary_asset, snapshot_timestamp, build_number),
 FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
 FOREIGN KEY (build_id) REFERENCES maven_snapshot_build(id) ON DELETE CASCADE,
 FOREIGN KEY (family_id) REFERENCES maven_snapshot_family(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO maintenance_cursor (task_name, last_seen_id)
SELECT 'maven_snapshot_backfill_v1', 0 WHERE NOT EXISTS
 (SELECT 1 FROM maintenance_cursor WHERE task_name = 'maven_snapshot_backfill_v1');
INSERT INTO maintenance_cursor (task_name, last_seen_id)
SELECT 'maven_snapshot_ready_v1', 0 WHERE NOT EXISTS
 (SELECT 1 FROM maintenance_cursor WHERE task_name = 'maven_snapshot_ready_v1');
