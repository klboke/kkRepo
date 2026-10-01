DROP INDEX CONCURRENTLY IF EXISTS idx_docker_tag_repo_updated;
CREATE INDEX CONCURRENTLY idx_docker_tag_repo_updated ON docker_tag (repository_id, updated_at DESC, id DESC);
