-- kkQuality ingestion privileges. nx-admin receives them through nx-all; no other role gets
-- code-quality access automatically.
INSERT IGNORE INTO security_privilege
  (privilege_id, name, description, type, read_only, properties_json)
VALUES
  ('nx-kkquality-read', 'nx-kkquality-read', 'Read kkQuality analyses and issues', 'application', 1, JSON_OBJECT('domain', 'kkquality', 'actions', 'read')),
  ('nx-kkquality-update', 'nx-kkquality-update', 'Submit kkQuality analyses', 'application', 1, JSON_OBJECT('domain', 'kkquality', 'actions', 'update,read'));

INSERT IGNORE INTO security_role_privilege (role_id, privilege_id)
SELECT r.role_id, p.privilege_id
FROM security_role r CROSS JOIN security_privilege p
WHERE r.role_id = 'nx-admin'
  AND p.privilege_id IN ('nx-kkquality-read', 'nx-kkquality-update');
