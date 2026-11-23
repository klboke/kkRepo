CREATE TABLE outbound_proxy_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    proxy_type VARCHAR(16),
    proxy_host VARCHAR(255),
    proxy_port INTEGER,
    proxy_username VARCHAR(255),
    proxy_password TEXT,
    bypass_hosts TEXT NOT NULL
);

INSERT INTO outbound_proxy_settings (id, revision, enabled, bypass_hosts)
VALUES (1, 0, FALSE, '');
