-- One shared port namespace for HTTP and HTTPS, including concurrent repository writes.
CREATE TABLE IF NOT EXISTS docker_connector_binding (
  port INT NOT NULL PRIMARY KEY,
  repository_id BIGINT UNSIGNED NOT NULL,
  scheme VARCHAR(5) NOT NULL,
  CONSTRAINT uk_docker_connector_repository_scheme UNIQUE (repository_id, scheme),
  CONSTRAINT fk_docker_connector_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT ck_docker_connector_port CHECK (port BETWEEN 1 AND 65535),
  CONSTRAINT ck_docker_connector_scheme CHECK (scheme IN ('http', 'https'))
);

INSERT INTO docker_connector_binding (port, repository_id, scheme)
SELECT r.docker_connector_port, r.id, 'http' FROM repository r
WHERE r.docker_connector_port IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM docker_connector_binding b
    WHERE b.port = r.docker_connector_port AND b.repository_id = r.id AND b.scheme = 'http'
  );
