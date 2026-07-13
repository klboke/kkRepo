-- Store Maven repository format with the Nexus-native maven2 identifier everywhere.
-- Older development databases may contain the previous internal value "maven".

UPDATE cleanup_policy
SET format = 'maven2'
WHERE LOWER(format) = 'maven';

UPDATE repository
SET format = 'maven2'
WHERE LOWER(format) = 'maven';

UPDATE component
SET format = 'maven2'
WHERE LOWER(format) = 'maven';

UPDATE asset
SET format = 'maven2'
WHERE LOWER(format) = 'maven';

UPDATE component_search
SET format = 'maven2'
WHERE LOWER(format) = 'maven';

UPDATE security_repository_target
SET format = 'maven2'
WHERE LOWER(format) = 'maven';

UPDATE security_privilege
SET properties_json = JSON_SET(properties_json, '$.format', 'maven2')
WHERE LOWER(JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.format'))) = 'maven';

UPDATE security_privilege
SET properties_json = JSON_SET(properties_json, '$.repositoryFormat', 'maven2')
WHERE LOWER(JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.repositoryFormat'))) = 'maven';
