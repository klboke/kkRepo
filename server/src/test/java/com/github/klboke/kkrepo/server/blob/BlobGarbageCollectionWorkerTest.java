package com.github.klboke.kkrepo.server.blob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.BlobReconcileWindow;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class BlobGarbageCollectionWorkerTest {

  @Test
  void disabledWorkerDoesNotTouchSharedState() {
    AssetDao assetDao = mock(AssetDao.class);
    MaintenanceCursorDao cursorDao = mock(MaintenanceCursorDao.class);
    BlobStorageRegistry storageRegistry = mock(BlobStorageRegistry.class);

    worker(assetDao, cursorDao, storageRegistry, false, new SimpleMeterRegistry()).drain();

    verifyNoInteractions(assetDao, cursorDao, storageRegistry);
  }

  @Test
  void lockedReconcileCursorSkipsScanAndRecordsLockOutcome() {
    AssetDao assetDao = mock(AssetDao.class);
    MaintenanceCursorDao cursorDao = mock(MaintenanceCursorDao.class);
    BlobStorageRegistry storageRegistry = mock(BlobStorageRegistry.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(cursorDao.tryLockLastSeenId(MaintenanceCursorDao.BLOB_UNREFERENCED_RECONCILE))
        .thenReturn(OptionalLong.empty());
    when(assetDao.claimDeletedBlobsForGc(anyInt(), any(), any())).thenReturn(List.of());

    worker(assetDao, cursorDao, storageRegistry, true, registry).drain();

    verify(assetDao, never()).markUnreferencedBlobsDeletedAfter(anyLong(), anyInt(), anyInt(), any());
    assertNotNull(registry.find("kkrepo_worker_items_total")
        .tags("worker", "blob_gc", "kind", "reconcile", "outcome", "locked")
        .counter());
  }

  @Test
  void reconcileAdvancesCursorAndCollectsUnreferencedBlob() {
    AssetDao assetDao = mock(AssetDao.class);
    MaintenanceCursorDao cursorDao = mock(MaintenanceCursorDao.class);
    BlobStorageRegistry storageRegistry = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    AssetBlobRecord blob = blob(11L);
    when(cursorDao.tryLockLastSeenId(MaintenanceCursorDao.BLOB_UNREFERENCED_RECONCILE))
        .thenReturn(OptionalLong.of(7L));
    when(assetDao.markUnreferencedBlobsDeletedAfter(7L, 32, 16, "unreferenced blob reconcile"))
        .thenReturn(new BlobReconcileWindow(1, 4, 12L, false));
    when(assetDao.claimDeletedBlobsForGc(eq(8), any(), any())).thenReturn(List.of(blob));
    when(assetDao.lockDeletedBlobById(11L)).thenReturn(Optional.of(blob));
    when(assetDao.hasLiveBlobForObjectKeyHash(3L, blob.objectKeyHash())).thenReturn(false);
    when(storageRegistry.forBlobStoreId(3L)).thenReturn(storage);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    worker(assetDao, cursorDao, storageRegistry, true, registry).drain();

    verify(cursorDao).updateLastSeenId(MaintenanceCursorDao.BLOB_UNREFERENCED_RECONCILE, 12L);
    verify(storage).delete(argThat(reference ->
        "bucket".equals(reference.bucket())
            && blob.objectKey().equals(reference.objectKey())
            && blob.sha256().equals(reference.sha256())
            && blob.size() == reference.size()));
    verify(assetDao).hardDeleteBlobByIdIfDeleted(11L);
    assertEquals(42.0, registry.get("kkrepo_blob_gc_deleted_bytes_total").counter().count());
  }

  @Test
  void liveReferenceKeepsPhysicalObjectButRemovesDeletedBlobRow() {
    AssetDao assetDao = mock(AssetDao.class);
    MaintenanceCursorDao cursorDao = mock(MaintenanceCursorDao.class);
    BlobStorageRegistry storageRegistry = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    AssetBlobRecord blob = blob(12L);
    stubLockedReconcile(cursorDao);
    when(assetDao.claimDeletedBlobsForGc(anyInt(), any(), any())).thenReturn(List.of(blob));
    when(assetDao.lockDeletedBlobById(12L)).thenReturn(Optional.of(blob));
    when(assetDao.hasLiveBlobForObjectKeyHash(3L, blob.objectKeyHash())).thenReturn(true);
    when(storageRegistry.forBlobStoreId(3L)).thenReturn(storage);

    worker(assetDao, cursorDao, storageRegistry, true, new SimpleMeterRegistry()).drain();

    verify(storage, never()).delete(any());
    verify(assetDao).hardDeleteBlobByIdIfDeleted(12L);
  }

  @Test
  void storageFailureReleasesClaimForRetry() {
    AssetDao assetDao = mock(AssetDao.class);
    MaintenanceCursorDao cursorDao = mock(MaintenanceCursorDao.class);
    BlobStorageRegistry storageRegistry = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    AssetBlobRecord blob = blob(13L);
    stubLockedReconcile(cursorDao);
    when(assetDao.claimDeletedBlobsForGc(anyInt(), any(), any())).thenReturn(List.of(blob));
    when(assetDao.lockDeletedBlobById(13L)).thenReturn(Optional.of(blob));
    when(assetDao.hasLiveBlobForObjectKeyHash(3L, blob.objectKeyHash())).thenReturn(false);
    when(storageRegistry.forBlobStoreId(3L)).thenReturn(storage);
    doThrow(new IllegalStateException("storage unavailable")).when(storage).delete(any());

    worker(assetDao, cursorDao, storageRegistry, true, new SimpleMeterRegistry()).drain();

    verify(assetDao).releaseBlobGcClaim(13L);
    verify(assetDao, never()).hardDeleteBlobByIdIfDeleted(13L);
  }

  private static void stubLockedReconcile(MaintenanceCursorDao cursorDao) {
    when(cursorDao.tryLockLastSeenId(MaintenanceCursorDao.BLOB_UNREFERENCED_RECONCILE))
        .thenReturn(OptionalLong.empty());
  }

  private static BlobGarbageCollectionWorker worker(
      AssetDao assetDao,
      MaintenanceCursorDao cursorDao,
      BlobStorageRegistry storageRegistry,
      boolean enabled,
      SimpleMeterRegistry registry) {
    return new BlobGarbageCollectionWorker(
        assetDao,
        cursorDao,
        storageRegistry,
        new RecordingTransactionManager(),
        enabled,
        8,
        32,
        16,
        0,
        60,
        new KkRepoMetrics(registry));
  }

  private static AssetBlobRecord blob(long id) {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        id,
        3L,
        "blob://bucket/raw/demo.bin",
        new byte[] {1},
        "raw/demo.bin",
        new byte[] {2},
        "sha1",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "md5",
        42L,
        "application/octet-stream",
        "alice",
        "127.0.0.1",
        now.minusSeconds(120),
        now.minusSeconds(60),
        Map.of());
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
