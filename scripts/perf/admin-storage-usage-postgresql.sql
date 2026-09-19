-- Same skewed inventory as the MySQL fixture, with the complete migrated schema.
INSERT INTO blob_store (id,name,type,attributes_json)
SELECT n,'usage-store-'||n,'FILE',jsonb_build_object('path','/tmp/usage-store-'||n) FROM generate_series(1,10) n;
INSERT INTO repository (id,name,format,type,recipe_name,blob_store_id,attributes_json)
SELECT n,'usage-repo-'||n,'raw','hosted','raw-hosted',1+(n-1)/10,'{}'::jsonb FROM generate_series(1,100) n;
INSERT INTO asset_blob (id,blob_store_id,blob_ref,blob_ref_hash,object_key,object_key_hash,size,deleted_at,attributes_json)
SELECT n,CASE WHEN n<=600000 THEN 1 ELSE 1+(1+(n-600001)%99)/10 END,
 'file:usage/'||n,decode(lpad(to_hex(n),64,'0'),'hex'),'objects/'||n,decode(lpad(to_hex(n),64,'0'),'hex'),
 1024+n%4096,CASE WHEN n>900000 THEN CURRENT_TIMESTAMP ELSE NULL END,jsonb_build_object('fixture',repeat('x',256))
FROM generate_series(1,1000000) n;
INSERT INTO asset (id,repository_id,asset_blob_id,format,path,path_hash,name,kind,size,attributes_json)
SELECT n,CASE WHEN n<=600000 THEN 1 WHEN n>800000 THEN 2+n%9 ELSE 2+(n-600001)%99 END,
 CASE WHEN n>800000 THEN n-800000 ELSE n END,'raw','packages/'||n||'.bin',decode(lpad(to_hex(n),64,'0'),'hex'),
 n||'.bin','PACKAGE',1024+(CASE WHEN n>800000 THEN n-800000 ELSE n END)%4096,jsonb_build_object('fixture',repeat('x',256))
FROM generate_series(1,1000000) n;
INSERT INTO blob_reference (owner_type,owner_id,blob_id,created_at)
SELECT 'usage-report',n,n,CURRENT_TIMESTAMP FROM generate_series(800001,900000) n;
UPDATE asset_blob SET external_reference_count=1 WHERE id>800000 AND id<=900000;
VACUUM (ANALYZE) asset;
VACUUM (ANALYZE) asset_blob;
SELECT 'dataset',(SELECT COUNT(*) FROM asset),(SELECT COUNT(*) FROM asset_blob);
EXPLAIN (ANALYZE,BUFFERS) SELECT blob_store_id,COUNT(*),COALESCE(SUM(size),0),SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END),SUM(CASE WHEN deleted_at IS NOT NULL THEN size ELSE 0 END) FROM asset_blob GROUP BY blob_store_id;
EXPLAIN (ANALYZE,BUFFERS) SELECT repository_id,COUNT(*),COALESCE(SUM(size),0),COUNT(*)-COUNT(size) FROM asset GROUP BY repository_id;
