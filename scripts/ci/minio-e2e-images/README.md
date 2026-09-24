These images are isolated Swift/S3 compatibility fixtures, not production storage images.
MinIO removed its community container images and legacy binary downloads. The Dockerfile
builds the same releases previously used by `docker-compose.swift-s3-e2e.yml` from the
official [server](https://github.com/minio/minio/tree/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e)
and [client](https://github.com/minio/mc/tree/b00526b153a31b36767991a4f5ce2cced435ee8e)
commits, with source archive checksums and upstream license files.

From the repository root, rebuild after changing the fixtures:

```sh
docker compose -f docker-compose.swift-s3-e2e.yml build minio minio-init
```

CI builds them before loading the Swift and application images, then clears its builder
cache to reclaim disk space. Local builds retain BuildKit's Go module and compilation
caches. No replacement MinIO images are downloaded or published.
