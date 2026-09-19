-- Concurrent builds preserve writes; dropping first permits recovery of an invalid index.
DROP INDEX CONCURRENTLY IF EXISTS idx_asset_repository_usage;
CREATE INDEX CONCURRENTLY idx_asset_repository_usage ON asset (repository_id, size);
