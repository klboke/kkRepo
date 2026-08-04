package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupUsageWriteOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable usage watermark with a post-commit-only, disposable node-local write coalescer. */
@Service
public class CleanupUsageTracker {
  private static final Logger log = LoggerFactory.getLogger(CleanupUsageTracker.class);
  private static final int LOCK_COUNT = 256;

  private final CleanupPolicyDao cleanupDao;
  private final CleanupUsageTrackingService tracking;
  private final CleanupRuntimeProperties properties;
  private final CleanupMetrics metrics;
  private final TransactionTemplate transactions;
  private final LongSupplier monotonicNanos;
  private final Map<Long, Long> persistedAt = new ConcurrentHashMap<>();
  private final Object[] locks = new Object[LOCK_COUNT];

  @Autowired
  public CleanupUsageTracker(
      CleanupPolicyDao cleanupDao,
      CleanupUsageTrackingService tracking,
      CleanupRuntimeProperties properties,
      CleanupMetrics metrics,
      TransactionTemplate transactions) {
    this(
        cleanupDao,
        tracking,
        properties,
        metrics,
        transactions,
        Clock.systemUTC(),
        System::nanoTime);
  }

  CleanupUsageTracker(
      CleanupPolicyDao cleanupDao,
      CleanupUsageTrackingService tracking,
      CleanupRuntimeProperties properties,
      CleanupMetrics metrics,
      TransactionTemplate transactions,
      Clock clock) {
    this(cleanupDao, tracking, properties, metrics, transactions, clock, System::nanoTime);
  }

  CleanupUsageTracker(
      CleanupPolicyDao cleanupDao,
      CleanupUsageTrackingService tracking,
      CleanupRuntimeProperties properties,
      CleanupMetrics metrics,
      TransactionTemplate transactions,
      Clock clock,
      LongSupplier monotonicNanos) {
    this.cleanupDao = cleanupDao;
    this.tracking = tracking;
    this.properties = properties;
    this.metrics = metrics;
    this.transactions = transactions;
    this.monotonicNanos = monotonicNanos;
    for (int index = 0; index < locks.length; index++) locks[index] = new Object();
  }

  public void record(long assetId, Long sourceRepositoryId) {
    if (assetId <= 0 || !tracking.isTracked(sourceRepositoryId)) {
      return;
    }
    Duration ttl = properties.getUsage().getCoalescingTtl();
    long observedNanos = monotonicNanos.getAsLong();
    long ttlNanos = durationNanos(ttl);
    Object lock = locks[Long.hashCode(assetId) & (LOCK_COUNT - 1)];
    synchronized (lock) {
      Long last = persistedAt.get(assetId);
      if (last != null && observedNanos - last < ttlNanos) {
        metrics.usage("coalesced");
        return;
      }
      try {
        CleanupUsageWriteOutcome outcome = transactions.execute(status ->
            cleanupDao.recordAssetUsage(assetId, sourceRepositoryId, ttl));
        if (outcome == null || outcome == CleanupUsageWriteOutcome.NOT_TRACKED) {
          metrics.usage("missing");
          return;
        }
        persistedAt.put(assetId, observedNanos);
        trim(observedNanos, ttlNanos);
        metrics.usage(outcome == CleanupUsageWriteOutcome.WRITTEN
            ? "written" : "coalesced");
      } catch (RuntimeException error) {
        metrics.usage(properties.getUsage().isFailClosed() ? "fail_closed" : "failed_open");
        if (properties.getUsage().isFailClosed()) {
          throw new CleanupUsageUnavailableException(
              "cleanup usage watermark is temporarily unavailable", error);
        }
        log.warn("Cleanup usage watermark write failed; fail-open is explicitly configured", error);
      }
    }
  }

  public boolean isTracked(Long sourceRepositoryId) {
    return tracking.isTracked(sourceRepositoryId);
  }

  private void trim(long nowNanos, long ttlNanos) {
    if (persistedAt.size() <= properties.getUsage().getLocalCacheMaximum()) return;
    persistedAt.entrySet().removeIf(entry -> nowNanos - entry.getValue() >= ttlNanos);
    if (persistedAt.size() > properties.getUsage().getLocalCacheMaximum()) {
      persistedAt.clear();
    }
  }

  private static long durationNanos(Duration duration) {
    if (duration == null || duration.isZero() || duration.isNegative()) return 0;
    try {
      return duration.toNanos();
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }
}
