-- Backfill Nexus repository-format and per-repository dynamic privileges.
-- Nexus contributes these from RepositoryFormatSecurityContributor and
-- RepositoryAdminSecurityContributor; INSERT IGNORE preserves migrated or
-- manually adjusted privileges.

INSERT IGNORE INTO security_privilege
  (privilege_id, name, description, type, read_only, properties_json)
SELECT
  CONCAT('nx-repository-view-', formats.format, '-*-', actions.action) AS privilege_id,
  CONCAT('nx-repository-view-', formats.format, '-*-', actions.action) AS name,
  CONCAT(actions.label, ' for all ''', formats.format, '''-format repository views') AS description,
  'repository-view' AS type,
  1 AS read_only,
  JSON_OBJECT('format', formats.format, 'repository', '*', 'actions', actions.action) AS properties_json
FROM (
  SELECT 'maven2' AS format UNION ALL
  SELECT 'npm' UNION ALL
  SELECT 'pypi' UNION ALL
  SELECT 'go' UNION ALL
  SELECT 'helm' UNION ALL
  SELECT 'raw'
) formats
CROSS JOIN (
  SELECT '*' AS action, 'All privileges' AS label UNION ALL
  SELECT 'browse', 'Browse privilege' UNION ALL
  SELECT 'read', 'Read privilege' UNION ALL
  SELECT 'edit', 'Edit privilege' UNION ALL
  SELECT 'add', 'Add privilege' UNION ALL
  SELECT 'delete', 'Delete privilege'
) actions;

INSERT IGNORE INTO security_privilege
  (privilege_id, name, description, type, read_only, properties_json)
SELECT
  CONCAT('nx-repository-view-', repo.format, '-', repo.name, '-', actions.action) AS privilege_id,
  CONCAT('nx-repository-view-', repo.format, '-', repo.name, '-', actions.action) AS name,
  CONCAT(actions.label, ' for ', repo.name, ' repository views') AS description,
  'repository-view' AS type,
  1 AS read_only,
  JSON_OBJECT('format', repo.format, 'repository', repo.name, 'actions', actions.action) AS properties_json
FROM (
  SELECT name, LOWER(format) AS format
  FROM repository
) repo
CROSS JOIN (
  SELECT '*' AS action, 'All privileges' AS label UNION ALL
  SELECT 'browse', 'Browse privilege' UNION ALL
  SELECT 'read', 'Read privilege' UNION ALL
  SELECT 'edit', 'Edit privilege' UNION ALL
  SELECT 'add', 'Add privilege' UNION ALL
  SELECT 'delete', 'Delete privilege'
) actions;

INSERT IGNORE INTO security_privilege
  (privilege_id, name, description, type, read_only, properties_json)
SELECT
  CONCAT('nx-repository-admin-', repo.format, '-', repo.name, '-', actions.action) AS privilege_id,
  CONCAT('nx-repository-admin-', repo.format, '-', repo.name, '-', actions.action) AS name,
  CONCAT(actions.label, ' for ', repo.name, ' repository administration') AS description,
  'repository-admin' AS type,
  1 AS read_only,
  JSON_OBJECT('format', repo.format, 'repository', repo.name, 'actions', actions.action) AS properties_json
FROM (
  SELECT name, LOWER(format) AS format
  FROM repository
) repo
CROSS JOIN (
  SELECT '*' AS action, 'All privileges' AS label UNION ALL
  SELECT 'browse', 'Browse privilege' UNION ALL
  SELECT 'read', 'Read privilege' UNION ALL
  SELECT 'edit', 'Edit privilege' UNION ALL
  SELECT 'delete', 'Delete privilege'
) actions;
