ALTER TABLE docker_tag
  ADD INDEX idx_docker_tag_repo_updated (repository_id, updated_at DESC, id DESC),
  ALGORITHM=INPLACE, LOCK=NONE;
