-- kkrepo V3: performance indexes for the million-component scale.
--   * component(repository_id, namespace, name) — GA metadata rebuild reads all versions of a
--     groupId/artifactId pair every PUT. The existing indexes don't cover this filter, so the
--     planner falls back to a scan of the repository's components.
--   * metadata_rebuild_marker(requested_at) — async rebuild worker leases queued items in age
--     order via SELECT ... FOR UPDATE SKIP LOCKED.

ALTER TABLE component
  ADD INDEX idx_component_repo_ns_name (repository_id, namespace(191), name(191));

ALTER TABLE metadata_rebuild_marker
  ADD INDEX idx_metadata_rebuild_marker_requested_at (requested_at);
