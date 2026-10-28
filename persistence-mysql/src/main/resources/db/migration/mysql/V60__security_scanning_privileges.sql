-- Preserve manually created privileges and existing role assignments, including
-- collisions on the unique name as well as the privilege ID. No ordinary role
-- receives scanning access automatically. nx-admin already holds nx-all.
INSERT IGNORE INTO security_privilege
  (privilege_id, name, description, type, read_only, properties_json)
VALUES
  ('nx-security-scanning-read', 'nx-security-scanning-read', 'Read artifact scan results', 'application', 1, JSON_OBJECT('domain', 'security-scanning', 'actions', 'read')),
  ('nx-security-scanning-update', 'nx-security-scanning-update', 'Manage artifact scanning', 'application', 1, JSON_OBJECT('domain', 'security-scanning', 'actions', 'update,read')),
  ('nx-security-scanning-waivers-create', 'nx-security-scanning-waivers-create', 'Create artifact scan waivers', 'application', 1, JSON_OBJECT('domain', 'security-scanning-waivers', 'actions', 'create')),
  ('nx-security-scanning-waivers-delete', 'nx-security-scanning-waivers-delete', 'Delete artifact scan waivers', 'application', 1, JSON_OBJECT('domain', 'security-scanning-waivers', 'actions', 'delete'));

INSERT IGNORE INTO security_role_privilege (role_id, privilege_id)
SELECT r.role_id, p.privilege_id
FROM security_role r CROSS JOIN security_privilege p
WHERE r.role_id = 'nx-admin'
  AND p.privilege_id IN (
    'nx-security-scanning-read', 'nx-security-scanning-update',
    'nx-security-scanning-waivers-create', 'nx-security-scanning-waivers-delete')
;
