-- Keep existing deployments unchanged while making the final state of a fresh,
-- not-yet-initialized database default to anonymous access disabled.
UPDATE security_anonymous_config
SET enabled = 0
WHERE id = 1
  AND enabled = 1
  AND user_source = 'Local'
  AND user_id = 'anonymous'
  AND realm_name = 'NexusAuthorizingRealm'
  AND NOT EXISTS (
    SELECT 1
    FROM security_user
    WHERE NOT (source = 'Local' AND user_id = 'anonymous')
  );
