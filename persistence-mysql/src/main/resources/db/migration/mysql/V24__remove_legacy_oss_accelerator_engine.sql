UPDATE blob_store
SET attributes_json = JSON_SET(attributes_json, '$.engine', 'oss-native')
WHERE type = 's3'
  AND JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.engine')) IN ('jindo', 'jindo-oss');
