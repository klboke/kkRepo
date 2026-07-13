package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.pub.PubContentTypes;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.ComponentDaoAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

class PubRepositoryDataMigrationWriterTest {

  @Test
  void migratesNexusArchivePathIntoStablePubArchiveComponent() throws Exception {
    byte[] archive = archiveBytes("example_package", "1.2.3");
    String sha256 = sha256(archive);
    RecordingComponentDao componentDao = new RecordingComponentDao();
    RecordingAssetDao assetDao = new RecordingAssetDao();
    PubAssetWriter assetWriter = new PubAssetWriter(
        assetDao,
        componentDao,
        mock(BrowseNodeDao.class),
        mock(AssetMetadataCache.class),
        null);
    PubRepositoryDataMigrationWriter writer = new PubRepositoryDataMigrationWriter(
        assetWriter,
        new ObjectMapper());

    PubRepositoryDataMigrationWriter.MigratedAsset result = writer.write(
        pubRepository(),
        new MemoryBlobStorage(),
        pubSource("api/archives/example_package-1.2.3.tar.gz", archive.length, sha256),
        new ByteArrayInputStream(archive),
        PubContentTypes.ARCHIVE,
        true);

    assertEquals(501L, result.componentId());
    assertEquals(601L, result.assetId());
    assertEquals(701L, result.assetBlobId());
    assertEquals("example_package", componentDao.component.name());
    assertEquals("1.2.3", componentDao.component.version());
    assertEquals("pub-package", componentDao.component.kind());
    assertEquals("packages/example_package/versions/1.2.3.tar.gz",
        componentDao.component.attributes().get("archivePath"));
    assertEquals(sha256, componentDao.component.attributes().get("archiveSha256"));
    assertEquals("2025-02-03T04:05:06Z", componentDao.component.attributes().get("publishedAt"));
    assertEquals("api/archives/example_package-1.2.3.tar.gz",
        componentDao.component.attributes().get("sourcePath"));
    assertEquals("packages/example_package/versions/1.2.3.tar.gz", assetDao.asset.path());
    assertEquals("archive", assetDao.asset.kind());
  }

  private static RepositoryRecord pubRepository() {
    return new RepositoryRecord(
        10L,
        "pub-hosted",
        RepositoryFormat.PUB,
        RepositoryType.HOSTED,
        "pub-hosted",
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

  private static RepositoryDataMigrationAssetRecord pubSource(String path, int size, String sha256) {
    Instant published = Instant.parse("2025-02-03T04:05:06Z");
    return new RepositoryDataMigrationAssetRecord(
        1L,
        2L,
        "pub-asset-1",
        "pub-component-1",
        path,
        PersistenceHashes.pathHash(path),
        RepositoryFormat.PUB,
        null,
        "example_package",
        "1.2.3",
        "archive",
        PubContentTypes.ARCHIVE,
        (long) size,
        "source-blob-ref",
        published,
        null,
        published,
        published,
        "admin",
        "127.0.0.1",
        null,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of(
            "sourceRepositoryType", "HOSTED",
            "attributes", Map.of("checksums", Map.of("sha256", sha256))),
        Instant.parse("2025-02-03T04:00:00Z"));
  }

  private static byte[] archiveBytes(String name, String version) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(out);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      TarArchiveEntry root = new TarArchiveEntry("./");
      root.setSize(0);
      tar.putArchiveEntry(root);
      tar.closeArchiveEntry();
      byte[] body = ("name: " + name + "\nversion: " + version + "\n")
          .getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry("./pubspec.yaml");
      entry.setSize(body.length);
      tar.putArchiveEntry(entry);
      tar.write(body);
      tar.closeArchiveEntry();
    }
    return out.toByteArray();
  }

  private static String sha256(byte[] body) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
  }

  private static final class RecordingComponentDao extends ComponentDaoAdapter {
    private ComponentRecord component;

    private RecordingComponentDao() {
      super(null, null);
    }

    @Override
    public Optional<ComponentRecord> findByNameAndVersion(long repositoryId, String name, String version) {
      return Optional.empty();
    }

    @Override
    public long insert(ComponentRecord record) {
      component = new ComponentRecord(
          501L,
          record.repositoryId(),
          record.format(),
          record.namespace(),
          record.name(),
          record.version(),
          record.kind(),
          record.coordinateHash(),
          record.attributes(),
          record.lastUpdatedAt());
      return component.id();
    }
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
      return Optional.empty();
    }

    @Override
    public AssetBlobRecord insertBlobOrFindExisting(AssetBlobRecord record) {
      blob = record.withId(701L);
      return blob;
    }

    @Override
    public OptionalLong tryInsertAsset(AssetRecord record) {
      asset = new AssetRecord(
          601L,
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
}
