package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.docker.DockerTagRecord;
import com.github.klboke.kkrepo.persistence.mysql.support.HashColumns;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.maven.path.HashType;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.docker.DockerManifestParser;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
        HashColumns.pathHash(path),
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
        HashColumns.pathHash(path),
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

  private static final class RecordingAssetDao extends AssetDao {
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
