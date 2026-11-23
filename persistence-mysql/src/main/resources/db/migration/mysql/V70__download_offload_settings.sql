CREATE TABLE IF NOT EXISTS download_offload_settings (
  id SMALLINT PRIMARY KEY,
  revision BIGINT NOT NULL,
  enabled BOOLEAN NOT NULL,
  method VARCHAR(32) NOT NULL,
  custom_header VARCHAR(128) NOT NULL,
  custom_target VARCHAR(16) NOT NULL,
  blob_store_ids TEXT NOT NULL
);

INSERT INTO download_offload_settings (id, revision, enabled, method, custom_header, custom_target, blob_store_ids)
VALUES (1, 0, FALSE, 'nginx', 'X-KkRepo-File', 'internal-uri', '')
ON DUPLICATE KEY UPDATE id = id;
