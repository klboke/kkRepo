package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.BackfillJob;
import com.github.klboke.kkrepo.security.scan.ScanEnums.BackfillStatus;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cursor-based history discovery; repeated pages are idempotent and bounded. */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.security-scanning", name = "enabled", havingValue = "true")
public class SecurityScanBackfillWorker {
  private static final Logger log = LoggerFactory.getLogger(SecurityScanBackfillWorker.class);

  private final SecurityScanDao scans;
  private final SecurityScanBackfillCoordinator coordinator;
  private final SecurityScanningProperties properties;
  private final String workerId = "backfill-" + UUID.randomUUID();

  public SecurityScanBackfillWorker(
      SecurityScanDao scans,
      SecurityScanBackfillCoordinator coordinator,
      SecurityScanningProperties properties) {
    this.scans = scans;
    this.coordinator = coordinator;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.backfill-delay-ms:1000}",
      initialDelayString = "${kkrepo.security-scanning.initial-delay-ms:5000}")
  public void runOnce() {
    for (BackfillJob job : coordinator.claim(workerId)) {
      process(job);
    }
  }

  private void process(BackfillJob job) {
    long cursor = job.cursorAssetId();
    long scannedAssets = job.scannedAssets();
    long markedAssets = job.markedAssets();
    if (job.attempts() > properties.getWorker().getMaxAttempts()) {
      if (!scans.updateBackfillProgress(
          job.id(),
          job.leaseToken(),
          cursor,
          scannedAssets,
          markedAssets,
          BackfillStatus.FAILED,
          "Backfill lease expired after the final permitted attempt",
          null,
          Instant.now())) {
        log.debug("Security scan backfill lease was lost while exhausting job: {}", job.id());
      }
      return;
    }
    try {
      int maxPages = properties.getWorker().getBackfillMaxPagesPerRun();
      for (int pageNumber = 0; pageNumber < maxPages; pageNumber++) {
        var page = scans.markRepositoryAssetsForBackfill(
            job.repositoryId(),
            cursor,
            properties.getWorker().getBackfillBatchSize());
        cursor = page.nextAssetId();
        scannedAssets += page.scannedAssets();
        markedAssets += page.markedAssets();
        boolean release = !page.complete() && pageNumber + 1 == maxPages;
        BackfillStatus status = page.complete()
            ? BackfillStatus.SUCCEEDED
            : release ? BackfillStatus.PENDING : BackfillStatus.RUNNING;
        Instant now = Instant.now();
        if (!scans.updateBackfillProgress(
            job.id(),
            job.leaseToken(),
            cursor,
            scannedAssets,
            markedAssets,
            status,
            null,
            status == BackfillStatus.RUNNING
                ? now.plusSeconds(properties.getWorker().getLeaseSeconds()) : null,
            now)) {
          log.debug("Security scan backfill lease was lost: {}", job.id());
          return;
        }
        if (status != BackfillStatus.RUNNING) {
          return;
        }
      }
    } catch (RuntimeException e) {
      Instant now = Instant.now();
      boolean retry =
          isRetryable(e) && job.attempts() < properties.getWorker().getMaxAttempts();
      try {
        boolean updated = retry
            ? scans.requeueBackfill(
                job.id(),
                job.leaseToken(),
                cursor,
                scannedAssets,
                markedAssets,
                safeMessage(e),
                now.plusSeconds(backoffSeconds(job.attempts())),
                now)
            : scans.updateBackfillProgress(
                job.id(),
                job.leaseToken(),
                cursor,
                scannedAssets,
                markedAssets,
                BackfillStatus.FAILED,
                safeMessage(e),
                null,
                now);
        if (!updated) {
          log.debug("Security scan backfill lease was lost after failure: {}", job.id());
        }
      } catch (RuntimeException persistenceFailure) {
        e.addSuppressed(persistenceFailure);
      }
      if (retry) {
        log.warn("Security scan backfill failed transiently and was requeued: {}", job.id(), e);
      } else {
        log.warn("Security scan backfill failed: {}", job.id(), e);
      }
    }
  }

  private long backoffSeconds(int attempts) {
    int exponent = Math.max(0, Math.min(20, attempts - 1));
    long maximum = properties.getWorker().getMaxBackoffSeconds();
    long base = Math.min(maximum, 5L << exponent);
    long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, base / 4 + 1));
    return Math.min(maximum, base + jitter);
  }

  private static boolean isRetryable(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof TransientDataAccessException
          || current instanceof RecoverableDataAccessException
          || current instanceof DataAccessResourceFailureException
          || current instanceof SQLTransientException
          || current instanceof SQLRecoverableException) {
        return true;
      }
      if (current instanceof SQLException sqlException) {
        String sqlState = sqlException.getSQLState();
        if (sqlState != null
            && (sqlState.startsWith("08") || sqlState.startsWith("40"))) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }

  private static String safeMessage(Throwable error) {
    String value = error.getMessage();
    if (value == null || value.isBlank()) return error.getClass().getSimpleName();
    return value.length() <= 512 ? value : value.substring(0, 512);
  }
}
