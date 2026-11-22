-- Fingerprint of the API key secret a bearer was issued from; a key reset changes it on every replica.
ALTER TABLE docker_auth_token
  ADD COLUMN subject_api_key_generation VARCHAR(64) NULL;
ALTER TABLE conan_auth_token
  ADD COLUMN api_key_generation VARCHAR(64) NULL;
