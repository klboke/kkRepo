-- Rename the local security source from the implementation-oriented
-- "default" value to the user-facing "Local" value.

UPDATE security_user
SET source = 'Local'
WHERE source = 'default';

UPDATE security_role
SET source = 'Local'
WHERE source = 'default';

UPDATE security_realm
SET attributes_json = JSON_SET(attributes_json, '$.source', 'Local')
WHERE realm_id = 'local'
  AND JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.source')) = 'default';

UPDATE security_anonymous_config
SET user_source = 'Local'
WHERE user_source = 'default';

UPDATE api_key
SET owner_source = 'Local'
WHERE owner_source = 'default';
