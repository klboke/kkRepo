ALTER TABLE swift_manifest
  ADD COLUMN declared_tools_version VARCHAR(32) NOT NULL DEFAULT '';

UPDATE swift_manifest m
SET declared_tools_version = COALESCE(a.attributes_json ->> 'declaredSwiftToolsVersion', '')
FROM asset a
WHERE a.id = m.asset_id
  AND m.declared_tools_version = '';
