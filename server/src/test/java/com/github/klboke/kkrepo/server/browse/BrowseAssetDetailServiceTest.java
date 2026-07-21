package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.DockerRegistryDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
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
  void ansibleCollectionDetailResolvesPublicPathAndShowsBoundedRegistryMetadata() {
    RepositoryRecord repository = repository(
        1L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED);
    String publicPath = "acme/tools/1.2.3/acme-tools-1.2.3.tar.gz";
    String storagePath =
        "api/v3/plugin/ansible/content/published/collections/artifacts/acme-tools-1.2.3.tar.gz";
    AssetRecord archive = new AssetRecord(
        10L, repository.id(), 20L, 100L, RepositoryFormat.ANSIBLEGALAXY,
        storagePath, PersistenceHashes.pathHash(storagePath), "acme-tools-1.2.3.tar.gz",
        "ansible-collection", "application/octet-stream", 512L, null,
        Instant.parse("2026-07-21T00:00:00Z"), Map.of());
    AssetBlobRecord blob = blob(100L, 512L);
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(repository.id(), storagePath), archive), Map.of(blob.id(), blob));
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AnsibleGalaxyRegistryDao.CollectionVersion version = ansibleVersion(repository.id());
    when(registry.findVersionByArtifactFilename(repository.id(), archive.name()))
        .thenReturn(Optional.of(version));
    when(registry.listSignatures(version.id())).thenReturn(List.of(
        new AnsibleGalaxyRegistryDao.Signature(
            1L, version.id(), null, "b".repeat(64), "ABCD", "HOSTED", Instant.now())));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(), assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])), new ObjectMapper());
    service.setAnsibleGalaxyRegistryDao(registry);

    BrowseAssetDetailService.BrowseAssetDetail detail =
        service.detail(repository, publicPath, null);

    assertEquals(publicPath, detail.path());
    assertEquals("acme-tools-1.2.3.tar.gz", detail.name());
    assertEquals("acme", detail.ansible().get("namespace"));
    assertEquals("tools", detail.ansible().get("name"));
    assertEquals("1.2.3", detail.ansible().get("version"));
    assertEquals("signed", detail.ansible().get("signature_status"));
    assertEquals(1, detail.ansible().get("signature_count"));
    assertEquals(Map.of("acme.base", ">=1.0.0"), detail.ansible().get("dependencies"));
    assertEquals(List.of(key(repository.id(), storagePath)), assets.pathLookups);
  }

  @Test
  void ansibleCollectionDetailRejectsAPathWhoseCoordinatesDoNotMatchTheArtifact() {
    RepositoryRecord repository = repository(
        1L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED);
    String storagePath =
        "api/v3/plugin/ansible/content/published/collections/artifacts/acme-tools-1.2.3.tar.gz";
    AssetRecord archive = new AssetRecord(
        10L, repository.id(), 20L, 100L, RepositoryFormat.ANSIBLEGALAXY,
        storagePath, PersistenceHashes.pathHash(storagePath), "acme-tools-1.2.3.tar.gz",
        "ansible-collection", "application/octet-stream", 512L, null,
        Instant.parse("2026-07-21T00:00:00Z"), Map.of());
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(repository.id(), storagePath), archive), Map.of());
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    when(registry.findVersionByArtifactFilename(repository.id(), archive.name()))
        .thenReturn(Optional.of(ansibleVersion(repository.id())));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(), assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])), new ObjectMapper());
    service.setAnsibleGalaxyRegistryDao(registry);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.detail(
            repository, "allowed/prefix/9.9.9/acme-tools-1.2.3.tar.gz", null));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    assertTrue(error.getReason().contains("does not match"));
    verify(registry, never()).listSignatures(30L);
    assertEquals(List.of(key(repository.id(), storagePath)), assets.pathLookups);
  }

  @Test
  void ansibleCollectionDetailFailsClosedWhenTheRegistryIdentityIsUnavailable() {
    RepositoryRecord repository = repository(
        1L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED);
    String publicPath = "acme/tools/1.2.3/acme-tools-1.2.3.tar.gz";
    String storagePath =
        "api/v3/plugin/ansible/content/published/collections/artifacts/acme-tools-1.2.3.tar.gz";
    AssetRecord archive = new AssetRecord(
        10L, repository.id(), 20L, 100L, RepositoryFormat.ANSIBLEGALAXY,
        storagePath, PersistenceHashes.pathHash(storagePath), "acme-tools-1.2.3.tar.gz",
        "ansible-collection", "application/octet-stream", 512L, null,
        Instant.parse("2026-07-21T00:00:00Z"), Map.of());
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(repository.id(), storagePath), archive), Map.of());
    BrowseAssetDetailService missingRegistry = new BrowseAssetDetailService(
        new StubRepositoryDao(), assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])), new ObjectMapper());

    ResponseStatusException unavailable = assertThrows(ResponseStatusException.class,
        () -> missingRegistry.detail(repository, publicPath, null));

    assertEquals(HttpStatus.NOT_FOUND, unavailable.getStatusCode());
    assertTrue(unavailable.getReason().contains("identity not found"));

    AnsibleGalaxyRegistryDao emptyRegistry = mock(AnsibleGalaxyRegistryDao.class);
    BrowseAssetDetailService missingIdentity = new BrowseAssetDetailService(
        new StubRepositoryDao(), assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])), new ObjectMapper());
    missingIdentity.setAnsibleGalaxyRegistryDao(emptyRegistry);

    ResponseStatusException absent = assertThrows(ResponseStatusException.class,
        () -> missingIdentity.detail(repository, publicPath, null));

    assertEquals(HttpStatus.NOT_FOUND, absent.getStatusCode());
    assertTrue(absent.getReason().contains("identity not found"));
  }

  @Test
  void ansibleGroupDetailTraversesNestedMembersAndKeepsSourceIdentity() {
    RepositoryRecord group = repository(
        10L, "ansible-group", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.GROUP);
    RepositoryRecord nested = repository(
        11L, "ansible-nested", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.GROUP);
    RepositoryRecord hosted = repository(
        12L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED);
    String publicPath = "acme/tools/1.2.3/acme-tools-1.2.3.tar.gz";
    String storagePath =
        "api/v3/plugin/ansible/content/published/collections/artifacts/acme-tools-1.2.3.tar.gz";
    AssetRecord archive = new AssetRecord(
        10L, hosted.id(), 20L, 100L, RepositoryFormat.ANSIBLEGALAXY,
        storagePath, PersistenceHashes.pathHash(storagePath), "acme-tools-1.2.3.tar.gz",
        "ansible-collection", "application/octet-stream", 512L, null,
        Instant.parse("2026-07-21T00:00:00Z"), Map.of());
    AssetBlobRecord blob = blob(100L, 512L);
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(hosted.id(), storagePath), archive), Map.of(blob.id(), blob));
    RepositoryDao repositories = mock(RepositoryDao.class);
    when(repositories.listMembers(group.id())).thenReturn(List.of(nested));
    when(repositories.listMembers(nested.id())).thenReturn(List.of(hosted, group));
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    when(registry.findVersionByArtifactFilename(hosted.id(), archive.name()))
        .thenReturn(Optional.of(ansibleVersion(hosted.id())));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        repositories, assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])), new ObjectMapper());
    service.setAnsibleGalaxyRegistryDao(registry);

    BrowseAssetDetailService.BrowseAssetDetail detail =
        service.detail(group, publicPath, hosted.name());

    assertEquals(group.name(), detail.repository());
    assertEquals(hosted.name(), detail.sourceRepository());
    assertEquals(hosted.name(), detail.ansible().get("source_repository"));
    assertEquals("unsigned", detail.ansible().get("signature_status"));
  }

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
  void terraformInternalRouteDetailIsNotExposedAsDownloadableAsset() {
    RepositoryRecord repository = repository(
        1L, "terraform-proxy", RepositoryFormat.TERRAFORM, RepositoryType.PROXY);
    StubAssetDao assets = new StubAssetDao(Map.of(), Map.of());
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.detail(repository, ".terraform/routes/token.json", null));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    assertEquals(List.of(), assets.pathLookups);
  }

  @Test
  void swiftInternalSignatureDetailIsNotExposedAsDownloadableAsset() {
    RepositoryRecord repository = repository(
        1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    StubAssetDao assets = new StubAssetDao(Map.of(), Map.of());
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.detail(repository, ".swift/signatures/acme/library/1.2.3/source.cms", null));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    assertEquals(List.of(), assets.pathLookups);
  }

  @Test
  void swiftBrowseOnlyManifestDetailShowsFileInfoWithoutDownload() {
    RepositoryRecord repository = repository(
        1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    String actualPath = "Alamofire/Alamofire/5.12.0/Package@swift-6.0.swift";
    AssetRecord manifest = new AssetRecord(
        10L,
        repository.id(),
        null,
        null,
        RepositoryFormat.SWIFT,
        actualPath,
        PersistenceHashes.pathHash(actualPath),
        "Package@swift-6.0.swift",
        "swift-manifest",
        "text/x-swift",
        128L,
        null,
        Instant.parse("2026-07-17T00:00:00Z"),
        Map.of());
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(repository.id(), actualPath), manifest),
        Map.of());
    SwiftRegistryDao swift = mock(SwiftRegistryDao.class);
    Instant updatedAt = Instant.parse("2026-07-17T00:00:00Z");
    SwiftRegistryDao.Release release = new SwiftRegistryDao.Release(
        50L,
        repository.id(),
        20L,
        "alamofire",
        "Alamofire",
        "alamofire",
        "Alamofire",
        "5.12.0",
        updatedAt,
        "{}",
        "a".repeat(64),
        11L,
        null,
        null,
        null,
        "GITHUB_PROXY",
        7L,
        SwiftRegistryDao.RELEASE_READY,
        updatedAt,
        updatedAt);
    when(swift.findRelease(repository.id(), "alamofire", "alamofire", "5.12.0"))
        .thenReturn(Optional.of(release));
    when(swift.listManifests(release.id())).thenReturn(List.of(
        new SwiftRegistryDao.Manifest(
            release.id(), "Package@swift-6.0.swift", "6.0", manifest.id(), "b".repeat(64))));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        null,
        null,
        swift,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(
        repository,
        "Alamofire/Alamofire/5.12.0/swift_manifests/Package@swift-6.0.swift",
        null);

    assertEquals("Package@swift-6.0.swift", detail.name());
    assertEquals(128L, detail.size());
    assertEquals("text/x-swift", detail.contentType());
    assertEquals(null, detail.downloadUrl());
    assertEquals("b".repeat(64), detail.checksum().get("sha256"));
    assertEquals("manifest", detail.swift().get("asset_kind"));
    assertEquals("Package@swift-6.0.swift", detail.swift().get("manifest_filename"));
    assertEquals("6.0", detail.swift().get("swift_tools_version"));
    assertTrue((Boolean) detail.content().get("generated"));
    assertTrue((Boolean) detail.provenance().get("dynamic"));
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
        PersistenceHashes.pathHash(path),
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
        PersistenceHashes.pathHash(path),
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
        PersistenceHashes.pathHash("s3://bucket/composer/company-example-1.2.3.zip"),
        "composer/company-example-1.2.3.zip",
        PersistenceHashes.pathHash("composer/company-example-1.2.3.zip"),
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
  void terraformPublicProviderPathsResolveToRevisionedStorageAssets() {
    RepositoryRecord repository = repository(
        1L, "terraform-hosted", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED);
    String base = "v1/providers/acme/demo/1.0.10";
    String filename = "terraform-provider-demo_1.0.10_linux_amd64.zip";
    String archivePath = base + "/package/linux/" + filename;
    String sumsPath = base + "/metadata-r2/terraform-provider-demo_1.0.10_SHA256SUMS";
    String signaturePath = sumsPath + ".sig";
    AssetRecord archive = asset(
        10L, repository.id(), 100L, archivePath, RepositoryFormat.TERRAFORM);
    AssetRecord sums = asset(
        11L, repository.id(), 101L, sumsPath, RepositoryFormat.TERRAFORM);
    AssetRecord signature = asset(
        12L, repository.id(), 102L, signaturePath, RepositoryFormat.TERRAFORM);
    StubAssetDao assets = new StubAssetDao(
        Map.of(
            key(repository.id(), archivePath), archive,
            key(repository.id(), sumsPath), sums,
            key(repository.id(), signaturePath), signature),
        Map.of(
            100L, blob(100L, 1024L),
            101L, blob(101L, 128L),
            102L, blob(102L, 287L)));
    TerraformRegistryDao terraform = mock(TerraformRegistryDao.class);
    when(terraform.listProviderPlatforms(repository.id(), "acme", "demo", "1.0.10"))
        .thenReturn(List.of(new TerraformRegistryDao.ProviderPlatform(
            repository.id(), "acme", "demo", "1.0.10", "linux", "amd64",
            filename, archivePath, "sha256", "5.0", 2,
            Instant.parse("2026-07-16T02:30:42Z"))));
    when(terraform.findProviderState(repository.id(), "acme", "demo", "1.0.10"))
        .thenReturn(Optional.of(new TerraformRegistryDao.ProviderState(
            repository.id(), "acme", "demo", "1.0.10", 2,
            sumsPath, signaturePath, 1, Instant.parse("2026-07-16T02:30:42Z"))));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        null,
        terraform,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    String publicArchive = base + "/download/linux/amd64/" + filename;
    BrowseAssetDetailService.BrowseAssetDetail archiveDetail =
        service.detail(repository, publicArchive, null);
    assertEquals(publicArchive, archiveDetail.path());
    assertEquals(filename, archiveDetail.name());
    assertEquals("/repository/terraform-hosted/" + publicArchive, archiveDetail.downloadUrl());

    String publicSums = base + "/download/linux/amd64/SHA256SUMS";
    BrowseAssetDetailService.BrowseAssetDetail sumsDetail =
        service.detail(repository, publicSums, null);
    assertEquals(publicSums, sumsDetail.path());
    assertEquals("SHA256SUMS", sumsDetail.name());
    assertEquals("/repository/terraform-hosted/" + publicSums, sumsDetail.downloadUrl());
    assertTrue(assets.pathLookups.contains(key(repository.id(), archivePath)));
    assertTrue(assets.pathLookups.contains(key(repository.id(), sumsPath)));

    String versionsPath = "v1/providers/acme/demo/versions.json";
    BrowseAssetDetailService.BrowseAssetDetail versionsDetail =
        service.detail(repository, versionsPath, null);
    assertEquals(versionsPath, versionsDetail.path());
    assertEquals("versions.json", versionsDetail.name());
    assertEquals("application/json", versionsDetail.contentType());
    assertEquals("/repository/terraform-hosted/" + versionsPath, versionsDetail.downloadUrl());
    assertTrue((Boolean) versionsDetail.content().get("generated"));
    assertTrue((Boolean) versionsDetail.provenance().get("dynamic"));
  }

  @Test
  void swiftArchiveDetailExposesReleaseIdentityChecksumSigningAndRepositoryUrls() {
    RepositoryRecord repository = repository(
        1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    String path = "Acme/Library/1.2.3.zip";
    AssetRecord archive = new AssetRecord(
        10L,
        repository.id(),
        20L,
        100L,
        RepositoryFormat.SWIFT,
        path,
        PersistenceHashes.pathHash(path),
        "1.2.3.zip",
        "swift-source-archive",
        "application/zip",
        1024L,
        null,
        Instant.parse("2026-07-16T00:00:00Z"),
        Map.of("swiftKind", "source-archive"));
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(repository.id(), path), archive),
        Map.of(100L, blob(100L, 1024L)));
    SwiftRegistryDao swift = mock(SwiftRegistryDao.class);
    SwiftRegistryDao.Release release = new SwiftRegistryDao.Release(
        50L,
        repository.id(),
        20L,
        "acme",
        "Acme",
        "library",
        "Library",
        "1.2.3",
        Instant.parse("2026-07-16T00:00:00Z"),
        "{}",
        "a".repeat(64),
        archive.id(),
        "cms-1.0.0",
        11L,
        null,
        "HOSTED",
        7L,
        SwiftRegistryDao.RELEASE_READY,
        Instant.parse("2026-07-16T00:00:00Z"),
        Instant.parse("2026-07-16T00:00:00Z"));
    when(swift.findRelease(repository.id(), "acme", "library", "1.2.3"))
        .thenReturn(Optional.of(release));
    when(swift.listManifests(release.id())).thenReturn(List.of(
        new SwiftRegistryDao.Manifest(release.id(), "Package.swift", "", 12L, "b".repeat(64)),
        new SwiftRegistryDao.Manifest(
            release.id(), "Package@swift-5.9.swift", "5.9", 13L, "c".repeat(64))));
    when(swift.listRepositoryUrls(release.id())).thenReturn(List.of(
        new SwiftRegistryDao.RepositoryUrl(
            1L, release.id(), repository.id(), "acme", "library",
            "https://github.com/acme/library", "https://github.com/Acme/Library")));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        null,
        null,
        swift,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(repository, path, null);

    assertEquals("Acme", detail.swift().get("scope"));
    assertEquals("Library", detail.swift().get("name"));
    assertEquals("1.2.3", detail.swift().get("version"));
    assertEquals("source-archive", detail.swift().get("asset_kind"));
    assertEquals("a".repeat(64), detail.swift().get("archive_sha256"));
    assertEquals("HOSTED", detail.swift().get("source_kind"));
    assertEquals("signed", detail.swift().get("signature_status"));
    assertEquals("cms-1.0.0", detail.swift().get("signature_format"));
    assertEquals(List.of("5.9"), detail.swift().get("swift_tools_versions"));
    assertEquals(
        List.of("https://github.com/Acme/Library"), detail.swift().get("repository_urls"));
  }

  @Test
  void swiftReleaseMetadataDetailUsesResolvedGroupMemberWithoutStoredAsset() {
    RepositoryRecord group = repository(
        1L, "swift-group", RepositoryFormat.SWIFT, RepositoryType.GROUP);
    RepositoryRecord stale = repository(
        2L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    RepositoryRecord source = repository(
        3L, "swift-proxy", RepositoryFormat.SWIFT, RepositoryType.PROXY);
    String path = "Alamofire/Alamofire/5.12.0";
    Instant updatedAt = Instant.parse("2026-07-17T05:27:58Z");
    SwiftRegistryDao.Release release = new SwiftRegistryDao.Release(
        50L,
        source.id(),
        20L,
        "alamofire",
        "Alamofire",
        "alamofire",
        "Alamofire",
        "5.12.0",
        updatedAt,
        "{}",
        "a".repeat(64),
        10L,
        null,
        null,
        null,
        "GITHUB_PROXY",
        7L,
        SwiftRegistryDao.RELEASE_READY,
        updatedAt,
        updatedAt);
    RepositoryDao repositories = mock(RepositoryDao.class);
    when(repositories.listMembers(group.id())).thenReturn(List.of(stale, source));
    SwiftRegistryDao swift = mock(SwiftRegistryDao.class);
    when(swift.findRelease(stale.id(), "alamofire", "alamofire", "5.12.0"))
        .thenReturn(Optional.empty());
    when(swift.findRelease(source.id(), "alamofire", "alamofire", "5.12.0"))
        .thenReturn(Optional.of(release));
    when(swift.listManifests(release.id())).thenReturn(List.of(
        new SwiftRegistryDao.Manifest(release.id(), "Package.swift", "", 12L, "b".repeat(64)),
        new SwiftRegistryDao.Manifest(
            release.id(), "Package@swift-6.0.swift", "6.0", 13L, "c".repeat(64)),
        new SwiftRegistryDao.Manifest(
            release.id(), "Package@swift-6.1.swift", "6.1", 14L, "d".repeat(64))));
    when(swift.listRepositoryUrls(release.id())).thenReturn(List.of(
        new SwiftRegistryDao.RepositoryUrl(
            1L,
            release.id(),
            source.id(),
            "alamofire",
            "alamofire",
            "https://github.com/alamofire/alamofire",
            "https://github.com/alamofire/alamofire")));
    StubAssetDao assets = new StubAssetDao(Map.of(), Map.of());
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        repositories,
        assets,
        null,
        null,
        swift,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(group, path, null);

    assertEquals(group.name(), detail.repository());
    assertEquals(source.name(), detail.sourceRepository());
    assertEquals(path, detail.path());
    assertEquals("5.12.0", detail.name());
    assertEquals("text/plain", detail.contentType());
    assertEquals(updatedAt, detail.lastUpdatedAt());
    assertEquals("/repository/swift-group/" + path, detail.downloadUrl());
    assertEquals("release-metadata", detail.swift().get("asset_kind"));
    assertEquals("GITHUB_PROXY", detail.swift().get("source_kind"));
    assertEquals(source.name(), detail.swift().get("source_repository"));
    assertEquals(List.of("6.0", "6.1"), detail.swift().get("swift_tools_versions"));
    assertEquals(
        List.of("https://github.com/alamofire/alamofire"),
        detail.swift().get("repository_urls"));
    assertTrue((Boolean) detail.content().get("generated"));
    assertTrue((Boolean) detail.provenance().get("dynamic"));
    assertEquals(List.of(), assets.pathLookups);
  }

  @Test
  void swiftReleaseMetadataDetailPreservesSemverEndingInZip() {
    RepositoryRecord repository = repository(
        1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    String version = "1.2.3+linux.zip";
    String path = "Acme/Library/" + version;
    SwiftRegistryDao swift = mock(SwiftRegistryDao.class);
    Instant updatedAt = Instant.parse("2026-07-16T00:00:00Z");
    SwiftRegistryDao.Release release = new SwiftRegistryDao.Release(
        50L,
        repository.id(),
        20L,
        "acme",
        "Acme",
        "library",
        "Library",
        version,
        updatedAt,
        "{}",
        "a".repeat(64),
        10L,
        null,
        null,
        null,
        "HOSTED",
        7L,
        SwiftRegistryDao.RELEASE_READY,
        updatedAt,
        updatedAt);
    when(swift.findRelease(repository.id(), "acme", "library", version))
        .thenReturn(Optional.of(release));
    StubAssetDao assets = new StubAssetDao(Map.of(), Map.of());
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        null,
        null,
        swift,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(repository, path, null);

    assertEquals(version, detail.swift().get("version"));
    assertEquals("release-metadata", detail.swift().get("asset_kind"));
    assertEquals(List.of(), assets.pathLookups);
  }

  @Test
  void swiftReleaseMetadataDetailStopsAtGroupTombstone() {
    RepositoryRecord group = repository(
        1L, "swift-group", RepositoryFormat.SWIFT, RepositoryType.GROUP);
    RepositoryRecord deleted = repository(
        2L, "swift-deleted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    RepositoryRecord later = repository(
        3L, "swift-later", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    String path = "Alamofire/Alamofire/5.12.0";
    RepositoryDao repositories = mock(RepositoryDao.class);
    when(repositories.listMembers(group.id())).thenReturn(List.of(deleted, later));
    SwiftRegistryDao swift = mock(SwiftRegistryDao.class);
    when(swift.findTombstone(deleted.id(), "alamofire", "alamofire", "5.12.0"))
        .thenReturn(Optional.of(new SwiftRegistryDao.Tombstone(
            deleted.id(),
            "alamofire",
            "alamofire",
            "5.12.0",
            "deleted",
            8L,
            Instant.parse("2026-07-17T05:27:58Z"))));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        repositories,
        new StubAssetDao(Map.of(), Map.of()),
        null,
        null,
        swift,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    ResponseStatusException error = assertThrows(
        ResponseStatusException.class, () -> service.detail(group, path, null));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    verify(swift, never()).findRelease(later.id(), "alamofire", "alamofire", "5.12.0");
  }

  @Test
  void swiftManifestDetailPreservesSemverEndingInZip() {
    RepositoryRecord repository = repository(
        1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    String version = "1.2.3+linux.zip";
    String path = "Acme/Library/" + version + "/Package.swift";
    AssetRecord manifest = new AssetRecord(
        10L,
        repository.id(),
        20L,
        100L,
        RepositoryFormat.SWIFT,
        path,
        PersistenceHashes.pathHash(path),
        "Package.swift",
        "swift-manifest",
        "text/x-swift",
        128L,
        null,
        Instant.parse("2026-07-16T00:00:00Z"),
        Map.of("swiftKind", "manifest"));
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(repository.id(), path), manifest),
        Map.of(100L, blob(100L, 128L)));
    SwiftRegistryDao swift = mock(SwiftRegistryDao.class);
    when(swift.findRelease(repository.id(), "acme", "library", version))
        .thenReturn(Optional.empty());
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        new StubRepositoryDao(),
        assets,
        null,
        null,
        swift,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    BrowseAssetDetailService.BrowseAssetDetail detail = service.detail(repository, path, null);

    assertEquals(version, detail.swift().get("version"));
    verify(swift).findRelease(repository.id(), "acme", "library", version);
  }

  @Test
  void swiftManifestDetailStopsAtGroupTombstone() {
    RepositoryRecord group = repository(
        1L, "swift-group", RepositoryFormat.SWIFT, RepositoryType.GROUP);
    RepositoryRecord deleted = repository(
        2L, "swift-deleted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    RepositoryRecord later = repository(
        3L, "swift-later", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    String path = "Alamofire/Alamofire/5.12.0/swift_manifests/Package.swift";
    RepositoryDao repositories = mock(RepositoryDao.class);
    when(repositories.listMembers(group.id())).thenReturn(List.of(deleted, later));
    SwiftRegistryDao swift = mock(SwiftRegistryDao.class);
    when(swift.findTombstone(deleted.id(), "alamofire", "alamofire", "5.12.0"))
        .thenReturn(Optional.of(new SwiftRegistryDao.Tombstone(
            deleted.id(),
            "alamofire",
            "alamofire",
            "5.12.0",
            "deleted",
            8L,
            Instant.parse("2026-07-17T05:27:58Z"))));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        repositories,
        new StubAssetDao(Map.of(), Map.of()),
        null,
        null,
        swift,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    ResponseStatusException error = assertThrows(
        ResponseStatusException.class, () -> service.detail(group, path, null));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    verify(swift, never()).findRelease(later.id(), "alamofire", "alamofire", "5.12.0");
  }

  @Test
  void swiftArchiveDetailStopsAtGroupTombstoneBeforePhysicalAssetFallback() {
    RepositoryRecord group = repository(
        1L, "swift-group", RepositoryFormat.SWIFT, RepositoryType.GROUP);
    RepositoryRecord deleted = repository(
        2L, "swift-deleted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    RepositoryRecord later = repository(
        3L, "swift-later", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    String path = "Alamofire/Alamofire/5.12.0.zip";
    RepositoryDao repositories = mock(RepositoryDao.class);
    when(repositories.listMembers(group.id())).thenReturn(List.of(deleted, later));
    SwiftRegistryDao swift = mock(SwiftRegistryDao.class);
    when(swift.findTombstone(deleted.id(), "alamofire", "alamofire", "5.12.0"))
        .thenReturn(Optional.of(new SwiftRegistryDao.Tombstone(
            deleted.id(),
            "alamofire",
            "alamofire",
            "5.12.0",
            "deleted",
            8L,
            Instant.parse("2026-07-17T05:27:58Z"))));
    AssetRecord archive = new AssetRecord(
        10L,
        later.id(),
        null,
        100L,
        RepositoryFormat.SWIFT,
        path,
        PersistenceHashes.pathHash(path),
        "5.12.0.zip",
        "swift-source-archive",
        "application/zip",
        128L,
        null,
        Instant.parse("2026-07-17T00:00:00Z"),
        Map.of("swiftKind", "source-archive"));
    StubAssetDao assets = new StubAssetDao(
        Map.of(key(later.id(), path), archive),
        Map.of(100L, blob(100L, 128L)));
    BrowseAssetDetailService service = new BrowseAssetDetailService(
        repositories,
        assets,
        null,
        null,
        swift,
        new StubBlobStorageRegistry(new StubBlobStorage(new byte[0])),
        new ObjectMapper());

    ResponseStatusException error = assertThrows(
        ResponseStatusException.class, () -> service.detail(group, path, null));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    assertEquals(List.of(), assets.pathLookups);
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
        PersistenceHashes.pathHash(path),
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
        PersistenceHashes.pathHash(path),
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

  private static AnsibleGalaxyRegistryDao.CollectionVersion ansibleVersion(long repositoryId) {
    Instant now = Instant.parse("2026-07-21T00:00:00Z");
    return new AnsibleGalaxyRegistryDao.CollectionVersion(
        30L, repositoryId, 20L, 10L,
        "acme", "acme", "tools", "tools", "1.2.3", "1.2.3",
        "acme-tools-1.2.3.tar.gz", "a".repeat(64), 512L,
        Map.of(
            "authors", List.of("kkRepo"),
            "tags", List.of("automation"),
            "license", "Apache-2.0",
            "description", "fixture"),
        Map.of("acme.base", ">=1.0.0"), ">=2.15", "HOSTED", 1L,
        AnsibleGalaxyRegistryDao.VERSION_READY, now, now, now);
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
        PersistenceHashes.pathHash(path),
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
        PersistenceHashes.pathHash(path),
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
        PersistenceHashes.pathHash(path),
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
        PersistenceHashes.pathHash("s3://bucket/npm/demo/-/demo-1.0.0.tgz"),
        "npm/demo/-/demo-1.0.0.tgz",
        PersistenceHashes.pathHash("npm/demo/-/demo-1.0.0.tgz"),
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
        PersistenceHashes.pathHash("s3://bucket/docker/manifests/" + digest),
        "docker/manifests/" + digest,
        PersistenceHashes.pathHash("docker/manifests/" + digest),
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
        PersistenceHashes.pathHash("s3://bucket/docker/blobs/" + digest),
        "docker/blobs/" + digest,
        PersistenceHashes.pathHash("docker/blobs/" + digest),
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
        PersistenceHashes.sha256(imageName),
        "sha256",
        digest,
        PersistenceHashes.sha256(digest),
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
        PersistenceHashes.sha256(digest),
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
        PersistenceHashes.sha256(manifest.imageName()),
        tag,
        PersistenceHashes.sha256(tag),
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

  private static class StubRepositoryDao extends RepositoryDaoAdapter {
    private StubRepositoryDao() {
      super(null, null);
    }
  }

  private static class StubAssetDao extends AssetDaoAdapter {
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
    public List<AssetRecord> listAssetsByPrefix(long repositoryId, String pathPrefix) {
      return assetsByPath.values().stream()
          .filter(asset -> asset.repositoryId() == repositoryId)
          .filter(asset -> asset.path().startsWith(pathPrefix))
          .toList();
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

  private static class StubDockerRegistryDao extends DockerRegistryDaoAdapter {
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
      super();
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
