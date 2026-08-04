package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.UsageTrackingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Rebuildable product-policy projection used to keep unconfigured downloads off the write path. */
@Component
public class CleanupUsageTrackingService implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(CleanupUsageTrackingService.class);

  private final CleanupPolicyDao cleanupDao;
  private final CleanupMetrics metrics;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final AtomicReference<Map<Long, Instant>> tracked =
      new AtomicReference<>(Map.of());
  private final AtomicBoolean reconciliationPending = new AtomicBoolean();

  @Autowired
  public CleanupUsageTrackingService(
      CleanupPolicyDao cleanupDao,
      CleanupMetrics metrics,
      PlatformTransactionManager transactionManager) {
    this(cleanupDao, metrics, Clock.systemUTC(), new TransactionTemplate(transactionManager));
  }

  CleanupUsageTrackingService(
      CleanupPolicyDao cleanupDao,
      CleanupMetrics metrics,
      Clock clock,
      TransactionTemplate transactions) {
    this.cleanupDao = cleanupDao;
    this.metrics = metrics;
    this.clock = clock;
    this.transactions = transactions;
  }

  @Override
  public void run(ApplicationArguments args) {
    reconcilePolicyProjection();
    refreshLocalSnapshot();
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.cleanup.usage.projection-delay-ms:60000}",
      initialDelayString = "${kkrepo.cleanup.usage.projection-initial-delay-ms:1000}")
  public void reconcilePolicyProjection() {
    transactions.executeWithoutResult(ignored -> reconcile(databaseNow()));
  }

  void reconcile(Instant now) {
    Map<Long, Instant> current = new HashMap<>();
    for (UsageTrackingRepository row : cleanupDao.listUsageTrackingRepositories()) {
      current.put(row.repositoryId(), row.trackingStartedAt());
    }
    Map<Long, Instant> required = new HashMap<>();
    for (CleanupPolicy policy : cleanupDao.listPolicies()) {
      if (!policy.criteria().containsKey("lastDownloadedOlderThanDays")) continue;
      cleanupDao.listTargets(policy.id()).forEach(target -> required.put(
          target.id(), current.getOrDefault(target.id(), now)));
    }
    cleanupDao.synchronizeUsageTracking(required, now);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.cleanup.usage.snapshot-delay-ms:1000}",
      initialDelayString = "${kkrepo.cleanup.usage.snapshot-initial-delay-ms:1000}")
  public void refreshLocalSnapshot() {
    Map<Long, Instant> snapshot = new HashMap<>();
    for (UsageTrackingRepository row : cleanupDao.listUsageTrackingRepositories()) {
      snapshot.put(row.repositoryId(), row.trackingStartedAt());
    }
    tracked.set(Map.copyOf(snapshot));
    metrics.trackedRepositories(snapshot.size());
  }

  /**
   * Applies the low-latency hint only after Spring has unbound the committing transaction. The
   * durable policy table and periodic full reconciliation remain authoritative.
   */
  @Scheduled(
      fixedDelayString = "${kkrepo.cleanup.usage.projection-hint-delay-ms:1000}",
      initialDelayString = "${kkrepo.cleanup.usage.projection-hint-initial-delay-ms:1000}")
  public void reconcilePendingSafely() {
    if (!reconciliationPending.compareAndSet(true, false)) return;
    try {
      reconcilePolicyProjection();
      refreshLocalSnapshot();
    } catch (RuntimeException failure) {
      reconciliationPending.set(true);
      log.warn("Cleanup usage tracking projection reconciliation failed", failure);
    }
  }

  public boolean isTracked(Long entryRepositoryId) {
    return entryRepositoryId != null && tracked.get().containsKey(entryRepositoryId);
  }

  public boolean hasTrackedRepositories() {
    return !tracked.get().isEmpty();
  }

  public Instant trackingStartedAt(long repositoryId) {
    return tracked.get().get(repositoryId);
  }

  private Instant databaseNow() {
    Instant value = cleanupDao.currentTime();
    return value == null ? clock.instant() : value;
  }

  public void reconcileAfterCommit() {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      reconciliationPending.set(true);
      reconcilePendingSafely();
      return;
    }
    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
        new org.springframework.transaction.support.TransactionSynchronization() {
          @Override
          public void afterCommit() {
            reconciliationPending.set(true);
          }
        });
  }
}
