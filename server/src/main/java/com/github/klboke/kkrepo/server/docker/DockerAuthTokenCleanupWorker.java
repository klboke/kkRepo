package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes expired Docker bearer tokens independently of upload-staging cleanup.
 *
 * <p>Every replica may run this worker. The DAO claims a bounded batch with {@code FOR UPDATE SKIP
 * LOCKED}, so replicas share the work without relying on process-local ownership.
 */
@Component
class DockerAuthTokenCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(DockerAuthTokenCleanupWorker.class);

  private final DockerAuthTokenDao authTokenDao;
  private final TransactionTemplate transactionTemplate;
  private final int batchSize;
  private final int maxItemsPerRun;

  DockerAuthTokenCleanupWorker(
      DockerAuthTokenDao authTokenDao,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.docker.auth-token-cleanup.batch-size:256}") int batchSize,
      @Value("${kkrepo.docker.auth-token-cleanup.max-items-per-run:4096}")
          int maxItemsPerRun) {
    this.authTokenDao = authTokenDao;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.batchSize = Math.max(1, Math.min(10_000, batchSize));
    this.maxItemsPerRun = Math.max(1, Math.min(1_000_000, maxItemsPerRun));
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.docker.auth-token-cleanup.interval-ms:60000}",
      initialDelayString = "${kkrepo.docker.auth-token-cleanup.initial-delay-ms:60000}")
  public void cleanup() {
    try {
      Instant cutoff = Instant.now();
      int remaining = maxItemsPerRun;
      while (remaining > 0) {
        int requested = Math.min(batchSize, remaining);
        int deleted = transactionTemplate.execute(
            status -> authTokenDao.deleteExpired(cutoff, requested));
        if (deleted <= 0) {
          return;
        }
        remaining -= Math.min(deleted, requested);
        if (deleted < requested) {
          return;
        }
      }
    } catch (RuntimeException e) {
      log.warn("Docker auth token cleanup failed; will retry next cycle", e);
    }
  }
}
