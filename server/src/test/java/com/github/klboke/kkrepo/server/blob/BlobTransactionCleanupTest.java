package com.github.klboke.kkrepo.server.blob;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BlobTransactionCleanupTest {

  @Test
  void deletesUploadedBlobWhenItHasNoLiveDatabaseReference() {
    RecordingBlobStorage storage = new RecordingBlobStorage();
    BlobReference reference = new BlobReference("disk", "repositories/repo/blob", "sha256", 4);

    BlobTransactionCleanup.deleteIfUnreferenced(
        new RecordingAssetDao(false), storage, 12, reference, "test");

    assertEquals(List.of(reference), storage.deleted);
  }

  @Test
  void keepsUploadedBlobWhenItHasLiveDatabaseReference() {
    RecordingBlobStorage storage = new RecordingBlobStorage();
    BlobReference reference = new BlobReference("disk", "repositories/repo/blob", "sha256", 4);

    BlobTransactionCleanup.deleteIfUnreferenced(
        new RecordingAssetDao(true), storage, 12, reference, "test");

    assertEquals(List.of(), storage.deleted);
  }

  @Test
  void keepsUploadedBlobWhenMetadataUsesSameObjectKey() {
    RecordingBlobStorage storage = new RecordingBlobStorage();
    BlobReference reference = new BlobReference("disk", "repositories/repo/blob", "sha256", 4);

    BlobTransactionCleanup.deleteIfNotReferencedByMetadata(
        new RecordingAssetDao(false), storage, 12, reference, "repositories/repo/blob", "test");

    assertEquals(List.of(), storage.deleted);
  }

  @Test
  void deletesUploadedBlobWhenMetadataReusedAnotherObjectKey() {
    RecordingBlobStorage storage = new RecordingBlobStorage();
    BlobReference reference = new BlobReference("disk", "repositories/repo/blob-new", "sha256", 4);

    BlobTransactionCleanup.deleteIfNotReferencedByMetadata(
        new RecordingAssetDao(false), storage, 12, reference, "repositories/repo/blob-old", "test");

    assertEquals(List.of(reference), storage.deleted);
  }

  private static final class RecordingBlobStorage implements BlobStorage {
    private final List<BlobReference> deleted = new ArrayList<>();

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean exists(BlobReference reference) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(BlobReference reference) {
      deleted.add(reference);
    }
  }

  private static final class RecordingAssetDao extends AssetDaoAdapter {
    private final boolean hasLiveBlob;

    private RecordingAssetDao(boolean hasLiveBlob) {
      super(null, null);
      this.hasLiveBlob = hasLiveBlob;
    }

    @Override
    public boolean hasLiveBlobForObjectKeyHash(long blobStoreId, byte[] objectKeyHash) {
      return hasLiveBlob;
    }
  }
}
