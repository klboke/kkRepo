CREATE TABLE IF NOT EXISTS maven_proxy_publication_time (
    repository_id BIGINT UNSIGNED NOT NULL,
    source_version_hash BINARY(32) NOT NULL,
    published_epoch_seconds BIGINT NOT NULL,
    verified_epoch_seconds BIGINT NOT NULL,
    PRIMARY KEY (repository_id, source_version_hash),
    FOREIGN KEY (repository_id) REFERENCES repository(id) ON DELETE CASCADE
) ENGINE=InnoDB;
