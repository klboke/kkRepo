package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupHistoryPruneResult;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import java.time.Instant;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded, database-coordinated retention for terminal cleanup audit runs. */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CleanupRunHistoryRetentionWorker {
  private static final String RETENTION_CURSOR = "cleanup_run_history_retention";
  private static final Logger log =
      LoggerFactory.getLogger(CleanupRunHistoryRetentionWorker.class);

  private final CleanupPolicyDao cleanupDao;
  private final CleanupRuntimeProperties properties;
  private final CleanupMetrics metrics;
  private final MaintenanceCursorDao cursors;
  private final TransactionTemplate transactions;

  @Autowired
  public CleanupRunHistoryRetentionWorker(
      CleanupPolicyDao cleanupDao,
      CleanupRuntimeProperties properties,
      CleanupMetrics metrics,
      MaintenanceCursorDao cursors,
      PlatformTransactionManager transactionManager) {
    this(
        cleanupDao,
        properties,
        metrics,
        cursors,
        new TransactionTemplate(transactionManager));
  }

  CleanupRunHistoryRetentionWorker(
      CleanupPolicyDao cleanupDao,
      CleanupRuntimeProperties properties,
      CleanupMetrics metrics) {
    this(cleanupDao, properties, metrics, null, (TransactionTemplate) null);
  }

  CleanupRunHistoryRetentionWorker(
      CleanupPolicyDao cleanupDao,
      CleanupRuntimeProperties properties,
      CleanupMetrics metrics,
      MaintenanceCursorDao cursors,
      TransactionTemplate transactions) {
    this.cleanupDao = cleanupDao;
    this.properties = properties;
    this.metrics = metrics;
    this.cursors = cursors;
    this.transactions = transactions;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.cleanup.history.cleanup-delay:1h}",
      initialDelayString = "${kkrepo.cleanup.history.initial-delay:5m}")
  public void runOnce() {
    CleanupRuntimeProperties.History history = properties.getHistory();
    if (!history.isEnabled()) return;
    try {
      if (!reserve(history)) return;
      Instant cutoff = cleanupDao.currentTime().minus(history.getRetention());
      int deletedRuns = 0;
      int deletedItems = 0;
      for (int batch = 0; batch < history.getMaxBatchesPerRun(); batch++) {
        CleanupHistoryPruneResult result = cleanupDao.pruneTerminalRunHistory(
            cutoff,
            history.getBatchSize(),
            history.getMinimumRunsPerPolicy(),
            history.getItemBatchSize());
        deletedRuns += result.deletedRuns();
        deletedItems += result.deletedRunItems();
        if (!result.workPerformed()) break;
      }
      metrics.retention(deletedRuns);
      metrics.retentionItems(deletedItems);
    } catch (RuntimeException failure) {
      log.warn("Cleanup run history retention failed; terminal history remains durable", failure);
      metrics.retentionFailure();
    }
  }

  private boolean reserve(CleanupRuntimeProperties.History history) {
    if (cursors == null || transactions == null) return true;
    cursors.ensureCursor(RETENTION_CURSOR);
    Boolean reserved = transactions.execute(ignored -> {
      OptionalLong locked = cursors.tryLockLastSeenId(RETENTION_CURSOR);
      if (locked.isEmpty()) return false;
      long now = cleanupDao.currentTime().toEpochMilli();
      if (locked.getAsLong() + history.getClusterInterval().toMillis() > now) return false;
      return cursors.updateLastSeenId(RETENTION_CURSOR, now) == 1;
    });
    return Boolean.TRUE.equals(reserved);
  }
}
