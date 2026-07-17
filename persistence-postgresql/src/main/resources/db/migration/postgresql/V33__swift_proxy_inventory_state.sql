CREATE TABLE swift_proxy_inventory (
  repository_id BIGINT NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
  scope_lc VARCHAR(64) NOT NULL,
  name_lc VARCHAR(128) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  last_checked_at TIMESTAMPTZ(3) NOT NULL,
  pages_json JSONB NOT NULL,
  page_etags_json JSONB NOT NULL,
  updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (repository_id, scope_lc, name_lc)
);

INSERT INTO swift_proxy_inventory
  (repository_id, scope_lc, name_lc, revision, last_checked_at, pages_json, page_etags_json)
SELECT repository_id,
       scope_lc,
       name_lc,
       COALESCE(MAX(revision), 0),
       MAX(last_checked_at),
       '{}'::jsonb,
       '{}'::jsonb
FROM swift_proxy_source
GROUP BY repository_id, scope_lc, name_lc;
