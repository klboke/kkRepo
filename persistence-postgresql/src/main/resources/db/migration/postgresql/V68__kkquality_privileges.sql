-- kkQuality privileges. Preserve manually created privileges and existing role assignments.
-- nx-admin already holds nx-all; no other role receives code-quality access automatically.
INSERT INTO security_privilege
  (privilege_id, name, description, type, read_only, properties_json)
VALUES
  ('nx-kkquality-read', 'nx-kkquality-read', 'Read kkQuality analyses and issues', 'application', true, jsonb_build_object('domain', 'kkquality', 'actions', 'read')),
  ('nx-kkquality-update', 'nx-kkquality-update', 'Submit kkQuality analyses', 'application', true, jsonb_build_object('domain', 'kkquality', 'actions', 'update,read'))
ON CONFLICT DO NOTHING;

INSERT INTO security_role_privilege (role_id, privilege_id)
SELECT r.role_id, p.privilege_id
FROM security_role r CROSS JOIN security_privilege p
WHERE r.role_id = 'nx-admin'
  AND p.privilege_id IN ('nx-kkquality-read', 'nx-kkquality-update')
ON CONFLICT DO NOTHING;
