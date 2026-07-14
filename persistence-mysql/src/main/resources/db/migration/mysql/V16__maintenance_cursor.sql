CREATE TABLE maintenance_cursor (
  task_name VARCHAR(100) NOT NULL,
  last_seen_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (task_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO maintenance_cursor (task_name, last_seen_id)
VALUES ('blob_unreferenced_reconcile', 0);
