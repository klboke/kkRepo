package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.ClaimedRunRepository;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Polls durable shards; every replica may run this worker concurrently. */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CleanupRunWorker {
  private static final Logger log = LoggerFactory.getLogger(CleanupRunWorker.class);

  private final CleanupPolicyDao cleanupDao;
  private final CleanupRunService runs;
  private final CleanupRuntimeProperties properties;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final String workerId;
  private final ExecutorService processingExecutor;
  private final ScheduledExecutorService heartbeatExecutor;
  private final Semaphore processingSlots;
  private final AtomicBoolean claiming = new AtomicBoolean();
  private volatile long nextPollNanos;
  private volatile long idleDelayNanos;

  @Autowired
  public CleanupRunWorker(
      CleanupPolicyDao cleanupDao,
      CleanupRunService runs,
      CleanupRuntimeProperties properties,
      TransactionTemplate transactions) {
    this(cleanupDao, runs, properties, transactions, Clock.systemUTC(), defaultWorkerId());
  }

  CleanupRunWorker(
      CleanupPolicyDao cleanupDao,
      CleanupRunService runs,
      CleanupRuntimeProperties properties,
      TransactionTemplate transactions,
      Clock clock,
      String workerId) {
    this.cleanupDao = cleanupDao;
    this.runs = runs;
    this.properties = properties;
    this.transactions = transactions;
    this.clock = clock;
    this.workerId = workerId;
    int concurrency = properties.getWorker().getConcurrency();
    AtomicInteger processThread = new AtomicInteger();
    this.processingExecutor = Executors.newFixedThreadPool(concurrency, runnable ->
        daemonThread(runnable, "kkrepo-cleanup-worker-" + processThread.incrementAndGet()));
    AtomicInteger heartbeatThread = new AtomicInteger();
    this.heartbeatExecutor = Executors.newScheduledThreadPool(
        Math.max(1, Math.min(4, concurrency)),
        runnable -> daemonThread(
            runnable, "kkrepo-cleanup-heartbeat-" + heartbeatThread.incrementAndGet()));
    this.processingSlots = new Semaphore(concurrency);
    this.idleDelayNanos = durationNanos(properties.getWorker().getIdleBaseDelay());
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.cleanup.worker.poll-delay-ms:500}",
      initialDelayString = "${kkrepo.cleanup.worker.initial-delay-ms:1000}")
  public void runOnce() {
    long monotonicNow = System.nanoTime();
    if (monotonicNow - nextPollNanos < 0 || !claiming.compareAndSet(false, true)) {
      return;
    }
    try {
      claimAvailableWork();
    } finally {
      claiming.set(false);
    }
  }

  private void claimAvailableWork() {
    int reservedSlots = reserveProcessingSlots();
    if (reservedSlots == 0) return;
    List<ClaimedRunRepository> claims;
    try {
      Instant now = databaseNow();
      claims = transactions.execute(status -> cleanupDao.claimRunRepositories(
          workerId,
          now,
          now.plus(properties.getWorker().getLeaseDuration()),
          reservedSlots));
    } catch (RuntimeException claimFailure) {
      processingSlots.release(reservedSlots);
      scheduleIdlePoll();
      log.warn("Cleanup shard claim failed; durable work remains available for retry", claimFailure);
      return;
    }
    int claimed = claims == null ? 0 : claims.size();
    if (claimed < reservedSlots) processingSlots.release(reservedSlots - claimed);
    if (claimed == 0) {
      scheduleIdlePoll();
      return;
    }
    resetIdleBackoff();
    for (ClaimedRunRepository claim : claims) {
      try {
        processingExecutor.execute(() -> processClaim(claim));
      } catch (RejectedExecutionException shutdown) {
        processingSlots.release();
        log.warn("Cleanup worker is shutting down after claiming shard: {}", claim.id());
      }
    }
  }

  private int reserveProcessingSlots() {
    int limit = properties.getWorker().getBatchSize();
    int reserved = 0;
    while (reserved < limit && processingSlots.tryAcquire()) reserved++;
    return reserved;
  }

  private void processClaim(ClaimedRunRepository claim) {
    LeaseHeartbeat heartbeat = null;
    try {
      heartbeat = startHeartbeat(claim);
      runs.process(claim);
    } catch (RuntimeException failure) {
      log.warn(
          "Cleanup shard processing escaped its bounded retry path: {}", claim.id(), failure);
    } finally {
      if (heartbeat != null) heartbeat.close();
      processingSlots.release();
    }
  }

  private void scheduleIdlePoll() {
    long base = durationNanos(properties.getWorker().getIdleBaseDelay());
    long maximum = durationNanos(properties.getWorker().getIdleMaxDelay());
    long delay = Math.max(base, idleDelayNanos);
    nextPollNanos = addSaturated(System.nanoTime(), delay);
    idleDelayNanos = Math.min(maximum, multiplySaturated(delay, 2));
  }

  private void resetIdleBackoff() {
    nextPollNanos = 0;
    idleDelayNanos = durationNanos(properties.getWorker().getIdleBaseDelay());
  }

  private LeaseHeartbeat startHeartbeat(ClaimedRunRepository claim) {
    long leaseMillis = Math.max(30, properties.getWorker().getLeaseDuration().toMillis());
    long configuredMillis = Math.max(
        10, properties.getWorker().getHeartbeatInterval().toMillis());
    long intervalMillis = Math.max(10, Math.min(configuredMillis, leaseMillis / 3));
    AtomicBoolean owned = new AtomicBoolean(true);
    ScheduledFuture<?> future = heartbeatExecutor.scheduleWithFixedDelay(() -> {
      if (!owned.get()) return;
      try {
        Instant now = databaseNow();
        boolean renewed = cleanupDao.heartbeatRunRepository(
            claim.id(),
            claim.leaseToken(),
            claim.fencingToken(),
            now.plus(properties.getWorker().getLeaseDuration()),
            now);
        if (!renewed) {
          owned.set(false);
          log.warn("Cleanup shard lease heartbeat lost its fence: {}", claim.id());
        }
      } catch (RuntimeException failure) {
        log.warn(
            "Cleanup shard lease heartbeat failed; the durable lease remains authoritative: {}",
            claim.id(),
            failure);
      }
    }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    return () -> {
      owned.set(false);
      future.cancel(false);
    };
  }

  @PreDestroy
  void shutdownHeartbeatExecutor() {
    processingExecutor.shutdown();
    try {
      if (!processingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
        processingExecutor.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      processingExecutor.shutdownNow();
    }
    heartbeatExecutor.shutdownNow();
  }

  private Instant databaseNow() {
    Instant value = cleanupDao.currentTime();
    return value == null ? clock.instant() : value;
  }

  private static String defaultWorkerId() {
    return ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();
  }

  private static Thread daemonThread(Runnable runnable, String name) {
    Thread thread = new Thread(runnable, name);
    thread.setDaemon(true);
    return thread;
  }

  private static long durationNanos(Duration duration) {
    try {
      return Math.max(1, duration.toNanos());
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static long addSaturated(long left, long right) {
    long result = left + right;
    return ((left ^ result) & (right ^ result)) < 0 ? Long.MAX_VALUE : result;
  }

  private static long multiplySaturated(long value, int multiplier) {
    return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
  }

  @FunctionalInterface
  private interface LeaseHeartbeat extends AutoCloseable {
    @Override
    void close();
  }
}
