-- Roles are one local permission definition set shared by local, LDAP, and OIDC
-- users. User sources remain distinct, but role definitions are normalized to
-- the default Nexus authorization source.

UPDATE security_role
SET source = 'default'
WHERE source <> 'default';
