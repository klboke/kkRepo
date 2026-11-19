CREATE TABLE staging_group_change (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_id BIGINT UNSIGNED NOT NULL,
  group_repository_id BIGINT UNSIGNED NOT NULL,
  member_repository_id BIGINT UNSIGNED NOT NULL,
  action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  position INT NULL,
  status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_staging_group_change (session_id, group_repository_id, member_repository_id),
  INDEX idx_staging_group_change_group (group_repository_id),
  CONSTRAINT fk_staging_group_change_session
    FOREIGN KEY (session_id) REFERENCES staging_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_staging_group_change_group
    FOREIGN KEY (group_repository_id) REFERENCES repository(id) ON DELETE CASCADE,
  CONSTRAINT fk_staging_group_change_member
    FOREIGN KEY (member_repository_id) REFERENCES repository(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
