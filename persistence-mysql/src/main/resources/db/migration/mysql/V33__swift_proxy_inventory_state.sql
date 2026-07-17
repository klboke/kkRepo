CREATE TABLE swift_proxy_inventory (
  repository_id BIGINT UNSIGNED NOT NULL,
  scope_lc VARCHAR(64) NOT NULL,
  name_lc VARCHAR(128) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  last_checked_at DATETIME(3) NOT NULL,
  pages_json JSON NOT NULL,
  page_etags_json JSON NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (repository_id, scope_lc, name_lc),
  CONSTRAINT fk_swift_proxy_inventory_repository
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO swift_proxy_inventory
  (repository_id, scope_lc, name_lc, revision, last_checked_at, pages_json, page_etags_json)
SELECT repository_id,
       scope_lc,
       name_lc,
       COALESCE(MAX(revision), 0),
       MAX(last_checked_at),
       JSON_OBJECT(),
       JSON_OBJECT()
FROM swift_proxy_source
GROUP BY repository_id, scope_lc, name_lc;
