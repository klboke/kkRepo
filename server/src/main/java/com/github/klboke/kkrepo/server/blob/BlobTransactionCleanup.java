package com.github.klboke.kkrepo.server.blob;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlobTransactionCleanup {
  private static final Logger log = LoggerFactory.getLogger(BlobTransactionCleanup.class);

  private BlobTransactionCleanup() {
  }

  public static void deleteIfUnreferenced(
      AssetDao assetDao,
      BlobStorage storage,
      long blobStoreId,
      BlobReference reference,
      String reason) {
    if (assetDao == null || storage == null || reference == null) {
      return;
    }
    try {
      if (assetDao.hasLiveBlobForObjectKeyHash(blobStoreId, PersistenceHashes.objectKeyHash(reference.objectKey()))) {
        return;
      }
    } catch (RuntimeException e) {
      log.warn("Skipped deleting uploaded blob after {} because reference check failed: {}",
          reason, reference.objectKey(), e);
      return;
    }
    try {
      storage.delete(reference);
    } catch (RuntimeException e) {
      log.warn("Failed to delete uploaded blob after {}: {}", reason, reference.objectKey(), e);
    }
  }

  public static void deleteIfNotReferencedByMetadata(
      AssetDao assetDao,
      BlobStorage storage,
      long blobStoreId,
      BlobReference reference,
      String metadataObjectKey,
      String reason) {
    if (reference == null) {
      return;
    }
    if (metadataObjectKey != null && metadataObjectKey.equals(reference.objectKey())) {
      return;
    }
    deleteIfUnreferenced(assetDao, storage, blobStoreId, reference, reason);
  }
}
