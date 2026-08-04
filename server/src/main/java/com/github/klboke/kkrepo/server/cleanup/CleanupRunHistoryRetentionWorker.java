package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, database-coordinated retention for terminal cleanup audit runs. */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CleanupRunHistoryRetentionWorker {
  private static final Logger log =
      LoggerFactory.getLogger(CleanupRunHistoryRetentionWorker.class);

  private final CleanupPolicyDao cleanupDao;
  private final CleanupRuntimeProperties properties;
  private final CleanupMetrics metrics;

  public CleanupRunHistoryRetentionWorker(
      CleanupPolicyDao cleanupDao,
      CleanupRuntimeProperties properties,
      CleanupMetrics metrics) {
    this.cleanupDao = cleanupDao;
    this.properties = properties;
    this.metrics = metrics;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.cleanup.history.cleanup-delay:1h}",
      initialDelayString = "${kkrepo.cleanup.history.initial-delay:5m}")
  public void runOnce() {
    CleanupRuntimeProperties.History history = properties.getHistory();
    if (!history.isEnabled()) return;
    try {
      Instant cutoff = cleanupDao.currentTime().minus(history.getRetention());
      int total = 0;
      for (int batch = 0; batch < history.getMaxBatchesPerRun(); batch++) {
        int deleted = cleanupDao.deleteTerminalRunsBefore(
            cutoff, history.getBatchSize(), history.getMinimumRunsPerPolicy());
        total += deleted;
        if (deleted < history.getBatchSize()) break;
      }
      metrics.retention(total);
    } catch (RuntimeException failure) {
      log.warn("Cleanup run history retention failed; terminal history remains durable", failure);
      metrics.retentionFailure();
    }
  }
}
