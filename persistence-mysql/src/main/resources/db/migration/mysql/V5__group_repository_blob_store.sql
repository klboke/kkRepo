-- Nexus group repositories still attach a storage facet, so existing group
-- repositories created before this alignment should use the default blob store.

UPDATE repository r
JOIN blob_store b ON b.name = 'default'
SET r.blob_store_id = b.id
WHERE r.type = 'group'
  AND r.blob_store_id IS NULL;
