package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.maven.path.HashType;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.docker.DockerManifestParser;
import com.github.klboke.kkrepo.server.conda.CondaRepositoryDataMigrationWriter;
import com.github.klboke.kkrepo.server.conan.ConanRepositoryDataMigrationWriter;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RepositoryDataMigrationWriterTest {
  private static final byte[] SAMPLE = "kkrepo migration checksum\n".getBytes(StandardCharsets.UTF_8);
  private static final MavenPathParser MAVEN_PATH_PARSER = new MavenPathParser();

  @Test
  void generatedMavenChecksumPayloadsMatchNexusUploadHandlerSemantics() {
    String md5 = "d66cfec994ca50e0a9830dd6c6be982a";
    String sha1 = "49e74bffd0f0c03a549168345d62e1ace42d39bd";
    String sha256 = "74398b43ab3feaa6d2231ff0318f6862a157b34991a515025d89325b9c458db2";
    String sha512 = "e6f835a1cec59cdf01b05765808a53a1980c6fea27cc466ea87bee6dcb9334cbd0dc4db659750552824c66261ceeafc5627179e26d40eeadc9527422926093fe";

    assertEquals(md5, digest(HashType.MD5));
    assertEquals(sha1, digest(HashType.SHA1));
    assertEquals(sha256, digest(HashType.SHA256));
    assertEquals(sha512, digest(HashType.SHA512));

    MavenPath mainPath = MAVEN_PATH_PARSER.parsePath("com/acme/app/1.0/app-1.0.jar");
    List<RepositoryDataMigrationWriter.GeneratedMavenChecksum> checksums =
        RepositoryDataMigrationWriter.generatedMavenChecksums(mainPath, md5, sha1, sha256, sha512);

    assertEquals(4, checksums.size());
    assertChecksum(checksums.get(0), "com/acme/app/1.0/app-1.0.jar.md5", md5);
    assertChecksum(checksums.get(1), "com/acme/app/1.0/app-1.0.jar.sha1", sha1);
    assertChecksum(checksums.get(2), "com/acme/app/1.0/app-1.0.jar.sha256", sha256);
    assertChecksum(checksums.get(3), "com/acme/app/1.0/app-1.0.jar.sha512", sha512);
  }

  @Test
  void reusableBlobAttributesAlreadyContainingSha512DoNotNeedUpdate() {
    RepositoryDataMigrationWriter.Digests digests = new RepositoryDataMigrationWriter.Digests(
        "md5", "sha1", "sha256", "sha512", 123L);

    assertNull(RepositoryDataMigrationWriter.mergeReusableBlobAttributes(
        Map.of("sha512", "sha512", "sourceAssetId", "#12:1"),
        digests));
  }

  @Test
  void reusableBlobAttributesOnlyBackfillMissingSha512() {
    RepositoryDataMigrationWriter.Digests digests = new RepositoryDataMigrationWriter.Digests(
        "md5", "sha1", "sha256", "sha512", 123L);

    Map<String, Object> attributes = RepositoryDataMigrationWriter.mergeReusableBlobAttributes(
        Map.of("sourceAssetId", "#12:1"),
        digests);

    assertEquals("sha512", attributes.get("sha512"));
    assertEquals("#12:1", attributes.get("sourceAssetId"));
    assertFalse(attributes.containsKey("sourceBlobRef"));
    assertFalse(attributes.containsKey("sourceMetadata"));
  }

  @Test
  void dockerManifestMigrationTargetParsesPlainDockerV2Path() {
    var target = RepositoryDataMigrationWriter
        .dockerManifestMigrationTarget("v2/team/app/manifests/release-2026")
        .orElseThrow();

    assertEquals("team/app", target.imageName());
    assertEquals("release-2026", target.reference());
  }

  @Test
  void dockerManifestMigrationTargetParsesSourceAssetPathWithoutV2Prefix() {
    var target = RepositoryDataMigrationWriter
        .dockerManifestMigrationTarget("library/alpine/manifests/sha256:"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        .orElseThrow();

    assertEquals("library/alpine", target.imageName());
    assertEquals("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", target.reference());
  }

  @Test
  void dockerManifestMigrationTargetDecodesPathSegments() {
    var target = RepositoryDataMigrationWriter
        .dockerManifestMigrationTarget("/v2/team%2Fencoded/manifests/v1")
        .orElseThrow();

    assertEquals("team/encoded", target.imageName());
    assertEquals("v1", target.reference());
  }

  @Test
  void dockerManifestMigrationTargetIgnoresTagsAndBlobPaths() {
    assertTrue(RepositoryDataMigrationWriter
        .dockerManifestMigrationTarget("v2/team/app/tags/list")
        .isEmpty());
    assertTrue(RepositoryDataMigrationWriter
        .dockerManifestMigrationTarget("v2/team/app/blobs/sha256:"
            + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        .isEmpty());
  }

  @Test
  void dockerBlobMigrationTargetParsesBlobDigest() {
    var target = RepositoryDataMigrationWriter
        .dockerBlobMigrationTarget("v2/team/app/blobs/sha256:"
            + "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
        .orElseThrow();

    assertEquals("team/app", target.imageName());
    assertEquals("sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        target.digest().value());
  }

  @Test
  void dockerBlobMigrationTargetAcceptsRepositoryScopedBlobPathWithoutImageName() {
    var target = RepositoryDataMigrationWriter
        .dockerBlobMigrationTarget("v2/blobs/sha256:"
            + "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
        .orElseThrow();

    assertNull(target.imageName());
    assertEquals("sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
        target.digest().value());
  }

  @Test
  void dockerBlobMigrationTargetTreatsNexusDashPlaceholderAsRepositoryScopedBlob() {
    var target = RepositoryDataMigrationWriter
        .dockerBlobMigrationTarget("v2/-/blobs/sha256:"
            + "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
        .orElseThrow();

    assertNull(target.imageName());
    assertEquals("sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        target.digest().value());
  }

  @Test
  void writeIndexesMigratedDockerManifestAsReadableRegistryMetadata() throws Exception {
    byte[] manifest = dockerManifestBytes(
        "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
    String manifestDigest = "sha256:" + sha256(manifest);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    ComponentDao componentDao = mock(ComponentDao.class);
    RecordingAssetDao assetDao = new RecordingAssetDao();
    BrowseNodeDao browseNodeDao = mock(BrowseNodeDao.class);
    DockerRegistryDao dockerRegistryDao = mock(DockerRegistryDao.class);
    when(repositoryDao.findById(10L)).thenReturn(Optional.of(dockerRepository()));
    when(dockerRegistryDao.upsertManifest(any())).thenAnswer(invocation -> {
      DockerManifestRecord record = invocation.getArgument(0);
      return new DockerManifestRecord(
          500L,
          record.repositoryId(),
          record.imageName(),
          record.imageNameHash(),
          record.digestAlgorithm(),
          record.digest(),
          record.digestHash(),
          record.mediaType(),
          record.artifactType(),
          record.subjectDigest(),
          record.subjectDigestHash(),
          record.assetId(),
          record.size(),
          record.pushedBy(),
          record.pushedByIp(),
          record.deletedAt(),
          record.attributes(),
          record.createdAt(),
          record.updatedAt());
    });
    RepositoryDataMigrationWriter writer = new RepositoryDataMigrationWriter(
        repositoryDao,
        componentDao,
        assetDao,
        browseNodeDao,
        new FixedBlobStorageRegistry(new MemoryBlobStorage()),
        mock(RepositoryIndexRebuildDao.class),
        dockerRegistryDao,
        new DockerManifestParser(new ObjectMapper()),
        null,
        new TransientTransactionRetry(new RecordingTransactionManager(), 1, 0));

    RepositoryDataMigrationWriter.WriteResult result = writer.write(
        10L,
        dockerManifestSource("v2/team/app/manifests/latest", manifest.length),
        new ByteArrayInputStream(manifest),
        DockerConstants.MEDIA_TYPE_OCI_MANIFEST,
        true);

    assertEquals(200L, result.assetId());
    ArgumentCaptor<DockerManifestRecord> manifestCaptor = ArgumentCaptor.forClass(DockerManifestRecord.class);
    verify(dockerRegistryDao).upsertManifest(manifestCaptor.capture());
    DockerManifestRecord indexedManifest = manifestCaptor.getValue();
    assertEquals(10L, indexedManifest.repositoryId());
    assertEquals("team/app", indexedManifest.imageName());
    assertEquals(manifestDigest, indexedManifest.digest());
    assertEquals(200L, indexedManifest.assetId());
    assertEquals(DockerConstants.MEDIA_TYPE_OCI_MANIFEST, indexedManifest.mediaType());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DockerManifestReferenceRecord>> referencesCaptor = ArgumentCaptor.forClass(List.class);
    verify(dockerRegistryDao).replaceManifestReferences(anyLong(), referencesCaptor.capture());
    assertEquals(2, referencesCaptor.getValue().size());
    assertTrue(referencesCaptor.getValue().stream()
        .anyMatch(reference -> "CONFIG".equals(reference.referenceKind())));
    assertTrue(referencesCaptor.getValue().stream()
        .anyMatch(reference -> "LAYER".equals(reference.referenceKind())));

    ArgumentCaptor<DockerTagRecord> tagCaptor = ArgumentCaptor.forClass(DockerTagRecord.class);
    verify(dockerRegistryDao).upsertTag(tagCaptor.capture());
    assertEquals("latest", tagCaptor.getValue().tag());
    assertEquals(manifestDigest, tagCaptor.getValue().manifestDigest());
  }

  @Test
  void writeAcceptsNexusRepositoryScopedDockerBlobAsset() {
    byte[] blob = "docker blob".getBytes(StandardCharsets.UTF_8);
    RepositoryDataMigrationWriter writer = new RepositoryDataMigrationWriter(
        mockRepositoryDao(),
        mock(ComponentDao.class),
        new RecordingAssetDao(),
        mock(BrowseNodeDao.class),
        new FixedBlobStorageRegistry(new MemoryBlobStorage()),
        mock(RepositoryIndexRebuildDao.class),
        mock(DockerRegistryDao.class),
        new DockerManifestParser(new ObjectMapper()),
        null,
        new TransientTransactionRetry(new RecordingTransactionManager(), 1, 0));

    RepositoryDataMigrationWriter.WriteResult result = writer.write(
        10L,
        dockerBlobSource("v2/-/blobs/sha256:" + sha256Unchecked(blob), blob.length),
        new ByteArrayInputStream(blob),
        "application/octet-stream",
        true);

    assertEquals(200L, result.assetId());
  }

  @Test
  @SuppressWarnings("unchecked")
  void writeMigratedCargoCrateBuildsSparseIndexComponentMetadata() throws Exception {
    byte[] crate = cargoCrate("cargo_demo", "0.1.0", """
        [package]
        name = "cargo_demo"
        version = "0.1.0"
        description = "Migrated Cargo package"
        repository = "https://example.test/cargo_demo"

        [dependencies]
        serde = { version = "1", features = ["derive"] }
        """);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    ComponentDao componentDao = mock(ComponentDao.class);
    when(repositoryDao.findById(11L)).thenReturn(Optional.of(cargoRepository()));
    when(componentDao.upsertReturningId(any())).thenReturn(501L);
    RepositoryDataMigrationWriter writer = new RepositoryDataMigrationWriter(
        repositoryDao,
        componentDao,
        new RecordingAssetDao(),
        mock(BrowseNodeDao.class),
        new FixedBlobStorageRegistry(new MemoryBlobStorage()),
        mock(RepositoryIndexRebuildDao.class),
        mock(DockerRegistryDao.class),
        new DockerManifestParser(new ObjectMapper()),
        null,
        new TransientTransactionRetry(new RecordingTransactionManager(), 1, 0));

    RepositoryDataMigrationWriter.WriteResult result = writer.write(
        11L,
        cargoSource("crates/cargo_demo/0.1.0/cargo_demo-0.1.0.crate", crate.length),
        new ByteArrayInputStream(crate),
        "application/x-tar",
        true);

    assertEquals(501L, result.componentId());
    ArgumentCaptor<ComponentRecord> componentCaptor = ArgumentCaptor.forClass(ComponentRecord.class);
    verify(componentDao).upsertReturningId(componentCaptor.capture());
    ComponentRecord component = componentCaptor.getValue();
    assertEquals(RepositoryFormat.CARGO, component.format());
    assertEquals("cargo_demo", component.name());
    assertEquals("0.1.0", component.version());
    assertEquals("crate", component.kind());
    assertEquals("crates/cargo_demo/0.1.0/cargo_demo-0.1.0.crate", component.attributes().get("cratePath"));
    Map<String, Object> indexEntry = (Map<String, Object>) component.attributes().get("indexEntry");
    assertEquals("cargo_demo", indexEntry.get("name"));
    assertEquals("0.1.0", indexEntry.get("vers"));
    assertEquals(false, indexEntry.get("yanked"));
    assertEquals(64, String.valueOf(indexEntry.get("cksum")).length());
    List<Map<String, Object>> deps = (List<Map<String, Object>>) indexEntry.get("deps");
    assertEquals("serde", deps.get(0).get("name"));
    assertEquals("1", deps.get(0).get("req"));
    assertEquals(List.of("derive"), deps.get(0).get("features"));
  }

  @Test
  void delegatesCondaMigrationAndFailsClosedWhenProtocolWriterIsUnavailable() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    RepositoryRecord repository = condaRepository();
    RepositoryDataMigrationAssetRecord source = condaSource(
        "main/noarch/demo-1.0-0.conda", SAMPLE.length);
    when(repositories.findById(repository.id())).thenReturn(Optional.of(repository));
    when(storages.forBlobStoreId(repository.blobStoreId())).thenReturn(storage);
    RepositoryDataMigrationWriter writer = new RepositoryDataMigrationWriter(
        repositories,
        mock(ComponentDao.class),
        new RecordingAssetDao(),
        mock(BrowseNodeDao.class),
        storages,
        mock(RepositoryIndexRebuildDao.class),
        mock(DockerRegistryDao.class),
        new DockerManifestParser(new ObjectMapper()),
        null,
        new TransientTransactionRetry(new RecordingTransactionManager(), 1, 0));

    assertThrows(IllegalStateException.class, () -> writer.write(
        repository.id(), source, new ByteArrayInputStream(SAMPLE),
        "application/vnd.conda.package.v2", true));

    CondaRepositoryDataMigrationWriter conda = mock(CondaRepositoryDataMigrationWriter.class);
    when(conda.write(
        org.mockito.ArgumentMatchers.eq(repository),
        org.mockito.ArgumentMatchers.eq(source),
        any(InputStream.class),
        org.mockito.ArgumentMatchers.eq("application/vnd.conda.package.v2"),
        org.mockito.ArgumentMatchers.eq(true)))
        .thenReturn(new CondaRepositoryDataMigrationWriter.MigratedAsset(101L, 102L, 103L, "obj"));
    writer.setCondaMigrationWriter(conda);
    RepositoryDataMigrationWriter.WriteResult result = writer.write(
        repository.id(), source, new ByteArrayInputStream(SAMPLE),
        "application/vnd.conda.package.v2", true);

    assertEquals(101L, result.componentId());
    assertEquals(102L, result.assetId());
    assertEquals(103L, result.assetBlobId());
    assertEquals("obj", result.assetBlobObjectKey());
    verify(conda).write(
        org.mockito.ArgumentMatchers.eq(repository),
        org.mockito.ArgumentMatchers.eq(source),
        any(InputStream.class),
        org.mockito.ArgumentMatchers.eq("application/vnd.conda.package.v2"),
        org.mockito.ArgumentMatchers.eq(true));
  }

  @Test
  void delegatesConanMigrationAndFailsClosedWhenProtocolWriterIsUnavailable() {
    RepositoryDao repositories = mock(RepositoryDao.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    RepositoryRecord repository = conanRepository();
    RepositoryDataMigrationAssetRecord source = conanSource(
        "conans/demo/1.0/_/_/revisions/rrev/files/conan_export.tgz", SAMPLE.length);
    when(repositories.findById(repository.id())).thenReturn(Optional.of(repository));
    when(storages.forBlobStoreId(repository.blobStoreId())).thenReturn(storage);
    RepositoryDataMigrationWriter writer = new RepositoryDataMigrationWriter(
        repositories,
        mock(ComponentDao.class),
        new RecordingAssetDao(),
        mock(BrowseNodeDao.class),
        storages,
        mock(RepositoryIndexRebuildDao.class),
        mock(DockerRegistryDao.class),
        new DockerManifestParser(new ObjectMapper()),
        null,
        new TransientTransactionRetry(new RecordingTransactionManager(), 1, 0));

    assertThrows(IllegalStateException.class, () -> writer.write(
        repository.id(), source, new ByteArrayInputStream(SAMPLE),
        "application/gzip", true));

    ConanRepositoryDataMigrationWriter conan = mock(ConanRepositoryDataMigrationWriter.class);
    when(conan.write(
        org.mockito.ArgumentMatchers.eq(repository),
        org.mockito.ArgumentMatchers.eq(source),
        any(InputStream.class),
        org.mockito.ArgumentMatchers.eq("application/gzip"),
        org.mockito.ArgumentMatchers.eq(true)))
        .thenReturn(new ConanRepositoryDataMigrationWriter.MigratedAsset(
            101L, 102L, 103L, "obj", "a".repeat(40), SAMPLE.length, true));
    writer.setConanMigrationWriter(conan);

    RepositoryDataMigrationWriter.WriteResult result = writer.write(
        repository.id(), source, new ByteArrayInputStream(SAMPLE), "application/gzip", true);

    assertEquals(101L, result.componentId());
    assertEquals(102L, result.assetId());
    assertEquals(103L, result.assetBlobId());
    assertEquals("obj", result.assetBlobObjectKey());
  }

  @Test
  void classifiesConanPackageArchivesWithoutTreatingRecipeArchivesAsBinaryPackages()
      throws Exception {
    Method assetKind = RepositoryDataMigrationWriter.class.getDeclaredMethod(
        "assetKind", RepositoryFormat.class, RepositoryDataMigrationAssetRecord.class);
    assetKind.setAccessible(true);

    for (String archive : List.of(
        "conan_package.tgz", "conan_package.txz", "conan_package.tzst")) {
      assertEquals("conan-package", assetKind.invoke(
          null, RepositoryFormat.CONAN, conanSource(archive, SAMPLE.length)));
    }
    assertEquals("conan-revision-file", assetKind.invoke(
        null, RepositoryFormat.CONAN, conanSource("conan_export.tgz", SAMPLE.length)));
  }

  @Test
  void migratesCommitPinnedHuggingFaceFilesWithVerifiedIdentityAndLogicalBrowsePath() {
    byte[] model = "model-weights".getBytes(StandardCharsets.UTF_8);
    String commit = "0123456789abcdef0123456789abcdef01234567";
    String path = "org/model/resolve/" + commit + "/weights/model.safetensors";
    RepositoryDao repositories = mock(RepositoryDao.class);
    ComponentDao components = mock(ComponentDao.class);
    RecordingAssetDao assets = new RecordingAssetDao();
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    HuggingFaceRegistryDao registry = mock(HuggingFaceRegistryDao.class);
    when(repositories.findById(14L)).thenReturn(Optional.of(huggingFaceRepository()));
    when(components.upsertReturningId(any())).thenReturn(501L);
    when(registry.upsertRevision(any())).thenAnswer(invocation -> {
      HuggingFaceRegistryDao.ModelRevision value = invocation.getArgument(0);
      return new HuggingFaceRegistryDao.ModelRevision(
          601L, value.repositoryId(), value.repoId(), value.commitHash(), value.componentId(),
          value.rawMetadataAssetId(), value.author(), value.committedAt(), value.privateModel(),
          value.gated(), value.libraryName(), value.pipelineTag(), value.license(),
          value.observedAt(), value.updatedAt());
    });
    when(registry.upsertFileMetadata(any())).thenAnswer(invocation -> invocation.getArgument(0));
    RepositoryDataMigrationWriter writer = migrationWriter(
        repositories, components, assets, browse);
    writer.setHuggingFaceRegistry(registry);

    RepositoryDataMigrationWriter.WriteResult result = writer.write(
        14L,
        huggingFaceSource(path, "org", "model", commit, model.length,
            Map.of("checksums", List.of(Map.of("SHA-256", sha256Unchecked(model))))),
        new ByteArrayInputStream(model),
        "application/octet-stream",
        true);

    assertEquals(501L, result.componentId());
    assertEquals(200L, result.assetId());
    assertEquals("huggingface-model-file", assets.asset.kind());
    assertEquals("model-file", assets.asset.attributes().get("huggingfaceRole"));
    assertEquals("weights/model.safetensors", assets.asset.attributes().get("filePath"));
    assertEquals((long) model.length, assets.asset.attributes().get("expectedSize"));
    verify(browse).upsertPathAncestors(
        14L, "org/model/" + commit + "/weights/model.safetensors", 200L, 501L);

    ArgumentCaptor<ComponentRecord> component = ArgumentCaptor.forClass(ComponentRecord.class);
    verify(components).upsertReturningId(component.capture());
    assertEquals("model-revision", component.getValue().kind());
    assertEquals("org/model", component.getValue().attributes().get("repoId"));
    assertEquals(commit, component.getValue().attributes().get("commit"));

    ArgumentCaptor<HuggingFaceRegistryDao.ModelFile> file =
        ArgumentCaptor.forClass(HuggingFaceRegistryDao.ModelFile.class);
    verify(registry).upsertFileMetadata(file.capture());
    assertEquals(HuggingFaceRegistryDao.FILE_READY, file.getValue().state());
    assertEquals(200L, file.getValue().assetId());
    assertEquals(sha256Unchecked(model), file.getValue().internalSha256());
  }

  @Test
  void rejectsAmbiguousOrUnverifiableHuggingFaceMigrationRecords() {
    byte[] model = "model-weights".getBytes(StandardCharsets.UTF_8);
    String commit = "0123456789abcdef0123456789abcdef01234567";
    String canonical = "org/model/resolve/" + commit + "/model.bin";
    RepositoryDao repositories = mock(RepositoryDao.class);
    when(repositories.findById(14L)).thenReturn(Optional.of(huggingFaceRepository()));
    ComponentDao components = mock(ComponentDao.class);
    when(components.upsertReturningId(any())).thenReturn(501L);
    RepositoryDataMigrationWriter writer = migrationWriter(
        repositories, components, new RecordingAssetDao(), mock(BrowseNodeDao.class));
    writer.setHuggingFaceRegistry(mock(HuggingFaceRegistryDao.class));

    assertThrows(IllegalStateException.class, () -> writer.write(
        14L,
        huggingFaceSource("org/model/resolve/main/model.bin", "org", "model", commit,
            model.length, Map.of("sha256", sha256Unchecked(model))),
        new ByteArrayInputStream(model), null, true));
    assertThrows(IllegalStateException.class, () -> writer.write(
        14L,
        huggingFaceSource(canonical.replace(commit, commit.toUpperCase()), "org", "model", commit,
            model.length, Map.of("sha256", sha256Unchecked(model))),
        new ByteArrayInputStream(model), null, true));
    assertThrows(IllegalStateException.class, () -> writer.write(
        14L,
        huggingFaceSource(canonical, "org", "model", "f".repeat(40),
            model.length, Map.of("sha256", sha256Unchecked(model))),
        new ByteArrayInputStream(model), null, true));
    assertThrows(IllegalStateException.class, () -> writer.write(
        14L,
        huggingFaceSource(canonical, "wrong", "identity", commit,
            model.length, Map.of("sha256", sha256Unchecked(model))),
        new ByteArrayInputStream(model), null, true));
    assertThrows(IllegalStateException.class, () -> writer.write(
        14L,
        huggingFaceSource(canonical, "org", "model", commit, model.length, Map.of()),
        new ByteArrayInputStream(model), null, true));
    assertThrows(IllegalStateException.class, () -> writer.write(
        14L,
        huggingFaceSource(canonical, "org", "model", commit, model.length,
            Map.of("sha-256", "a".repeat(64))),
        new ByteArrayInputStream(model), null, true));
  }

  @Test
  void failsClosedWhenHuggingFaceRegistryProjectionIsUnavailable() {
    byte[] model = "model-weights".getBytes(StandardCharsets.UTF_8);
    String commit = "0123456789abcdef0123456789abcdef01234567";
    String path = "model/resolve/" + commit + "/config.json";
    RepositoryDao repositories = mock(RepositoryDao.class);
    when(repositories.findById(14L)).thenReturn(Optional.of(huggingFaceRepository()));
    ComponentDao components = mock(ComponentDao.class);
    when(components.upsertReturningId(any())).thenReturn(501L);
    RepositoryDataMigrationWriter writer = migrationWriter(
        repositories, components, new RecordingAssetDao(), mock(BrowseNodeDao.class));

    assertThrows(IllegalStateException.class, () -> writer.write(
        14L,
        huggingFaceSource(path, null, "model", commit, model.length,
            Map.of("sha256", sha256Unchecked(model))),
        new ByteArrayInputStream(model), "application/json", true));
  }

  private static void assertChecksum(
      RepositoryDataMigrationWriter.GeneratedMavenChecksum checksum,
      String expectedPath,
      String expectedHex) {
    assertEquals(expectedPath, checksum.path().path());
    assertArrayEquals(expectedHex.getBytes(StandardCharsets.UTF_8), checksum.payload());
    assertEquals(expectedHex, new String(checksum.payload(), StandardCharsets.UTF_8));
  }

  private static String digest(HashType hashType) {
    try {
      MessageDigest digest = MessageDigest.getInstance(hashType.javaAlgorithm());
      return HexFormat.of().formatHex(digest.digest(SAMPLE));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("Missing digest algorithm " + hashType.javaAlgorithm(), e);
    }
  }

  private static RepositoryRecord dockerRepository() {
    return new RepositoryRecord(
        10L,
        "docker-hosted",
        RepositoryFormat.DOCKER,
        RepositoryType.HOSTED,
        "docker-hosted",
        true,
        1L,
        null,
        null,
        null,
        null,
        "ALLOW",
        true,
        Map.of());
  }

  private static RepositoryRecord cargoRepository() {
    return new RepositoryRecord(
        11L,
        "cargo-hosted",
        RepositoryFormat.CARGO,
        RepositoryType.HOSTED,
        "cargo-hosted",
        true,
        1L,
        null,
        null,
        null,
        null,
        "ALLOW",
        true,
        Map.of());
  }

  private static RepositoryRecord huggingFaceRepository() {
    return new RepositoryRecord(
        14L,
        "huggingface-proxy",
        RepositoryFormat.HUGGINGFACE,
        RepositoryType.PROXY,
        "huggingface-proxy",
        true,
        1L,
        null,
        "https://huggingface.co",
        null,
        null,
        "ALLOW",
        true,
        Map.of());
  }

  private static RepositoryDataMigrationWriter migrationWriter(
      RepositoryDao repositories,
      ComponentDao components,
      AssetDao assets,
      BrowseNodeDao browse) {
    return new RepositoryDataMigrationWriter(
        repositories,
        components,
        assets,
        browse,
        new FixedBlobStorageRegistry(new MemoryBlobStorage()),
        mock(RepositoryIndexRebuildDao.class),
        mock(DockerRegistryDao.class),
        new DockerManifestParser(new ObjectMapper()),
        null,
        new TransientTransactionRetry(new RecordingTransactionManager(), 1, 0));
  }

  private static RepositoryDao mockRepositoryDao() {
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    when(repositoryDao.findById(10L)).thenReturn(Optional.of(dockerRepository()));
    return repositoryDao;
  }

  private static RepositoryDataMigrationAssetRecord dockerManifestSource(String path, int size) {
    return dockerSource(path, "MANIFEST", DockerConstants.MEDIA_TYPE_OCI_MANIFEST, size);
  }

  private static RepositoryDataMigrationAssetRecord dockerBlobSource(String path, int size) {
    return dockerSource(path, "BLOB", "application/octet-stream", size);
  }

  private static RepositoryDataMigrationAssetRecord cargoSource(String path, int size) {
    Instant now = Instant.now();
    return new RepositoryDataMigrationAssetRecord(
        1L,
        2L,
        "cargo-asset-1",
        "cargo-component-1",
        path,
        PersistenceHashes.pathHash(path),
        RepositoryFormat.CARGO,
        null,
        "cargo_demo",
        "0.1.0",
        "crate",
        "application/x-tar",
        (long) size,
        "source-blob-ref",
        now.minusSeconds(60),
        null,
        now.minusSeconds(120),
        now.minusSeconds(60),
        "nexus-admin",
        "127.0.0.1",
        null,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of(),
        now.minusSeconds(180));
  }

  private static RepositoryDataMigrationAssetRecord huggingFaceSource(
      String path,
      String namespace,
      String name,
      String version,
      int size,
      Map<String, Object> metadata) {
    Instant now = Instant.parse("2026-08-17T00:00:00Z");
    return new RepositoryDataMigrationAssetRecord(
        1L,
        2L,
        "hf-source-asset",
        "hf-source-component",
        path,
        PersistenceHashes.pathHash(path),
        RepositoryFormat.HUGGINGFACE,
        namespace,
        name,
        version,
        "huggingface-model-file",
        "application/octet-stream",
        (long) size,
        "source-blob-ref",
        now.minusSeconds(60),
        null,
        now.minusSeconds(120),
        now.minusSeconds(60),
        "nexus-admin",
        "127.0.0.1",
        null,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        metadata,
        now.minusSeconds(180));
  }

  private static RepositoryDataMigrationAssetRecord condaSource(String path, int size) {
    return new RepositoryDataMigrationAssetRecord(
        1L, 2L, "source", "component", path, PersistenceHashes.pathHash(path),
        RepositoryFormat.CONDA, "main/noarch", "demo", "1.0", "conda-package",
        "application/vnd.conda.package.v2", (long) size, null,
        Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH,
        "nexus", "127.0.0.1", "PENDING", 0, null, null,
        null, null, null, null, Map.of("sha256", "a".repeat(64)), Instant.EPOCH);
  }

  private static RepositoryDataMigrationAssetRecord conanSource(String path, int size) {
    return new RepositoryDataMigrationAssetRecord(
        1L, 2L, "source", "component", path, PersistenceHashes.pathHash(path),
        RepositoryFormat.CONAN, null, "demo", "1.0", "conan-revision-file",
        "application/gzip", (long) size, null,
        Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH,
        "nexus", "127.0.0.1", "PENDING", 0, null, null,
        null, null, null, null, Map.of("sha1", "a".repeat(40)), Instant.EPOCH);
  }

  private static RepositoryRecord condaRepository() {
    return new RepositoryRecord(
        12L, "conda-hosted", RepositoryFormat.CONDA, RepositoryType.HOSTED,
        "conda-hosted", true, 1L, null, null, null, null, "ALLOW_ONCE", true, Map.of());
  }

  private static RepositoryRecord conanRepository() {
    return new RepositoryRecord(
        13L, "conan-hosted", RepositoryFormat.CONAN, RepositoryType.HOSTED,
        "conan-hosted", true, 1L, null, null, null, null, "ALLOW_ONCE", true, Map.of());
  }

  private static RepositoryDataMigrationAssetRecord dockerSource(
      String path,
      String assetKind,
      String contentType,
      int size) {
    Instant now = Instant.now();
    return new RepositoryDataMigrationAssetRecord(
        1L,
        2L,
        "#12:34",
        null,
        path,
        PersistenceHashes.pathHash(path),
        RepositoryFormat.DOCKER,
        null,
        "team/app",
        null,
        assetKind,
        contentType,
        (long) size,
        "source-blob-ref",
        now.minusSeconds(60),
        null,
        now.minusSeconds(120),
        now.minusSeconds(60),
        "nexus-admin",
        "127.0.0.1",
        null,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of(),
        now.minusSeconds(180));
  }

  private static byte[] dockerManifestBytes(String layerDigest) {
    return ("{"
        + "\"schemaVersion\":2,"
        + "\"mediaType\":\"" + DockerConstants.MEDIA_TYPE_OCI_MANIFEST + "\","
        + "\"config\":{\"mediaType\":\"application/vnd.oci.empty.v1+json\","
        + "\"digest\":\"sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\","
        + "\"size\":0},"
        + "\"layers\":[{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar\","
        + "\"digest\":\"" + layerDigest + "\","
        + "\"size\":11}]"
        + "}").getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] body) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
  }

  private static String sha256Unchecked(byte[] body) {
    try {
      return sha256(body);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("Missing SHA-256", e);
    }
  }

  private static byte[] cargoCrate(String name, String version, String manifest) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry(name + "-" + version + "/Cargo.toml");
      entry.setSize(manifestBytes.length);
      tar.putArchiveEntry(entry);
      tar.write(manifestBytes);
      tar.closeArchiveEntry();
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private static final class RecordingAssetDao extends AssetDaoAdapter {
    private AssetBlobRecord blob;
    private AssetRecord asset;

    private RecordingAssetDao() {
      super(null, null);
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      return asset != null && asset.repositoryId() == repositoryId && asset.path().equals(path)
          ? Optional.of(asset)
          : Optional.empty();
    }

    @Override
    public Optional<AssetBlobRecord> findReusableBlobBySha256(long blobStoreId, String sha256, long size) {
      return blob != null && blob.blobStoreId() == blobStoreId && blob.sha256().equals(sha256)
          && blob.size() == size
          ? Optional.of(blob)
          : Optional.empty();
    }

    @Override
    public AssetBlobRecord insertBlobOrFindExisting(AssetBlobRecord record) {
      blob = record.withId(100L);
      return blob;
    }

    @Override
    public OptionalLong tryInsertAsset(AssetRecord record) {
      asset = new AssetRecord(
          200L,
          record.repositoryId(),
          record.componentId(),
          record.assetBlobId(),
          record.format(),
          record.path(),
          record.pathHash(),
          record.name(),
          record.kind(),
          record.contentType(),
          record.size(),
          record.lastDownloadedAt(),
          record.lastUpdatedAt(),
          record.attributes());
      return OptionalLong.of(asset.id());
    }

    @Override
    public boolean hasLiveBlobForObjectKeyHash(long blobStoreId, byte[] objectKeyHash) {
      return blob != null && blob.blobStoreId() == blobStoreId;
    }
  }

  private static final class FixedBlobStorageRegistry extends BlobStorageRegistry {
    private final BlobStorage storage;

    private FixedBlobStorageRegistry(BlobStorage storage) {
      super(null, null, null, null, false);
      this.storage = storage;
    }

    @Override
    public BlobStorage forBlobStoreId(long blobStoreId) {
      return storage;
    }
  }

  private static final class MemoryBlobStorage implements BlobStorage {
    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      return new BlobReference("test", repository + "/" + logicalPath, sha256, size);
    }

    @Override
    public BlobReference putFile(String repository, String logicalPath, Path file, String sha256) {
      try {
        return new BlobReference("test", repository + "/" + logicalPath, sha256, Files.size(file));
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      return Optional.empty();
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
    }
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
    }
  }
}
