-- Keep user-console component search bounded for the common "latest components" and
-- format-filtered views. Keyword search still uses LIKE semantics for Nexus-style partial
-- matches, but these indexes prevent the default Search tab from scanning the component table.

ALTER TABLE component
  ADD INDEX idx_component_last_updated (last_updated_at),
  ADD INDEX idx_component_format_last_updated (format, last_updated_at);
