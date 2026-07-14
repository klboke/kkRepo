-- Seed Nexus blob store privileges contributed by BlobStoreSecurityContributor.
-- INSERT IGNORE makes this safe for both fresh MySQL deployments and upgraded
-- development databases where custom privileges may already exist.

INSERT IGNORE INTO security_privilege
  (privilege_id, name, description, type, read_only, properties_json)
VALUES
  ('nx-blobstores-all', 'nx-blobstores-all', 'All permissions for Blob Stores', 'application', 1, JSON_OBJECT('domain', 'blobstores', 'actions', '*')),
  ('nx-blobstores-create', 'nx-blobstores-create', 'Create permission for Blob Stores', 'application', 1, JSON_OBJECT('domain', 'blobstores', 'actions', 'create,read')),
  ('nx-blobstores-read', 'nx-blobstores-read', 'Read permission for Blob Stores', 'application', 1, JSON_OBJECT('domain', 'blobstores', 'actions', 'read')),
  ('nx-blobstores-update', 'nx-blobstores-update', 'Update permission for Blob Stores', 'application', 1, JSON_OBJECT('domain', 'blobstores', 'actions', 'update,read')),
  ('nx-blobstores-delete', 'nx-blobstores-delete', 'Delete permission for Blob Stores', 'application', 1, JSON_OBJECT('domain', 'blobstores', 'actions', 'delete,read'));
