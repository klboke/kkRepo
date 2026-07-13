package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class DockerBlobStoreTest {
  @Test
  void putBlobReusesExistingAssetBlobBeforeUploadingContentAddressedObject() {
    DockerDigest digest = DockerDigest.parse(
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    RecordingAssetDao assetDao = new RecordingAssetDao(existingBlob(digest, "existing-object"));
    RecordingBlobStorage storage = new RecordingBlobStorage();
    DockerBlobStore blobStore = new DockerBlobStore(
        assetDao,
        new FixedBlobStorageRegistry(storage),
        mock(AssetMetadataCache.class),
        null);

    DockerBlobStore.StoredBlob stored = blobStore.putBlob(
        runtime(),
        digest,
        new ByteArrayInputStream("ignored".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        7,
        "application/octet-stream",
        "alice",
        "127.0.0.1");

    assertEquals(0, storage.putCalls);
    assertEquals("existing-object", stored.blob().objectKey());
    assertEquals(List.of("docker/blobs/sha256/aa/" + digest.hex()), assetDao.reusedAssetPaths);
  }

  @Test
  void putBlobDeletesUploadedObjectOnPersistFailureOnlyWhenDatabaseHasNoLiveReference() {
    DockerDigest digest = DockerDigest.parse(
        "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    RecordingAssetDao assetDao = new RecordingAssetDao(null);
    assetDao.failAssetPersist = true;
    RecordingBlobStorage storage = new RecordingBlobStorage();
    DockerBlobStore blobStore = new DockerBlobStore(
        assetDao,
        new FixedBlobStorageRegistry(storage),
        mock(AssetMetadataCache.class),
        null);

    RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
        () -> blobStore.putBlob(
            runtime(),
            digest,
            new ByteArrayInputStream("content".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            7,
            "application/octet-stream",
            "alice",
            "127.0.0.1"));

    assertEquals("persist failed", thrown.getMessage());
    assertEquals(1, storage.deleted.size());
    assertEquals("docker/blobs/sha256/bb/" + digest.hex(), storage.deleted.get(0).objectKey());
  }

  @Test
  void putBlobKeepsUploadedObjectOnPersistFailureWhenAnotherRowAlreadyReferencesIt() {
    DockerDigest digest = DockerDigest.parse(
        "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
    RecordingAssetDao assetDao = new RecordingAssetDao(null);
    assetDao.failAssetPersist = true;
    assetDao.hasLiveBlobForObjectKey = true;
    RecordingBlobStorage storage = new RecordingBlobStorage();
    DockerBlobStore blobStore = new DockerBlobStore(
        assetDao,
        new FixedBlobStorageRegistry(storage),
        mock(AssetMetadataCache.class),
        null);

    org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
        () -> blobStore.putBlob(
            runtime(),
            digest,
            new ByteArrayInputStream("content".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            7,
            "application/octet-stream",
            "alice",
            "127.0.0.1"));

    assertTrue(storage.deleted.isEmpty());
  }

  @Test
  void putVerifiedBlobDeletesUploadedObjectWhenStreamDigestDoesNotMatchExpectedDigest() {
    DockerDigest expected = DockerDigest.parse(
        "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
    RecordingAssetDao assetDao = new RecordingAssetDao(null);
    RecordingBlobStorage storage = new RecordingBlobStorage();
    DockerBlobStore blobStore = new DockerBlobStore(
        assetDao,
        new FixedBlobStorageRegistry(storage),
        mock(AssetMetadataCache.class),
        null);

    DockerProtocolException thrown = org.junit.jupiter.api.Assertions.assertThrows(DockerProtocolException.class,
        () -> blobStore.putVerifiedBlob(
            runtime(),
            expected,
            new ByteArrayInputStream("actual-content".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "actual-content".length(),
            "application/octet-stream",
            "alice",
            "127.0.0.1"));

    assertEquals(DockerErrorCode.DIGEST_INVALID, thrown.code());
    assertEquals(1, storage.deleted.size());
    assertEquals("docker/blobs/sha256/dd/" + expected.hex(), storage.deleted.get(0).objectKey());
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        10L,
        "docker-hosted",
        RepositoryFormat.DOCKER,
        RepositoryType.HOSTED,
        "docker-hosted",
        true,
        1L,
        "ALLOW",
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        5000,
        null,
        List.of());
  }

  private static AssetBlobRecord existingBlob(DockerDigest digest, String objectKey) {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        300L,
        1L,
        "test:" + objectKey,
        PersistenceHashes.blobRefHash("test:" + objectKey),
        objectKey,
        PersistenceHashes.objectKeyHash(objectKey),
        null,
        digest.hex(),
        null,
        7,
        "application/octet-stream",
        "alice",
        "127.0.0.1",
        now,
        now,
        Map.of("dockerDigest", digest.value()));
  }

  private static final class RecordingAssetDao extends AssetDaoAdapter {
    private final AssetBlobRecord reusableBlob;
    private final List<String> reusedAssetPaths = new ArrayList<>();
    private boolean failAssetPersist;
    private boolean hasLiveBlobForObjectKey;

    private RecordingAssetDao(AssetBlobRecord reusableBlob) {
      super(null, null);
      this.reusableBlob = reusableBlob;
    }

    @Override
    public Optional<AssetBlobRecord> findReusableBlobBySha256(long blobStoreId, String sha256, long size) {
      return Optional.ofNullable(reusableBlob)
          .filter(blob -> blob.blobStoreId() == blobStoreId)
          .filter(blob -> blob.size() == size)
          .filter(blob -> blob.sha256().equals(sha256));
    }

    @Override
    public AssetBlobRecord insertBlobOrFindExisting(AssetBlobRecord record) {
      return record.withId(301L);
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      return Optional.empty();
    }

    @Override
    public OptionalLong tryInsertAsset(AssetRecord record) {
      if (failAssetPersist) {
        throw new RuntimeException("persist failed");
      }
      reusedAssetPaths.add(record.path());
      return OptionalLong.of(400L);
    }

    @Override
    public boolean hasLiveBlobForObjectKeyHash(long blobStoreId, byte[] objectKeyHash) {
      return hasLiveBlobForObjectKey;
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

  private static final class RecordingBlobStorage implements BlobStorage {
    private final List<BlobReference> deleted = new ArrayList<>();
    private int putCalls;

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      putCalls++;
      return new BlobReference("test", logicalPath, sha256, size);
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public boolean exists(BlobReference reference) {
      return false;
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
      deleted.add(reference);
    }
  }
}
