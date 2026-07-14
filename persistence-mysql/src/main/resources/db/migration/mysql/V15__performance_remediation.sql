-- Performance remediation for million-scale repositories.

ALTER TABLE asset
  ADD INDEX idx_asset_repository_path_prefix (repository_id, path(191)),
  ADD INDEX idx_asset_repo_format_kind_path (repository_id, format, kind, path(191)),
  ADD INDEX idx_asset_component_path (component_id, path(191));

ALTER TABLE browse_node
  ADD COLUMN has_asset_subtree TINYINT(1) NOT NULL DEFAULT 0 AFTER depth,
  ADD INDEX idx_browse_node_root (repository_id, depth, path_hash),
  ADD INDEX idx_browse_node_parent_subtree (parent_id, has_asset_subtree);

UPDATE browse_node bn
LEFT JOIN (
  SELECT id
  FROM (
    SELECT DISTINCT ancestor.id
    FROM browse_node ancestor
    JOIN browse_node descendant
      ON descendant.repository_id = ancestor.repository_id
     AND descendant.asset_id IS NOT NULL
     AND LEFT(descendant.path, CHAR_LENGTH(ancestor.path) + 1) = CONCAT(ancestor.path, '/')
  ) materialized
) subtree ON subtree.id = bn.id
SET bn.has_asset_subtree = 1
WHERE bn.asset_id IS NOT NULL OR subtree.id IS NOT NULL;

ALTER TABLE component
  ADD INDEX idx_component_repo_name (repository_id, name(191)),
  ADD INDEX idx_component_repo_format_updated (repository_id, format, last_updated_at);

INSERT INTO component_search
  (component_id, repository_id, format, namespace, name, version, keywords, refreshed_at)
SELECT
  id,
  repository_id,
  format,
  namespace,
  name,
  version,
  CONCAT_WS(' ', namespace, name, version, kind, LOWER(format)),
  NOW(3)
FROM component
ON DUPLICATE KEY UPDATE
  repository_id = VALUES(repository_id),
  format = VALUES(format),
  namespace = VALUES(namespace),
  name = VALUES(name),
  version = VALUES(version),
  keywords = VALUES(keywords),
  refreshed_at = NOW(3);

CREATE TABLE repository_index_rebuild_marker (
  repository_id BIGINT UNSIGNED NOT NULL,
  index_kind VARCHAR(50) NOT NULL,
  scope_key VARCHAR(512) NOT NULL DEFAULT '',
  requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  attempts INT NOT NULL DEFAULT 0,
  last_attempted_at DATETIME(3) NULL,
  last_error TEXT NULL,
  PRIMARY KEY (repository_id, index_kind, scope_key),
  KEY idx_repository_index_rebuild_requested_at (requested_at),
  CONSTRAINT fk_repository_index_rebuild_marker_repository
    FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE metadata_rebuild_marker
  ADD COLUMN attempts INT NOT NULL DEFAULT 0 AFTER requested_at,
  ADD COLUMN last_attempted_at DATETIME(3) NULL AFTER attempts,
  ADD COLUMN last_error TEXT NULL AFTER last_attempted_at;

ALTER TABLE asset_blob
  ADD COLUMN deleted_at DATETIME(3) NULL AFTER blob_updated_at,
  ADD COLUMN delete_reason VARCHAR(255) NULL AFTER deleted_at,
  ADD COLUMN delete_claimed_at DATETIME(3) NULL AFTER delete_reason,
  ADD INDEX idx_asset_blob_deleted_at (deleted_at),
  ADD INDEX idx_asset_blob_delete_claimed_at (delete_claimed_at);
