package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.BackfillJob;
import com.github.klboke.kkrepo.security.scan.ScanEnums.BackfillStatus;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    try {
      var page = scans.markRepositoryAssetsForBackfill(
          job.repositoryId(),
          job.cursorAssetId(),
          properties.getWorker().getBackfillBatchSize());
      BackfillStatus status =
          page.complete() ? BackfillStatus.SUCCEEDED : BackfillStatus.RUNNING;
      if (!scans.updateBackfillProgress(
          job.id(),
          job.leaseToken(),
          page.nextAssetId(),
          job.scannedAssets() + page.scannedAssets(),
          job.markedAssets() + page.markedAssets(),
          status,
          null,
          Instant.now())) {
        log.debug("Security scan backfill lease was lost: {}", job.id());
      }
    } catch (RuntimeException e) {
      scans.updateBackfillProgress(
          job.id(),
          job.leaseToken(),
          job.cursorAssetId(),
          job.scannedAssets(),
          job.markedAssets(),
          BackfillStatus.FAILED,
          safeMessage(e),
          Instant.now());
      log.warn("Security scan backfill failed: {}", job.id(), e);
    }
  }

  private static String safeMessage(Throwable error) {
    String value = error.getMessage();
    if (value == null || value.isBlank()) return error.getClass().getSimpleName();
    return value.length() <= 512 ? value : value.substring(0, 512);
  }
}
