-- Upstream Packages may omit MD5sum/SHA1; SHA256 remains required and verified.
ALTER TABLE apt_package_record
  ALTER COLUMN md5 DROP NOT NULL,
  ALTER COLUMN sha1 DROP NOT NULL;
