UPDATE asset
SET content_type = 'application/x-tgz'
WHERE format = 'npm'
  AND (kind = 'tarball' OR LOWER(path) LIKE '%.tgz')
  AND (content_type IS NULL OR content_type <> 'application/x-tgz');

UPDATE asset_blob b
JOIN (
  SELECT a.asset_blob_id
  FROM asset a
  WHERE a.asset_blob_id IS NOT NULL
  GROUP BY a.asset_blob_id
  HAVING SUM(CASE
      WHEN a.format = 'npm' AND (a.kind = 'tarball' OR LOWER(a.path) LIKE '%.tgz') THEN 1
      ELSE 0
    END) > 0
    AND SUM(CASE
      WHEN a.format = 'npm' AND (a.kind = 'tarball' OR LOWER(a.path) LIKE '%.tgz') THEN 0
      ELSE 1
    END) = 0
) npm_tarball_blob ON npm_tarball_blob.asset_blob_id = b.id
SET b.content_type = 'application/x-tgz'
WHERE b.content_type IS NULL OR b.content_type <> 'application/x-tgz';
