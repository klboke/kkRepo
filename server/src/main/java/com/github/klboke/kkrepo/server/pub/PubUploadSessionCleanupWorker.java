package com.github.klboke.kkrepo.server.pub;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.persistence.mysql.dao.PubUploadSessionDao;
import com.github.klboke.kkrepo.persistence.mysql.model.PubUploadSessionRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cleans expired Pub publish staging blobs from the shared blob store.
 *
 * <p>Upload session state is durable in MySQL, so any replica can retry this cleanup. Each candidate
 * row is locked and rechecked before blob deletion; if a blob store is temporarily unavailable, the
 * row remains open and the next cleanup cycle retries it.
 */
@Component
class PubUploadSessionCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(PubUploadSessionCleanupWorker.class);

  private final PubUploadSessionDao uploadSessionDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final TransactionTemplate transactionTemplate;
  private final boolean enabled;
  private final int batchSize;

  PubUploadSessionCleanupWorker(
      PubUploadSessionDao uploadSessionDao,
      BlobStorageRegistry blobStorageRegistry,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.pub.upload-cleanup.enabled:true}") boolean enabled,
      @Value("${kkrepo.pub.upload-cleanup.batch-size:64}") int batchSize) {
    this.uploadSessionDao = uploadSessionDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.enabled = enabled;
    this.batchSize = Math.max(1, batchSize);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.pub.upload-cleanup.interval-ms:60000}",
      initialDelayString = "${kkrepo.pub.upload-cleanup.initial-delay-ms:60000}")
  public void cleanup() {
    if (!enabled) {
      return;
    }
    try {
      Instant now = Instant.now();
      List<PubUploadSessionRecord> sessions =
          uploadSessionDao.listExpiredOpen(now, batchSize);
      for (PubUploadSessionRecord session : sessions) {
        cleanupOne(session.id(), now);
      }
    } catch (RuntimeException e) {
      log.warn("Pub upload cleanup failed; will retry next cycle", e);
    }
  }

  private void cleanupOne(long sessionRowId, Instant now) {
    transactionTemplate.executeWithoutResult(status -> {
      PubUploadSessionRecord locked = uploadSessionDao.lockById(sessionRowId).orElse(null);
      if (!isExpiredOpen(locked, now)) {
        return;
      }
      if (!deleteStagedBlob(locked)) {
        return;
      }
      uploadSessionDao.markFailed(locked.id(), "Pub upload session expired");
    });
  }

  private static boolean isExpiredOpen(PubUploadSessionRecord session, Instant now) {
    return session != null
        && (PubUploadSessionDao.STATUS_NEW.equals(session.status())
            || PubUploadSessionDao.STATUS_UPLOADED.equals(session.status()))
        && session.expiresAt() != null
        && session.expiresAt().isBefore(now);
  }

  private boolean deleteStagedBlob(PubUploadSessionRecord session) {
    if (session.blobRef() == null || session.objectKey() == null || session.blobStoreId() == null) {
      return true;
    }
    try {
      BlobReference reference = BlobReferenceCodec.reference(
          session.blobRef(),
          session.objectKey(),
          session.sha256(),
          session.size() == null ? 0L : session.size());
      blobStorageRegistry.forBlobStoreId(session.blobStoreId()).delete(reference);
      return true;
    } catch (RuntimeException e) {
      log.warn("Failed deleting expired Pub upload session staging blob sessionId={} objectKey={}",
          session.sessionId(), session.objectKey(), e);
      return false;
    }
  }
}
