-- PostgreSQL does not automatically index referencing FK columns.
-- Keep unused-policy checks and FK enforcement bounded as scan state grows.
DROP INDEX CONCURRENTLY IF EXISTS idx_repository_scan_policy_ref;
CREATE INDEX CONCURRENTLY idx_repository_scan_policy_ref ON repository_security_scan_config (policy_id);
DROP INDEX CONCURRENTLY IF EXISTS idx_asset_scan_policy_ref;
CREATE INDEX CONCURRENTLY idx_asset_scan_policy_ref ON asset_security_state (policy_id);
DROP INDEX CONCURRENTLY IF EXISTS idx_asset_context_policy_ref;
CREATE INDEX CONCURRENTLY idx_asset_context_policy_ref ON asset_security_policy_state (policy_id);
DROP INDEX CONCURRENTLY IF EXISTS idx_scan_waiver_policy_ref;
CREATE INDEX CONCURRENTLY idx_scan_waiver_policy_ref ON security_scan_waiver (policy_id);
