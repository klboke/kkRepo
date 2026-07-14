package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerUploadDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadChunkRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadSessionRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cleans Docker Registry V2 upload staging state from durable storage.
 *
 * <p>Upload sessions and chunk rows are shared-database truth; staged chunk bytes live in the repository's
 * shared blob store. Multiple replicas can run this worker concurrently because terminal sessions
 * are claimed with {@code FOR UPDATE SKIP LOCKED} and a short lease before chunk object deletion.
 */
@Component
class DockerUploadCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(DockerUploadCleanupWorker.class);

  private final DockerUploadDao uploadDao;
  private final DockerAuthTokenDao authTokenDao;
  private final RepositoryRuntimeRegistry runtimeRegistry;
  private final BlobStorageRegistry blobStorageRegistry;
  private final TransactionTemplate transactionTemplate;
  private final boolean enabled;
  private final int batchSize;
  private final long leaseSeconds;
  private final String owner;

  DockerUploadCleanupWorker(
      DockerUploadDao uploadDao,
      DockerAuthTokenDao authTokenDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      BlobStorageRegistry blobStorageRegistry,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.docker.cleanup.enabled:true}") boolean enabled,
      @Value("${kkrepo.docker.cleanup.batch-size:64}") int batchSize,
      @Value("${kkrepo.docker.cleanup.lease-seconds:300}") long leaseSeconds) {
    this.uploadDao = uploadDao;
    this.authTokenDao = authTokenDao;
    this.runtimeRegistry = runtimeRegistry;
    this.blobStorageRegistry = blobStorageRegistry;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.enabled = enabled;
    this.batchSize = Math.max(1, batchSize);
    this.leaseSeconds = Math.max(30, leaseSeconds);
    this.owner = ownerId();
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.docker.cleanup.interval-ms:60000}",
      initialDelayString = "${kkrepo.docker.cleanup.initial-delay-ms:60000}")
  public void cleanup() {
    if (!enabled) {
      return;
    }
    try {
      Instant now = Instant.now();
      transactionTemplate.executeWithoutResult(status -> {
        uploadDao.expireSessions(now);
        authTokenDao.deleteExpired(now);
      });
      cleanupUploadSessions(now);
    } catch (RuntimeException e) {
      log.warn("Docker upload cleanup failed; will retry next cycle", e);
    }
  }

  private void cleanupUploadSessions(Instant now) {
    List<DockerUploadSessionRecord> sessions = transactionTemplate.execute(status ->
        uploadDao.claimTerminalSessions(now, owner, now.plusSeconds(leaseSeconds), batchSize));
    if (sessions == null || sessions.isEmpty()) {
      return;
    }
    for (DockerUploadSessionRecord session : sessions) {
      cleanupOne(session);
    }
  }

  private void cleanupOne(DockerUploadSessionRecord session) {
    List<DockerUploadChunkRecord> chunks = uploadDao.listChunks(session.uuid());
    if (!deleteChunks(session, chunks)) {
      return;
    }
    transactionTemplate.executeWithoutResult(status -> uploadDao.deleteSession(session.uuid()));
  }

  private boolean deleteChunks(DockerUploadSessionRecord session, List<DockerUploadChunkRecord> chunks) {
    RepositoryRuntime runtime = runtimeRegistry.resolveById(session.repositoryId()).orElse(null);
    if (runtime == null || runtime.blobStoreId() == null) {
      log.warn("Skipping Docker upload session cleanup until repository runtime is available: uuid={} repositoryId={}",
          session.uuid(), session.repositoryId());
      return false;
    }
    BlobStorage storage;
    try {
      storage = blobStorageRegistry.forBlobStoreId(runtime.blobStoreId());
    } catch (RuntimeException e) {
      log.warn("Skipping Docker upload session cleanup until blob store is available: uuid={} blobStoreId={}",
          session.uuid(), runtime.blobStoreId(), e);
      return false;
    }
    for (DockerUploadChunkRecord chunk : chunks) {
      if (chunk.blobRef() == null || chunk.objectKey() == null) {
        continue;
      }
      try {
        BlobReference reference = BlobReferenceCodec.reference(
            chunk.blobRef(), chunk.objectKey(), chunk.sha256(), chunk.size());
        storage.delete(reference);
      } catch (RuntimeException e) {
        log.warn("Failed deleting Docker upload chunk uuid={} chunk={} objectKey={}",
            session.uuid(), chunk.id(), chunk.objectKey(), e);
        throw e;
      }
    }
    return true;
  }

  private static String ownerId() {
    String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
    return "docker-cleanup:" + runtimeName + ":" + UUID.randomUUID();
  }
}
