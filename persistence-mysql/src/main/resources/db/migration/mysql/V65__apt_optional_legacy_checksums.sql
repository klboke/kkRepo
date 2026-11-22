-- Upstream Packages may omit MD5sum/SHA1; SHA256 remains required and verified.
ALTER TABLE apt_package_record
  MODIFY COLUMN md5 CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  MODIFY COLUMN sha1 CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ALGORITHM=INPLACE, LOCK=NONE;
