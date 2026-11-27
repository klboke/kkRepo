INSERT INTO maintenance_cursor (task_name, last_seen_id)
VALUES ('repository_credential_key_upgrade_v1', 0)
ON DUPLICATE KEY UPDATE task_name = task_name;
