-- Fingerprint of the API key secret a bearer was issued from; a key reset changes it on every replica.
-- MySQL commits each ALTER independently. Check information_schema before every column so a
-- repaired Flyway migration can resume after either column was already committed.
SET @kkrepo_docker_bearer_generation_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE docker_auth_token ADD COLUMN subject_api_key_generation VARCHAR(64) NULL AFTER subject_api_key_id',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'docker_auth_token'
    AND column_name = 'subject_api_key_generation'
);
PREPARE kkrepo_docker_bearer_generation_statement FROM @kkrepo_docker_bearer_generation_sql;
EXECUTE kkrepo_docker_bearer_generation_statement;
DEALLOCATE PREPARE kkrepo_docker_bearer_generation_statement;

SET @kkrepo_conan_bearer_generation_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE conan_auth_token ADD COLUMN api_key_generation VARCHAR(64) NULL AFTER api_key_id',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'conan_auth_token'
    AND column_name = 'api_key_generation'
);
PREPARE kkrepo_conan_bearer_generation_statement FROM @kkrepo_conan_bearer_generation_sql;
EXECUTE kkrepo_conan_bearer_generation_statement;
DEALLOCATE PREPARE kkrepo_conan_bearer_generation_statement;
