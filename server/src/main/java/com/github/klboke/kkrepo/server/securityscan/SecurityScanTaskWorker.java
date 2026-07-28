package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanTask;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Adapter;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanExecutor.SupersededSecurityScanTaskException;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanFinalizer.LostSecurityScanLeaseException;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Executes claimed tasks concurrently while DB leases remain the cluster ownership authority. */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.security-scanning", name = "enabled", havingValue = "true")
public class SecurityScanTaskWorker {
  private static final Logger log = LoggerFactory.getLogger(SecurityScanTaskWorker.class);

  private final SecurityScanDao scans;
  private final SecurityScanTaskCoordinator coordinator;
  private final SecurityScanExecutor executor;
  private final SecurityScanFinalizer finalizer;
  private final SecurityScanningProperties properties;
  private final AssetDao assets;
  private final SecurityScanMetrics metrics;
  private final Adapter adapter;
  private final String workerId;
  private final ExecutorService taskExecutor;
  private final ScheduledExecutorService heartbeatExecutor;

  public SecurityScanTaskWorker(
      SecurityScanDao scans,
      SecurityScanTaskCoordinator coordinator,
      SecurityScanExecutor executor,
      SecurityScanFinalizer finalizer,
      SecurityScanningProperties properties,
      AssetDao assets,
      SecurityScanMetrics metrics,
      Adapter adapter) {
    this.scans = scans;
    this.coordinator = coordinator;
    this.executor = executor;
    this.finalizer = finalizer;
    this.properties = properties;
    this.assets = assets;
    this.metrics = metrics;
    this.adapter = adapter;
    this.workerId = hostName() + "-" + UUID.randomUUID();
    this.taskExecutor =
        Executors.newFixedThreadPool(properties.getWorker().getBatchSize(), Thread.ofVirtual().factory());
    this.heartbeatExecutor =
        Executors.newScheduledThreadPool(
            Math.min(2, properties.getWorker().getBatchSize()), Thread.ofVirtual().factory());
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.task-delay-ms:1000}",
      initialDelayString = "${kkrepo.security-scanning.initial-delay-ms:5000}")
  public void runOnce() {
    reapExpiredExhaustedTasks();
    List<ScanTask> tasks;
    try {
      tasks = coordinator.claim(workerId);
    } catch (RuntimeException e) {
      log.warn("Failed claiming security scan tasks", e);
      return;
    }
    List<Future<?>> futures = new java.util.ArrayList<>(tasks.size());
    for (ScanTask task : tasks) {
      futures.add(taskExecutor.submit(() -> executeOne(task)));
    }
    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (java.util.concurrent.ExecutionException e) {
        log.warn("Security scan worker execution escaped its task boundary", e.getCause());
      }
    }
  }

  private void reapExpiredExhaustedTasks() {
    List<ScanTask> exhausted;
    try {
      exhausted = coordinator.claimExpiredExhausted(workerId);
    } catch (RuntimeException e) {
      log.warn("Failed claiming exhausted security scan tasks", e);
      return;
    }
    for (ScanTask task : exhausted) {
      try {
        finalizer.failCurrentTask(
            task,
            "SCAN_ATTEMPTS_EXHAUSTED",
            "The worker lease expired after the final permitted scan attempt",
            false,
            Instant.now());
      } catch (LostSecurityScanLeaseException e) {
        log.debug("Did not reap exhausted scan task after lease loss: {}", task.id());
      } catch (RuntimeException e) {
        log.warn("Failed terminalizing exhausted security scan task: {}", task.id(), e);
      }
    }
  }

  private void executeOne(ScanTask task) {
    Timer.Sample timer = metrics.start();
    String format = task.assetId() == null
        ? "unknown"
        : assets.findAssetById(task.assetId())
            .map(asset -> asset.format().name())
            .orElse("unknown");
    String outcome = "success";
    int heartbeatSeconds = Math.min(
        properties.getWorker().getHeartbeatSeconds(),
        Math.max(5, properties.getWorker().getLeaseSeconds() / 3));
    Thread activeThread = Thread.currentThread();
    AtomicBoolean finished = new AtomicBoolean();
    ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
        () -> heartbeat(task, activeThread, finished),
        heartbeatSeconds,
        heartbeatSeconds,
        TimeUnit.SECONDS);
    try {
      executor.execute(task);
    } catch (SupersededSecurityScanTaskException e) {
      outcome = "superseded";
      if (!scans.cancelClaimedTask(task.id(), task.leaseToken(), Instant.now())) {
        log.debug("Ignored superseded scan task after lease loss: {}", task.id());
      }
    } catch (LostSecurityScanLeaseException e) {
      outcome = "lease_lost";
      log.debug("Security scan task {} was taken over by another worker", task.id());
    } catch (ScannerAdapterException e) {
      if (Thread.currentThread().isInterrupted()) {
        outcome = "cancelled";
      } else {
        outcome = e.retryable() && task.attempts() < task.maxAttempts() ? "retry" : "failed";
        fail(task, e.code(), e.getMessage(), e.retryable());
      }
    } catch (RuntimeException e) {
      if (Thread.currentThread().isInterrupted()) {
        outcome = "cancelled";
      } else {
        outcome = task.attempts() < task.maxAttempts() ? "retry" : "failed";
        log.warn("Unexpected security scan task failure: {}", task.id(), e);
        fail(task, "SCAN_INTERNAL_ERROR", safeMessage(e), true);
      }
    } finally {
      finished.set(true);
      heartbeat.cancel(false);
      metrics.recordTask(
          format, task.stage(), task.requestReason(), outcome, timer);
    }
  }

  private void heartbeat(ScanTask task, Thread activeThread, AtomicBoolean finished) {
    try {
      Instant now = Instant.now();
      boolean renewed = scans.heartbeatTask(
          task.id(),
          task.leaseToken(),
          now.plusSeconds(properties.getWorker().getLeaseSeconds()),
          now);
      if (!renewed) {
        log.debug("Security scan heartbeat lost lease for task {}", task.id());
        if (!finished.get()) {
          activeThread.interrupt();
          cancelAdapterRun(task.id());
        }
      }
    } catch (RuntimeException e) {
      // A periodic executor suppresses all later executions after an exception. Keep retrying
      // until the task completes or its fenced final write observes that another worker owns it.
      log.warn("Security scan heartbeat failed for task {}; retrying", task.id(), e);
    }
  }

  private void cancelAdapterRun(long taskId) {
    try {
      adapter.cancel(Long.toString(taskId));
    } catch (RuntimeException e) {
      // The durable cancelled/taken-over task row already fences publication. A cancellation
      // request may reach another adapter replica, so resource release remains best effort.
      log.debug("Unable to cancel active adapter work for task {}", taskId, e);
    }
  }

  private void fail(ScanTask task, String code, String summary, boolean retryable) {
    try {
      finalizer.failCurrentTask(
          task,
          code,
          summary,
          retryable,
          Instant.now().plusSeconds(backoffSeconds(task.attempts())));
    } catch (LostSecurityScanLeaseException e) {
      log.debug("Did not update failed scan task after lease loss: {}", task.id());
    }
  }

  private long backoffSeconds(int attempts) {
    int exponent = Math.max(0, Math.min(20, attempts - 1));
    long base = Math.min(
        properties.getWorker().getMaxBackoffSeconds(),
        5L << exponent);
    long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, base / 4 + 1));
    return Math.min(properties.getWorker().getMaxBackoffSeconds(), base + jitter);
  }

  @PreDestroy
  void shutdown() {
    taskExecutor.shutdownNow();
    heartbeatExecutor.shutdownNow();
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "kkrepo";
    }
  }

  private static String safeMessage(Throwable error) {
    String value = error.getMessage();
    if (value == null || value.isBlank()) return error.getClass().getSimpleName();
    return value.length() <= 512 ? value : value.substring(0, 512);
  }
}
