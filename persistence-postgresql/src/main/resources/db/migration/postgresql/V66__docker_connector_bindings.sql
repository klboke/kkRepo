-- One shared port namespace for HTTP and HTTPS, including concurrent repository writes.
CREATE TABLE docker_connector_binding (
  port INT NOT NULL PRIMARY KEY,
  repository_id BIGINT NOT NULL,
  scheme VARCHAR(5) NOT NULL,
  CONSTRAINT uk_docker_connector_repository_scheme UNIQUE (repository_id, scheme),
  CONSTRAINT fk_docker_connector_repository FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT ck_docker_connector_port CHECK (port BETWEEN 1 AND 65535),
  CONSTRAINT ck_docker_connector_scheme CHECK (scheme IN ('http', 'https'))
);

INSERT INTO docker_connector_binding (port, repository_id, scheme)
SELECT (attributes_json #>> '{docker,connectorPort}')::integer, id, 'http' FROM repository
WHERE format = 'docker'
  AND attributes_json #>> '{docker,connectorPort}' IS NOT NULL
  AND COALESCE((attributes_json #>> '{docker,connectorEnabled}')::boolean, true);
