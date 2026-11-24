-- Fingerprint of the API key secret a bearer was issued from; a key reset changes it on every replica.
-- IF NOT EXISTS keeps a manually repaired or partially applied schema restart-safe.
ALTER TABLE docker_auth_token
  ADD COLUMN IF NOT EXISTS subject_api_key_generation VARCHAR(64) NULL;
ALTER TABLE conan_auth_token
  ADD COLUMN IF NOT EXISTS api_key_generation VARCHAR(64) NULL;
