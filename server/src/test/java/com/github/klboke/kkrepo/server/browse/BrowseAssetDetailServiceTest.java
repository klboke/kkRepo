package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.docker.DockerTagRecord;
import com.github.klboke.kkrepo.persistence.mysql.support.HashColumns;
import com.github.klboke.kkrepo.persistence.mysql.support.JsonColumns;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class BrowseAssetDetailServiceTest {

  @Test
  void composerInternalRouteDetailIsNotExposedAsDownloadableAsset() {
    RepositoryRecord repository = repository(
        1L, "composer-proxy", RepositoryFormat.COMPOSER, RepositoryType.PROXY);
    StubAssetDao assets = new StubAssetDao(Map.of(), Map.of());
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.detail(repository, "_composer/routes/token.json", null));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    assertEquals(List.of(), assets.pathLookups);
  }

  @Test
  void composerProxyDetailInfersPackageCoordinatesFromNexusPathForUsage() {
    RepositoryRecord repository = repository(
        1L, "composer-proxy", RepositoryFormat.COMPOSER, RepositoryType.PROXY);
    String path = "company/example/1.2.3/company-example-1.2.3.zip";
    AssetRecord archive = new AssetRecord(
        10L,
        repository.id(),
        null,
        100L,
        RepositoryFormat.COMPOSER,
        path,
        HashColumns.pathHash(path),
        "company-example-1.2.3.zip",
        "composer-dist",
        "application/zip",
        1024L,
        null,
        Instant.parse("2026-07-12T00:00:00Z"),
        Map.of());
    AssetBlobRecord blob = blob(100L, 1024L);
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(repository.id(), path), archive),
        Map.of(blob.id(), blob));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(repository, path, null);

    assertEquals("DIST", detail.composer().get("asset_kind"));
    assertEquals("company/example", detail.composer().get("package_name"));
    assertEquals("1.2.3", detail.composer().get("version"));
  }

  @Test
  void composerHostedDetailUsesAssetUploaderWhenBlobWasReused() {
    RepositoryRecord repository = repository(
        1L, "composer-hosted", RepositoryFormat.COMPOSER, RepositoryType.HOSTED);
    String path = "company/example/1.2.3/company-example-1.2.3.zip";
    AssetRecord archive = new AssetRecord(
        10L,
        repository.id(),
        null,
        100L,
        RepositoryFormat.COMPOSER,
        path,
        HashColumns.pathHash(path),
        "company-example-1.2.3.zip",
        "composer-dist",
        "application/zip",
        1024L,
        null,
        Instant.parse("2026-07-12T00:00:00Z"),
        Map.of("createdBy", "admin", "createdByIp", "127.0.0.1"));
    AssetBlobRecord reusedBlob = new AssetBlobRecord(
        100L,
        1L,
        "s3://bucket/composer/company-example-1.2.3.zip",
        HashColumns.pathHash("s3://bucket/composer/company-example-1.2.3.zip"),
        "composer/company-example-1.2.3.zip",
        HashColumns.pathHash("composer/company-example-1.2.3.zip"),
        "sha1",
        "sha256",
        "md5",
        1024L,
        "application/zip",
        "system",
        null,
        Instant.parse("2026-07-11T00:00:00Z"),
        Instant.parse("2026-07-11T00:00:00Z"),
        Map.of());
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(repository.id(), path), archive),
        Map.of(reusedBlob.id(), reusedBlob));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(repository, path, null);

    assertEquals("admin", detail.uploader());
    assertEquals("127.0.0.1", detail.uploaderIp());
  }

  @Test
  void npmTarballWithoutRootDocumentFallsBackToTarballPackageJson() throws IOException {
    RepositoryRecord repository = repository(1L, "npm-hosted", RepositoryFormat.NPM, RepositoryType.HOSTED);
    String tarballPath = "demo/-/demo-1.0.0.tgz";
    byte[] tarballBytes = tarball("""
        {"name":"demo","version":"1.0.0","license":"MIT","keywords":["browse","fallback"]}
        """);
    AssetRecord tarball = asset(10L, repository.id(), 100L, tarballPath);
    AssetBlobRecord blob = blob(100L, tarballBytes.length);
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(repository.id(), tarballPath), tarball),
        Map.of(blob.id(), blob));
    StubBlobStorage storage = new StubBlobStorage(tarballBytes);
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        new StubBlobStorageRegistry(storage),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(repository, tarballPath, null);

    assertEquals("TARBALL", detail.npm().get("asset_kind"));
    assertEquals("MIT", detail.npm().get("license"));
    assertEquals("browse fallback", detail.npm().get("keywords"));
    assertEquals("demo", detail.npm().get("name"));
    assertEquals("1.0.0", detail.npm().get("version"));
    assertEquals(1, storage.gets);
    assertEquals(List.of(key(repository.id(), tarballPath), key(repository.id(), "demo")), assets.pathLookups);
  }

  @Test
  void pypiPublicPathDownloadUrlUsesPackagesProtocolPath() {
    RepositoryRecord repository = repository(1L, "pypi-hosted", RepositoryFormat.PYPI, RepositoryType.HOSTED);
    String publicPath = "demo/1.0.0/demo-1.0.0-py3-none-any.whl";
    String storagePath = "packages/" + publicPath;
    AssetRecord wheel = asset(10L, repository.id(), 100L, storagePath, RepositoryFormat.PYPI);
    AssetBlobRecord blob = blob(100L, 952L);
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(repository.id(), storagePath), wheel),
        Map.of(blob.id(), blob));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(repository, publicPath, null);

    assertEquals("/repository/pypi-hosted/packages/demo/1.0.0/demo-1.0.0-py3-none-any.whl", detail.downloadUrl());
    assertEquals(List.of(key(repository.id(), storagePath)), assets.pathLookups);
  }

  @Test
  void pubArchiveDetailExposesStoredPubMetadata() {
    RepositoryRecord repository = repository(1L, "pub-hosted", RepositoryFormat.PUB, RepositoryType.HOSTED);
    String path = "packages/example_package/versions/1.0.0.tar.gz";
    Map<String, Object> pubAttributes = new LinkedHashMap<>();
    pubAttributes.put("packageName", "example_package");
    pubAttributes.put("version", "1.0.0");
    pubAttributes.put("archiveSha256", "a".repeat(64));
    pubAttributes.put("archiveSize", 512L);
    pubAttributes.put("publishedAt", "2026-07-08T00:00:00Z");
    pubAttributes.put("publishSource", "pub-client");
    pubAttributes.put("publishedBy", "alice");
    pubAttributes.put("publishApiKeyId", 42L);
    pubAttributes.put("uploadSessionId", "session-1");
    pubAttributes.put("sourceClient", "Dart pub 3.9.0");
    pubAttributes.put("pubspec", Map.of(
        "name", "example_package",
        "version", "1.0.0",
        "description", "Demo package",
        "environment", Map.of("sdk", "^3.0.0")));
    AssetRecord archive = new AssetRecord(
        10L,
        repository.id(),
        null,
        100L,
        RepositoryFormat.PUB,
        path,
        HashColumns.pathHash(path),
        "1.0.0.tar.gz",
        "archive",
        "application/octet-stream",
        512L,
        null,
        Instant.parse("2026-07-08T00:00:00Z"),
        pubAttributes);
    AssetBlobRecord blob = blob(100L, 512L);
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(repository.id(), path), archive),
        Map.of(blob.id(), blob));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(repository, path, null);

    assertEquals("archive", detail.pub().get("asset_kind"));
    assertEquals("example_package", detail.pub().get("package_name"));
    assertEquals("1.0.0", detail.pub().get("version"));
    assertEquals("a".repeat(64), detail.pub().get("archive_sha256"));
    assertEquals(512L, detail.pub().get("archive_size_bytes"));
    assertEquals("pub-client", detail.pub().get("publish_source"));
    assertEquals("alice", detail.pub().get("published_by"));
    assertEquals(42L, detail.pub().get("publish_api_key_id"));
    assertEquals("session-1", detail.pub().get("upload_session_id"));
    assertEquals("Dart pub 3.9.0", detail.pub().get("source_client"));
    assertEquals("Demo package", detail.pub().get("description"));
    assertEquals(Map.of("sdk", "^3.0.0"), detail.pub().get("environment"));
    assertEquals("/repository/pub-hosted/" + path, detail.downloadUrl());
  }

  @Test
  void cargoDynamicConfigDetailDoesNotRequireStoredAssetBlob() {
    RepositoryRecord repository = repository(1L, "cargo-hosted", RepositoryFormat.CARGO, RepositoryType.HOSTED);
    StubAssetDao assets = new StubAssetDao(Map.of(), Map.of());
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(repository, "config.json", null);

    assertEquals("cargo-hosted", detail.repository());
    assertEquals("config.json", detail.path());
    assertEquals("config.json", detail.name());
    assertEquals("application/json", detail.contentType());
    assertEquals("/repository/cargo-hosted/config.json", detail.downloadUrl());
    assertEquals("kkrepo", detail.uploader());
    assertTrue((Boolean) detail.content().get("generated"));
    assertTrue((Boolean) detail.provenance().get("dynamic"));
    assertEquals(List.of(), assets.pathLookups);
  }

  @Test
  void dockerAssetDetailExposesDockerMetadata() {
    RepositoryRecord repository = repository(1L, "docker-hosted", RepositoryFormat.DOCKER, RepositoryType.HOSTED);
    String digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    String path = "docker/manifests/library/alpine/sha256/" + digest.substring("sha256:".length());
    AssetRecord manifest = new AssetRecord(
        10L,
        repository.id(),
        null,
        100L,
        RepositoryFormat.DOCKER,
        path,
        HashColumns.pathHash(path),
        "library/alpine@" + digest,
        "MANIFEST",
        "application/vnd.oci.image.manifest.v1+json",
        1024L,
        null,
        Instant.parse("2026-05-27T00:00:00Z"),
        Map.of("docker", Map.of(
            "imageName", "library/alpine",
            "reference", "latest",
            "digest", digest,
            "mediaType", "application/vnd.oci.image.manifest.v1+json")));
    AssetBlobRecord blob = blob(100L, 1024L);
    String configDigest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    String layerOne = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    String layerTwo = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    String referrerDigest = "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    AssetRecord cachedLayer = dockerBlobAsset(20L, repository.id(), 200L, layerOne);
    AssetBlobRecord layerBlob = dockerBlob(200L, layerOne, 4096L);
    StubAssetDao assets = new StubAssetDao(
        Map.of(
            key(repository.id(), path), manifest,
            key(repository.id(), cachedLayer.path()), cachedLayer),
        Map.of(
            blob.id(), blob,
            layerBlob.id(), layerBlob));
    DockerManifestRecord manifestRecord = dockerManifest(50L, repository.id(), manifest.id(), "library/alpine", digest,
        "application/vnd.oci.image.manifest.v1+json", 1024L);
    DockerManifestRecord referrerRecord = dockerManifest(51L, repository.id(), 11L, "library/alpine", referrerDigest,
        "application/vnd.oci.image.manifest.v1+json", 321L);
    StubDockerRegistryDao docker = new StubDockerRegistryDao(
        List.of(manifestRecord, referrerRecord),
        Map.of(manifestRecord.id(), List.of(
            reference(manifestRecord, "CONFIG", configDigest, 512L, Map.of()),
            reference(manifestRecord, "LAYER", layerOne, 4096L, Map.of()),
            reference(manifestRecord, "LAYER", layerTwo, 8192L, Map.of()))),
        Map.of(manifestRecord.id(), List.of(tag(manifestRecord, "latest"))),
        Map.of(digest, List.of(referrerRecord)));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        docker,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(repository, path, null);

    assertEquals("MANIFEST", detail.docker().get("asset_kind"));
    assertEquals("library/alpine", detail.docker().get("image_name"));
    assertEquals("latest", detail.docker().get("reference"));
    assertEquals(digest, detail.docker().get("digest"));
    assertEquals("application/vnd.oci.image.manifest.v1+json", detail.docker().get("media_type"));
    assertEquals(1024L, detail.docker().get("manifest_size_bytes"));
    assertEquals(2, detail.docker().get("layer_count"));
    assertEquals(12288L, detail.docker().get("layer_size_bytes"));
    assertEquals(1, detail.docker().get("cached_layer_count"));
    assertEquals(4096L, detail.docker().get("cached_layer_size_bytes"));
    assertEquals(12800L, detail.docker().get("referenced_size_bytes"));
    assertEquals(List.of("latest"), detail.docker().get("tags"));
    assertEquals(1, detail.docker().get("referrer_count"));
    assertEquals(1, ((List<?>) detail.docker().get("config_descriptors")).size());
    assertEquals(2, ((List<?>) detail.docker().get("layer_descriptors")).size());
    assertEquals(1, ((List<?>) detail.docker().get("referrers")).size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void dockerIndexDetailSummarizesPlatformsAndCachedChildImage() {
    RepositoryRecord repository = repository(1L, "docker-proxy", RepositoryFormat.DOCKER, RepositoryType.PROXY);
    String indexDigest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    String childDigest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    String armDigest = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    String layerDigest = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    String path = "docker/manifests/library/redis/sha256/" + indexDigest.substring("sha256:".length());
    AssetRecord indexAsset = dockerManifestAsset(
        10L,
        repository.id(),
        100L,
        "library/redis",
        indexDigest,
        "application/vnd.oci.image.index.v1+json",
        2048L);
    AssetRecord childAsset = dockerManifestAsset(
        11L,
        repository.id(),
        101L,
        "library/redis",
        childDigest,
        "application/vnd.oci.image.manifest.v1+json",
        700L);
    AssetRecord layerAsset = dockerBlobAsset(20L, repository.id(), 200L, layerDigest);
    AssetBlobRecord indexBlob = dockerManifestBlob(100L, indexDigest, 2048L, "application/vnd.oci.image.index.v1+json");
    AssetBlobRecord childBlob = dockerManifestBlob(101L, childDigest, 700L, "application/vnd.oci.image.manifest.v1+json");
    AssetBlobRecord layerBlob = dockerBlob(200L, layerDigest, 8192L);
    StubAssetDao assets = new StubAssetDao(
        Map.of(
            key(repository.id(), indexAsset.path()), indexAsset,
            key(repository.id(), childAsset.path()), childAsset,
            key(repository.id(), layerAsset.path()), layerAsset),
        Map.of(
            indexBlob.id(), indexBlob,
            childBlob.id(), childBlob,
            layerBlob.id(), layerBlob));
    DockerManifestRecord indexRecord = dockerManifest(
        50L, repository.id(), indexAsset.id(), "library/redis", indexDigest,
        "application/vnd.oci.image.index.v1+json", 2048L);
    DockerManifestRecord childRecord = dockerManifest(
        51L, repository.id(), childAsset.id(), "library/redis", childDigest,
        "application/vnd.oci.image.manifest.v1+json", 700L);
    StubDockerRegistryDao docker = new StubDockerRegistryDao(
        List.of(indexRecord, childRecord),
        Map.of(
            indexRecord.id(), List.of(
                reference(indexRecord, "MANIFEST", childDigest, 700L, Map.of(
                    "os", "linux", "architecture", "amd64")),
                reference(indexRecord, "MANIFEST", armDigest, 701L, Map.of(
                    "os", "linux", "architecture", "arm64", "variant", "v8")),
                reference(indexRecord, "MANIFEST",
                    "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                    512L,
                    Map.of("os", "unknown", "architecture", "unknown"))),
            childRecord.id(), List.of(
                reference(childRecord, "CONFIG",
                    "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                    256L,
                    Map.of()),
                reference(childRecord, "LAYER", layerDigest, 8192L, Map.of()))));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        docker,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(repository, path, null);

    assertEquals("library/redis", detail.docker().get("image_name"));
    assertEquals(indexDigest, detail.docker().get("digest"));
    assertEquals(2048L, detail.docker().get("manifest_size_bytes"));
    assertEquals(3, detail.docker().get("manifest_descriptor_count"));
    assertEquals(2, detail.docker().get("platform_count"));
    assertEquals("linux/amd64, linux/arm64/v8", detail.docker().get("platform_summary"));
    assertEquals(1, detail.docker().get("cached_platform_count"));
    assertEquals(8192L, detail.docker().get("cached_image_size_bytes"));
    assertEquals("linux/amd64", detail.docker().get("cached_image_platform"));
    List<Map<String, Object>> platforms = (List<Map<String, Object>>) detail.docker().get("platforms");
    assertEquals(3, platforms.size());
    assertEquals("linux/amd64", platforms.get(0).get("platform"));
    assertEquals(8192L, platforms.get(0).get("cached_image_size_bytes"));
    assertEquals("linux/arm64/v8", platforms.get(1).get("platform"));
    List<Map<String, Object>> manifestDescriptors =
        (List<Map<String, Object>>) detail.docker().get("manifest_descriptors");
    assertEquals(3, manifestDescriptors.size());
    assertEquals(childDigest, manifestDescriptors.get(0).get("digest"));
  }

  private static RepositoryRecord repository(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type) {
    return new RepositoryRecord(
        id,
        name,
        format,
        type,
        format.name().toLowerCase(Locale.ROOT) + "-" + type.name().toLowerCase(Locale.ROOT),
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }

  private static AssetRecord asset(long id, long repositoryId, long blobId, String path) {
    return asset(id, repositoryId, blobId, path, RepositoryFormat.NPM);
  }

  private static AssetRecord asset(
      long id,
      long repositoryId,
      long blobId,
      String path,
      RepositoryFormat format) {
    return new AssetRecord(
        id,
        repositoryId,
        null,
        blobId,
        format,
        path,
        HashColumns.pathHash(path),
        path.substring(path.lastIndexOf('/') + 1),
        "tarball",
        "application/x-tgz",
        1024L,
        null,
        Instant.parse("2026-05-27T00:00:00Z"),
        Map.of("version", "1.0.0"));
  }

  private static AssetRecord dockerManifestAsset(
      long id,
      long repositoryId,
      long blobId,
      String imageName,
      String digest,
      String mediaType,
      long size) {
    String path = "docker/manifests/" + imageName + "/sha256/" + digest.substring("sha256:".length());
    return new AssetRecord(
        id,
        repositoryId,
        null,
        blobId,
        RepositoryFormat.DOCKER,
        path,
        HashColumns.pathHash(path),
        imageName + "@" + digest,
        "MANIFEST",
        mediaType,
        size,
        null,
        Instant.parse("2026-05-27T00:00:00Z"),
        Map.of("docker", Map.of(
            "imageName", imageName,
            "digest", digest,
            "mediaType", mediaType)));
  }

  private static AssetRecord dockerBlobAsset(long id, long repositoryId, long blobId, String digest) {
    String path = "docker/blobs/sha256/" + digest.substring("sha256:".length(), "sha256:".length() + 2)
        + "/" + digest.substring("sha256:".length());
    return new AssetRecord(
        id,
        repositoryId,
        null,
        blobId,
        RepositoryFormat.DOCKER,
        path,
        HashColumns.pathHash(path),
        digest,
        "BLOB",
        "application/octet-stream",
        1024L,
        null,
        Instant.parse("2026-05-27T00:00:00Z"),
        Map.of("docker", Map.of("digest", digest, "kind", "blob")));
  }

  private static AssetBlobRecord blob(long id, long size) {
    return new AssetBlobRecord(
        id,
        1L,
        "s3://bucket/npm/demo/-/demo-1.0.0.tgz",
        HashColumns.pathHash("s3://bucket/npm/demo/-/demo-1.0.0.tgz"),
        "npm/demo/-/demo-1.0.0.tgz",
        HashColumns.pathHash("npm/demo/-/demo-1.0.0.tgz"),
        "sha1",
        "sha256",
        "md5",
        size,
        "application/x-tgz",
        "alice",
        "127.0.0.1",
        Instant.parse("2026-05-27T00:00:00Z"),
        Instant.parse("2026-05-27T00:00:00Z"),
        Map.of());
  }

  private static AssetBlobRecord dockerManifestBlob(long id, String digest, long size, String contentType) {
    return new AssetBlobRecord(
        id,
        1L,
        "s3://bucket/docker/manifests/" + digest,
        HashColumns.pathHash("s3://bucket/docker/manifests/" + digest),
        "docker/manifests/" + digest,
        HashColumns.pathHash("docker/manifests/" + digest),
        null,
        digest.substring("sha256:".length()),
        null,
        size,
        contentType,
        "proxy",
        "https://registry-1.docker.io",
        Instant.parse("2026-05-27T00:00:00Z"),
        Instant.parse("2026-05-27T00:00:00Z"),
        Map.of("dockerDigest", digest));
  }

  private static AssetBlobRecord dockerBlob(long id, String digest, long size) {
    return new AssetBlobRecord(
        id,
        1L,
        "s3://bucket/docker/blobs/" + digest,
        HashColumns.pathHash("s3://bucket/docker/blobs/" + digest),
        "docker/blobs/" + digest,
        HashColumns.pathHash("docker/blobs/" + digest),
        null,
        digest.substring("sha256:".length()),
        null,
        size,
        "application/octet-stream",
        "proxy",
        "https://registry-1.docker.io",
        Instant.parse("2026-05-27T00:00:00Z"),
        Instant.parse("2026-05-27T00:00:00Z"),
        Map.of("dockerDigest", digest));
  }

  private static DockerManifestRecord dockerManifest(
      long id,
      long repositoryId,
      long assetId,
      String imageName,
      String digest,
      String mediaType,
      long size) {
    return new DockerManifestRecord(
        id,
        repositoryId,
        imageName,
        DockerRegistryDao.hash(imageName),
        "sha256",
        digest,
        DockerRegistryDao.hash(digest),
        mediaType,
        null,
        null,
        null,
        assetId,
        size,
        "proxy",
        "https://registry-1.docker.io",
        null,
        Map.of("rawBytesDigest", digest),
        Instant.parse("2026-05-27T00:00:00Z"),
        Instant.parse("2026-05-27T00:00:00Z"));
  }

  private static DockerManifestReferenceRecord reference(
      DockerManifestRecord manifest,
      String kind,
      String digest,
      long size,
      Map<String, Object> platform) {
    return new DockerManifestReferenceRecord(
        null,
        manifest.id(),
        manifest.repositoryId(),
        manifest.imageName(),
        digest,
        DockerRegistryDao.hash(digest),
        kind,
        "application/vnd.oci.image.manifest.v1+json",
        size,
        platform,
        Map.of());
  }

  private static DockerTagRecord tag(DockerManifestRecord manifest, String tag) {
    return new DockerTagRecord(
        null,
        manifest.repositoryId(),
        manifest.imageName(),
        DockerRegistryDao.hash(manifest.imageName()),
        tag,
        DockerRegistryDao.hash(tag),
        manifest.id(),
        manifest.digest(),
        manifest.pushedBy(),
        manifest.pushedByIp(),
        Instant.parse("2026-05-27T00:00:00Z"),
        Instant.parse("2026-05-27T00:00:00Z"));
  }

  private static String key(long repositoryId, String path) {
    return repositoryId + "|" + path;
  }

  private static byte[] tarball(String packageJson) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      byte[] packageJsonBytes = packageJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry("package/package.json");
      entry.setSize(packageJsonBytes.length);
      tar.putArchiveEntry(entry);
      tar.write(packageJsonBytes);
      tar.closeArchiveEntry();
    }
    return bytes.toByteArray();
  }

  private static class StubRepositoryDao extends RepositoryDao {
    private StubRepositoryDao() {
      super(null, null);
    }
  }

  private static class StubAssetDao extends AssetDao {
    private final Map<String, AssetRecord> assetsByPath;
    private final Map<Long, AssetBlobRecord> blobsById;
    private final Map<String, AssetRecord> dockerBlobAssetsBySha256;
    private final ArrayList<String> pathLookups = new ArrayList<>();

    private StubAssetDao(Map<String, AssetRecord> assetsByPath, Map<Long, AssetBlobRecord> blobsById) {
      super(null, null);
      this.assetsByPath = assetsByPath;
      this.blobsById = blobsById;
      this.dockerBlobAssetsBySha256 = assetsByPath.values().stream()
          .filter(asset -> asset.format() == RepositoryFormat.DOCKER)
          .filter(asset -> "BLOB".equals(asset.kind()))
          .collect(Collectors.toMap(
              asset -> asset.repositoryId() + "|" + dockerSha256(asset),
              asset -> asset,
              (left, right) -> left));
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      pathLookups.add(key(repositoryId, path));
      return Optional.ofNullable(assetsByPath.get(key(repositoryId, path)));
    }

    @Override
    public Optional<AssetBlobRecord> findBlobById(long assetBlobId) {
      return Optional.ofNullable(blobsById.get(assetBlobId));
    }

    @Override
    public Optional<AssetRecord> findAssetById(long assetId) {
      return assetsByPath.values().stream()
          .filter(asset -> asset.id() == assetId)
          .findFirst();
    }

    @Override
    public Optional<AssetRecord> findDockerBlobAssetBySha256(long repositoryId, String sha256) {
      return Optional.ofNullable(dockerBlobAssetsBySha256.get(repositoryId + "|" + sha256));
    }
  }

  private static String dockerSha256(AssetRecord asset) {
    if (asset.name() != null && asset.name().startsWith("sha256:")) {
      return asset.name().substring("sha256:".length());
    }
    String marker = "/sha256/";
    int index = asset.path().lastIndexOf(marker);
    return index < 0 ? asset.path() : asset.path().substring(index + marker.length());
  }

  private static class StubDockerRegistryDao extends DockerRegistryDao {
    private final List<DockerManifestRecord> manifests;
    private final Map<Long, List<DockerManifestReferenceRecord>> referencesByManifestId;
    private final Map<Long, List<DockerTagRecord>> tagsByManifestId;
    private final Map<String, List<DockerManifestRecord>> referrersBySubjectDigest;

    private StubDockerRegistryDao(
        List<DockerManifestRecord> manifests,
        Map<Long, List<DockerManifestReferenceRecord>> referencesByManifestId) {
      this(manifests, referencesByManifestId, Map.of(), Map.of());
    }

    private StubDockerRegistryDao(
        List<DockerManifestRecord> manifests,
        Map<Long, List<DockerManifestReferenceRecord>> referencesByManifestId,
        Map<Long, List<DockerTagRecord>> tagsByManifestId,
        Map<String, List<DockerManifestRecord>> referrersBySubjectDigest) {
      super(null, new JsonColumns(new ObjectMapper()));
      this.manifests = manifests;
      this.referencesByManifestId = referencesByManifestId;
      this.tagsByManifestId = tagsByManifestId;
      this.referrersBySubjectDigest = referrersBySubjectDigest;
    }

    @Override
    public Optional<DockerManifestRecord> findManifestByDigest(
        long repositoryId,
        String imageName,
        String digest) {
      return manifests.stream()
          .filter(manifest -> manifest.repositoryId() == repositoryId)
          .filter(manifest -> manifest.imageName().equals(imageName))
          .filter(manifest -> manifest.digest().equals(digest))
          .findFirst();
    }

    @Override
    public Optional<DockerManifestRecord> findBrowseManifestByReferencePath(long repositoryId, String path) {
      String normalized = path;
      if (normalized.startsWith("docker/manifests/")) {
        normalized = normalized.substring("docker/manifests/".length());
      }
      int marker = normalized.lastIndexOf("/sha256/");
      if (marker < 0) {
        return Optional.empty();
      }
      String imageName = normalized.substring(0, marker);
      String digest = "sha256:" + normalized.substring(marker + "/sha256/".length());
      return findManifestByDigest(repositoryId, imageName, digest);
    }

    @Override
    public List<DockerManifestReferenceRecord> listReferences(long manifestId) {
      return referencesByManifestId.getOrDefault(manifestId, List.of());
    }

    @Override
    public List<DockerTagRecord> listTagsForManifest(long manifestId) {
      return tagsByManifestId.getOrDefault(manifestId, List.of());
    }

    @Override
    public List<DockerManifestRecord> listReferrers(
        long repositoryId,
        String subjectDigest,
        String artifactType) {
      return referrersBySubjectDigest.getOrDefault(subjectDigest, List.of()).stream()
          .filter(manifest -> manifest.repositoryId() == repositoryId)
          .toList();
    }
  }

  private static class StubBlobStorageRegistry extends BlobStorageRegistry {
    private final BlobStorage storage;

    private StubBlobStorageRegistry(BlobStorage storage) {
      super(null, null, null, null, 0L);
      this.storage = storage;
    }

    @Override
    public BlobStorage forBlobStoreId(long blobStoreId) {
      return storage;
    }
  }

  private static class StubBlobStorage implements BlobStorage {
    private final byte[] content;
    private int gets;

    private StubBlobStorage(byte[] content) {
      this.content = content;
    }

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      gets++;
      return Optional.of(new ByteArrayInputStream(content));
    }

    @Override
    public boolean exists(BlobReference reference) {
      return true;
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
      throw new UnsupportedOperationException();
    }
  }
}
