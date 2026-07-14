ALTER TABLE repository
  ADD COLUMN docker_connector_port INT GENERATED ALWAYS AS (
    CASE
      WHEN format = 'docker'
        AND JSON_EXTRACT(attributes_json, '$.docker.connectorPort') IS NOT NULL
        AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.docker.connectorEnabled')), 'true') = 'true'
      THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.docker.connectorPort')) AS UNSIGNED)
      ELSE NULL
    END
  ) STORED,
  ADD UNIQUE KEY uk_repository_docker_connector_port (docker_connector_port);
