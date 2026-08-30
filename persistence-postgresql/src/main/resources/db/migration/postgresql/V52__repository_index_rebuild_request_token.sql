ALTER TABLE repository_index_rebuild_marker
  ADD COLUMN request_token VARCHAR(36) NOT NULL DEFAULT '';
