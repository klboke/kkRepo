package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.UsageTrackingRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Rebuildable product-policy projection used to keep unconfigured downloads off the write path. */
@Component
public class CleanupUsageTrackingService implements ApplicationRunner {
  private static final String RECONCILIATION_CURSOR = "cleanup_usage_projection_reconcile";
  private static final Logger log = LoggerFactory.getLogger(CleanupUsageTrackingService.class);

  private final CleanupPolicyDao cleanupDao;
  private final CleanupMetrics metrics;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final MaintenanceCursorDao cursors;
  private final long reconciliationIntervalMillis;
  private final AtomicReference<Map<Long, Instant>> tracked =
      new AtomicReference<>(Map.of());
  private final AtomicBoolean reconciliationPending = new AtomicBoolean();
  private final AtomicLong loadedRevision = new AtomicLong(Long.MIN_VALUE);

  @Autowired
  public CleanupUsageTrackingService(
      CleanupPolicyDao cleanupDao,
      CleanupMetrics metrics,
      PlatformTransactionManager transactionManager,
      MaintenanceCursorDao cursors,
      @Value("${kkrepo.cleanup.usage.projection-delay-ms:60000}")
      long reconciliationIntervalMillis) {
    this(
        cleanupDao,
        metrics,
        Clock.systemUTC(),
        new TransactionTemplate(transactionManager),
        cursors,
        reconciliationIntervalMillis);
  }

  CleanupUsageTrackingService(
      CleanupPolicyDao cleanupDao,
      CleanupMetrics metrics,
      Clock clock,
      TransactionTemplate transactions) {
    this(cleanupDao, metrics, clock, transactions, null, 0);
  }

  CleanupUsageTrackingService(
      CleanupPolicyDao cleanupDao,
      CleanupMetrics metrics,
      Clock clock,
      TransactionTemplate transactions,
      MaintenanceCursorDao cursors,
      long reconciliationIntervalMillis) {
    this.cleanupDao = cleanupDao;
    this.metrics = metrics;
    this.clock = clock;
    this.transactions = transactions;
    this.cursors = cursors;
    this.reconciliationIntervalMillis = Math.max(1_000, reconciliationIntervalMillis);
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
    if (cursors == null) {
      reconcileProjectionNow();
      return;
    }
    cursors.ensureCursor(RECONCILIATION_CURSOR);
    transactions.executeWithoutResult(ignored -> {
      OptionalLong locked = cursors.tryLockLastSeenId(RECONCILIATION_CURSOR);
      if (locked.isEmpty()) return;
      Instant now = databaseNow();
      long eligibleAt = locked.getAsLong() + reconciliationIntervalMillis;
      if (eligibleAt > now.toEpochMilli()) return;
      reconcile(now);
      if (cursors.updateLastSeenId(RECONCILIATION_CURSOR, now.toEpochMilli()) != 1) {
        throw new IllegalStateException("cleanup usage projection cursor disappeared");
      }
    });
  }

  private void reconcileProjectionNow() {
    transactions.executeWithoutResult(ignored -> reconcile(databaseNow()));
  }

  void reconcile(Instant now) {
    cleanupDao.lockUsageTrackingProjection();
    Map<Long, Instant> current = new HashMap<>();
    for (UsageTrackingRepository row : cleanupDao.listUsageTrackingRepositories()) {
      current.put(row.repositoryId(), row.trackingStartedAt());
    }
    Map<Long, Instant> required = new HashMap<>();
    long afterId = 0;
    while (true) {
      List<CleanupPolicy> page = cleanupDao.listPolicies(afterId, 500);
      if (page.isEmpty()) break;
      List<Long> usagePolicyIds = page.stream()
          .filter(policy -> policy.criteria().containsKey("lastDownloadedOlderThanDays"))
          .map(CleanupPolicy::id)
          .toList();
      cleanupDao.listTargets(usagePolicyIds).values().stream()
          .flatMap(List::stream)
          .forEach(target -> required.put(
              target.id(), current.getOrDefault(target.id(), now)));
      afterId = page.getLast().id();
      if (page.size() < 500) break;
    }
    cleanupDao.synchronizeUsageTracking(required, now);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.cleanup.usage.snapshot-delay-ms:1000}",
      initialDelayString = "${kkrepo.cleanup.usage.snapshot-initial-delay-ms:1000}")
  public void refreshLocalSnapshot() {
    long revision = cleanupDao.usageTrackingRevision();
    if (loadedRevision.get() == revision) return;
    Map<Long, Instant> snapshot = new HashMap<>();
    for (UsageTrackingRepository row : cleanupDao.listUsageTrackingRepositories()) {
      snapshot.put(row.repositoryId(), row.trackingStartedAt());
    }
    tracked.set(Map.copyOf(snapshot));
    loadedRevision.set(revision);
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
      reconcileProjectionNow();
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
