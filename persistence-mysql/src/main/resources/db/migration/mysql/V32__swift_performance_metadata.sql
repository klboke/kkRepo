ALTER TABLE swift_manifest
  ADD COLUMN declared_tools_version VARCHAR(32) NOT NULL DEFAULT '' AFTER sha256;

UPDATE swift_manifest m
JOIN asset a ON a.id = m.asset_id
SET m.declared_tools_version = COALESCE(
    JSON_UNQUOTE(JSON_EXTRACT(a.attributes_json, '$.declaredSwiftToolsVersion')),
    ''
  )
WHERE m.declared_tools_version = '';
