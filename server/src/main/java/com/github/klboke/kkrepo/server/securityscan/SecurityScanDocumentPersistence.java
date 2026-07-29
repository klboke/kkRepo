package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobReferenceDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomically fences scanner document blob rows before they become visible to blob GC. */
@Service
public class SecurityScanDocumentPersistence {
  static final String PERSISTING_OWNER = "security-scan-persisting";

  private final AssetDao assets;
  private final BlobReferenceDao blobReferences;

  public SecurityScanDocumentPersistence(AssetDao assets, BlobReferenceDao blobReferences) {
    this.assets = assets;
    this.blobReferences = blobReferences;
  }

  @Transactional
  public Optional<AssetBlobRecord> findReusableAndRetain(
      long ownerId, long blobStoreId, String sha256, long size) {
    Optional<AssetBlobRecord> reusable =
        assets.findReusableBlobBySha256(blobStoreId, sha256, size);
    reusable.ifPresent(blob -> retainOrThrow(ownerId, blob.id()));
    return reusable;
  }

  @Transactional
  public AssetBlobRecord insertOrRecoverAndRetain(long ownerId, AssetBlobRecord proposed) {
    AssetBlobRecord stored = assets.insertBlobOrFindExisting(proposed);
    if (blobReferences.retain(PERSISTING_OWNER, ownerId, stored.id())) {
      return stored;
    }
    AssetBlobRecord restored = assets.restoreDeletedBlobById(stored.id())
        .orElseThrow(() -> unavailable(stored.id()));
    retainOrThrow(ownerId, restored.id());
    return restored;
  }

  @Transactional
  public void release(long ownerId, long blobId) {
    blobReferences.release(PERSISTING_OWNER, ownerId, blobId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseOwner(long ownerId) {
    blobReferences.releaseOwner(PERSISTING_OWNER, ownerId);
  }

  private void retainOrThrow(long ownerId, long blobId) {
    if (!blobReferences.retain(PERSISTING_OWNER, ownerId, blobId)) {
      throw unavailable(blobId);
    }
  }

  private static IllegalStateException unavailable(long blobId) {
    return new IllegalStateException(
        "Security scan document blob is unavailable for provisional ownership: " + blobId);
  }
}
