package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.PubUploadSessionDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.PubUploadSessionRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PubUploadSessionCleanupWorkerTest {
  @Test
  void cleanupLocksExpiredSessionBeforeDeletingBlobAndMarkingFailed() {
    PubUploadSessionDao uploadSessionDao = mock(PubUploadSessionDao.class);
    BlobStorageRegistry blobStorageRegistry = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    PubUploadSessionRecord expired = session(PubUploadSessionDao.STATUS_UPLOADED, Instant.now().minusSeconds(60));
    when(uploadSessionDao.listExpiredOpen(any(), anyInt())).thenReturn(List.of(expired));
    when(uploadSessionDao.lockById(expired.id())).thenReturn(Optional.of(expired));
    when(blobStorageRegistry.forBlobStoreId(expired.blobStoreId())).thenReturn(storage);
    PubUploadSessionCleanupWorker worker = new PubUploadSessionCleanupWorker(
        uploadSessionDao, blobStorageRegistry, transactions, true, 64);

    worker.cleanup();

    verify(uploadSessionDao).lockById(expired.id());
    verify(storage).delete(argThat(reference ->
        "test-bucket".equals(reference.bucket())
            && "pub/uploads/session-1.tar.gz".equals(reference.objectKey())
            && expired.sha256().equals(reference.sha256())
            && expired.size() == reference.size()));
    verify(uploadSessionDao).markFailed(expired.id(), "Pub upload session expired");
    assertEquals(1, transactions.committed);
  }

  @Test
  void cleanupSkipsSessionWhenLockedRowIsNoLongerExpiredOpen() {
    PubUploadSessionDao uploadSessionDao = mock(PubUploadSessionDao.class);
    BlobStorageRegistry blobStorageRegistry = mock(BlobStorageRegistry.class);
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    PubUploadSessionRecord candidate = session(PubUploadSessionDao.STATUS_UPLOADED, Instant.now().minusSeconds(60));
    PubUploadSessionRecord finalized = session(PubUploadSessionDao.STATUS_FINALIZED, Instant.now().minusSeconds(60));
    when(uploadSessionDao.listExpiredOpen(any(), anyInt())).thenReturn(List.of(candidate));
    when(uploadSessionDao.lockById(candidate.id())).thenReturn(Optional.of(finalized));
    PubUploadSessionCleanupWorker worker = new PubUploadSessionCleanupWorker(
        uploadSessionDao, blobStorageRegistry, transactions, true, 64);

    worker.cleanup();

    verify(blobStorageRegistry, never()).forBlobStoreId(anyLong());
    verify(uploadSessionDao, never()).markFailed(anyLong(), any());
    assertEquals(1, transactions.committed);
  }

  private static PubUploadSessionRecord session(String status, Instant expiresAt) {
    Instant now = Instant.now();
    return new PubUploadSessionRecord(
        11L,
        2L,
        "session-1",
        "field-token",
        "alice",
        null,
        status,
        expiresAt,
        3L,
        "blob://test-bucket/pub/uploads/session-1.tar.gz",
        "pub/uploads/session-1.tar.gz",
        "md5",
        "sha1",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "sha512",
        10L,
        "demo_pkg",
        "1.0.0",
        Map.of(),
        null,
        null,
        now.minusSeconds(120),
        now.minusSeconds(60));
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    int committed;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
      committed++;
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
    }
  }
}
