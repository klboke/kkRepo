-- Seed Nexus-compatible built-in security facts that are contributed by
-- NexusSecurityContributor, RepositoryViewSecurityContributor, and
-- RepositoryAdminSecurityContributor in Nexus 3.29.x.

INSERT INTO security_privilege
  (privilege_id, name, description, type, read_only, properties_json)
VALUES
  ('nx-settings-all', 'nx-settings-all', 'All permissions for Settings', 'application', 1, JSON_OBJECT('domain', 'settings', 'actions', '*')),
  ('nx-settings-read', 'nx-settings-read', 'Read permission for Settings', 'application', 1, JSON_OBJECT('domain', 'settings', 'actions', 'read')),
  ('nx-settings-update', 'nx-settings-update', 'Update permission for Settings', 'application', 1, JSON_OBJECT('domain', 'settings', 'actions', 'update,read')),
  ('nx-bundles-all', 'nx-bundles-all', 'All permissions for Bundles', 'application', 1, JSON_OBJECT('domain', 'bundles', 'actions', '*')),
  ('nx-bundles-read', 'nx-bundles-read', 'Read permission for Bundles', 'application', 1, JSON_OBJECT('domain', 'bundles', 'actions', 'read')),
  ('nx-search-read', 'nx-search-read', 'Read permission for Search', 'application', 1, JSON_OBJECT('domain', 'search', 'actions', 'read')),
  ('nx-healthcheck-read', 'nx-healthcheck-read', 'Read permission for Healthcheck', 'application', 1, JSON_OBJECT('domain', 'healthcheck', 'actions', 'read')),
  ('nx-apikey-all', 'nx-apikey-all', 'All permissions for APIKey', 'application', 1, JSON_OBJECT('domain', 'apikey', 'actions', '*')),
  ('nx-ldap-all', 'nx-ldap-all', 'All permissions for LDAP', 'application', 1, JSON_OBJECT('domain', 'ldap', 'actions', '*')),
  ('nx-ldap-create', 'nx-ldap-create', 'Create permission for LDAP', 'application', 1, JSON_OBJECT('domain', 'ldap', 'actions', 'create,read')),
  ('nx-ldap-read', 'nx-ldap-read', 'Read permission for LDAP', 'application', 1, JSON_OBJECT('domain', 'ldap', 'actions', 'read')),
  ('nx-ldap-update', 'nx-ldap-update', 'Update permission for LDAP', 'application', 1, JSON_OBJECT('domain', 'ldap', 'actions', 'update,read')),
  ('nx-ldap-delete', 'nx-ldap-delete', 'Delete permission for LDAP', 'application', 1, JSON_OBJECT('domain', 'ldap', 'actions', 'delete,read')),
  ('nx-privileges-all', 'nx-privileges-all', 'All permissions for Privileges', 'application', 1, JSON_OBJECT('domain', 'privileges', 'actions', '*')),
  ('nx-privileges-create', 'nx-privileges-create', 'Create permission for Privileges', 'application', 1, JSON_OBJECT('domain', 'privileges', 'actions', 'create,read')),
  ('nx-privileges-read', 'nx-privileges-read', 'Read permission for Privileges', 'application', 1, JSON_OBJECT('domain', 'privileges', 'actions', 'read')),
  ('nx-privileges-update', 'nx-privileges-update', 'Update permission for Privileges', 'application', 1, JSON_OBJECT('domain', 'privileges', 'actions', 'update,read')),
  ('nx-privileges-delete', 'nx-privileges-delete', 'Delete permission for Privileges', 'application', 1, JSON_OBJECT('domain', 'privileges', 'actions', 'delete,read')),
  ('nx-roles-all', 'nx-roles-all', 'All permissions for Roles', 'application', 1, JSON_OBJECT('domain', 'roles', 'actions', '*')),
  ('nx-roles-create', 'nx-roles-create', 'Create permission for Roles', 'application', 1, JSON_OBJECT('domain', 'roles', 'actions', 'create,read')),
  ('nx-roles-read', 'nx-roles-read', 'Read permission for Roles', 'application', 1, JSON_OBJECT('domain', 'roles', 'actions', 'read')),
  ('nx-roles-update', 'nx-roles-update', 'Update permission for Roles', 'application', 1, JSON_OBJECT('domain', 'roles', 'actions', 'update,read')),
  ('nx-roles-delete', 'nx-roles-delete', 'Delete permission for Roles', 'application', 1, JSON_OBJECT('domain', 'roles', 'actions', 'delete,read')),
  ('nx-users-all', 'nx-users-all', 'All permissions for Users', 'application', 1, JSON_OBJECT('domain', 'users', 'actions', '*')),
  ('nx-users-create', 'nx-users-create', 'Create permission for Users', 'application', 1, JSON_OBJECT('domain', 'users', 'actions', 'create,read')),
  ('nx-users-read', 'nx-users-read', 'Read permission for Users', 'application', 1, JSON_OBJECT('domain', 'users', 'actions', 'read')),
  ('nx-users-update', 'nx-users-update', 'Update permission for Users', 'application', 1, JSON_OBJECT('domain', 'users', 'actions', 'update,read')),
  ('nx-users-delete', 'nx-users-delete', 'Delete permission for Users', 'application', 1, JSON_OBJECT('domain', 'users', 'actions', 'delete,read')),
  ('nx-userschangepw', 'nx-userschangepw', 'Change password permission', 'application', 1, JSON_OBJECT('domain', 'userschangepw', 'actions', 'create,read')),
  ('nx-selectors-all', 'nx-selectors-all', 'All permissions for Content Selectors', 'application', 1, JSON_OBJECT('domain', 'selectors', 'actions', '*')),
  ('nx-selectors-create', 'nx-selectors-create', 'Create permission for Content Selectors', 'application', 1, JSON_OBJECT('domain', 'selectors', 'actions', 'create,read')),
  ('nx-selectors-read', 'nx-selectors-read', 'Read permission for Content Selectors', 'application', 1, JSON_OBJECT('domain', 'selectors', 'actions', 'read')),
  ('nx-selectors-update', 'nx-selectors-update', 'Update permission for Content Selectors', 'application', 1, JSON_OBJECT('domain', 'selectors', 'actions', 'update,read')),
  ('nx-selectors-delete', 'nx-selectors-delete', 'Delete permission for Content Selectors', 'application', 1, JSON_OBJECT('domain', 'selectors', 'actions', 'delete,read')),
  ('nx-component-upload', 'nx-component-upload', 'Upload component permission', 'application', 1, JSON_OBJECT('domain', 'component', 'actions', 'create')),
  ('nx-repository-view-*-*-*', 'nx-repository-view-*-*-*', 'All permissions for all repository views', 'repository-view', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', '*')),
  ('nx-repository-view-*-*-browse', 'nx-repository-view-*-*-browse', 'Browse permission for all repository views', 'repository-view', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', 'browse')),
  ('nx-repository-view-*-*-read', 'nx-repository-view-*-*-read', 'Read permission for all repository views', 'repository-view', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', 'read')),
  ('nx-repository-view-*-*-edit', 'nx-repository-view-*-*-edit', 'Edit permission for all repository views', 'repository-view', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', 'edit')),
  ('nx-repository-view-*-*-add', 'nx-repository-view-*-*-add', 'Add permission for all repository views', 'repository-view', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', 'add')),
  ('nx-repository-view-*-*-delete', 'nx-repository-view-*-*-delete', 'Delete permission for all repository views', 'repository-view', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', 'delete')),
  ('nx-repository-admin-*-*-*', 'nx-repository-admin-*-*-*', 'All permissions for all repository administration', 'repository-admin', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', '*')),
  ('nx-repository-admin-*-*-browse', 'nx-repository-admin-*-*-browse', 'Browse permission for all repository administration', 'repository-admin', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', 'browse')),
  ('nx-repository-admin-*-*-read', 'nx-repository-admin-*-*-read', 'Read permission for all repository administration', 'repository-admin', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', 'read')),
  ('nx-repository-admin-*-*-edit', 'nx-repository-admin-*-*-edit', 'Edit permission for all repository administration', 'repository-admin', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', 'edit')),
  ('nx-repository-admin-*-*-add', 'nx-repository-admin-*-*-add', 'Add permission for all repository administration', 'repository-admin', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', 'add')),
  ('nx-repository-admin-*-*-delete', 'nx-repository-admin-*-*-delete', 'Delete permission for all repository administration', 'repository-admin', 1, JSON_OBJECT('format', '*', 'repository', '*', 'actions', 'delete'))
ON DUPLICATE KEY UPDATE privilege_id = privilege_id;

INSERT INTO security_role
  (role_id, source, name, description, read_only, attributes_json)
VALUES
  ('nx-anonymous', 'default', 'nx-anonymous', 'Anonymous Role', 1, JSON_OBJECT('source', 'nexus-builtin')),
  ('nx-admin', 'default', 'nx-admin', 'Administrator Role', 1, JSON_OBJECT('source', 'nexus-builtin'))
ON DUPLICATE KEY UPDATE role_id = role_id;

INSERT IGNORE INTO security_role_privilege (role_id, privilege_id)
VALUES
  ('nx-admin', 'nx-all'),
  ('nx-anonymous', 'nx-search-read'),
  ('nx-anonymous', 'nx-healthcheck-read'),
  ('nx-anonymous', 'nx-repository-view-*-*-browse'),
  ('nx-anonymous', 'nx-repository-view-*-*-read');

INSERT INTO security_user
  (source, user_id, first_name, last_name, email, password_hash, status, external_id, attributes_json)
VALUES
  ('default', 'anonymous', 'Anonymous', 'User', 'anonymous@example.invalid', NULL, 'ACTIVE', NULL, JSON_OBJECT('source', 'nexus-builtin'))
ON DUPLICATE KEY UPDATE user_id = user_id;

INSERT IGNORE INTO security_user_role (user_id, role_id)
SELECT id, 'nx-anonymous'
FROM security_user
WHERE source = 'default' AND user_id = 'anonymous';

INSERT INTO security_anonymous_config
  (id, enabled, user_source, user_id, realm_name)
VALUES
  (1, 1, 'default', 'anonymous', 'NexusAuthorizingRealm')
ON DUPLICATE KEY UPDATE id = id;
