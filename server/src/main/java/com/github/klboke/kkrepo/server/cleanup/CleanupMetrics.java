package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupOperationalSummary;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Low-cardinality cleanup runtime metrics. */
@Component
public class CleanupMetrics {
  private static final Logger log = LoggerFactory.getLogger(CleanupMetrics.class);

  private final MeterRegistry registry;
  private final CleanupPolicyDao cleanupDao;
  private final CleanupRuntimeProperties properties;
  private final AtomicLong trackedRepositories = new AtomicLong();
  private final AtomicLong pendingShards = new AtomicLong();
  private final AtomicLong retryWaitingShards = new AtomicLong();
  private final AtomicLong runningShards = new AtomicLong();
  private final AtomicLong expiredRunningLeases = new AtomicLong();
  private final AtomicLong oldestOutstandingAgeSeconds = new AtomicLong();

  public CleanupMetrics(
      MeterRegistry registry,
      CleanupPolicyDao cleanupDao,
      CleanupRuntimeProperties properties) {
    this.registry = registry;
    this.cleanupDao = cleanupDao;
    this.properties = properties;
    gauge(
        "kkrepo_cleanup_usage_tracked_repositories",
        "Repositories with durable cleanup download tracking enabled",
        trackedRepositories);
    gauge("kkrepo_cleanup_pending_shards", "Cleanup repository shards pending", pendingShards);
    gauge(
        "kkrepo_cleanup_retry_waiting_shards",
        "Cleanup repository shards waiting for retry",
        retryWaitingShards);
    gauge("kkrepo_cleanup_running_shards", "Cleanup repository shards running", runningShards);
    gauge(
        "kkrepo_cleanup_expired_running_leases",
        "Running cleanup shards whose durable lease has expired",
        expiredRunningLeases);
    gauge(
        "kkrepo_cleanup_oldest_outstanding_age_seconds",
        "Age of the oldest non-terminal cleanup repository shard",
        oldestOutstandingAgeSeconds);
  }

  void trackedRepositories(long count) {
    trackedRepositories.set(Math.max(0, count));
  }

  void usage(String outcome) {
    Counter.builder("kkrepo_cleanup_usage_updates_total")
        .tag("outcome", outcome)
        .register(registry)
        .increment();
  }

  void shard(
      String outcome,
      String mode,
      long scanned,
      long matched,
      long deleted,
      long failed) {
    Counter.builder("kkrepo_cleanup_repository_shards_total")
        .description("Terminal cleanup repository shard outcomes")
        .tags("outcome", tag(outcome), "mode", tag(mode))
        .register(registry)
        .increment();
    subjects("scanned", mode, scanned);
    subjects("matched", mode, matched);
    subjects("deleted", mode, deleted);
    subjects("failed", mode, failed);
  }

  void fenceRejected() {
    Counter.builder("kkrepo_cleanup_fence_rejections_total")
        .register(registry)
        .increment();
  }

  void takeover() {
    Counter.builder("kkrepo_cleanup_lease_takeovers_total")
        .description("Expired cleanup shard leases taken over by another replica")
        .register(registry)
        .increment();
  }

  void cursorConflict() {
    Counter.builder("kkrepo_cleanup_cursor_conflicts_total")
        .description("Completed shards whose stale cursor revision was not advanced")
        .register(registry)
        .increment();
  }

  void run(String outcome, String mode, String trigger, Duration duration) {
    Duration safeDuration = duration == null || duration.isNegative() ? Duration.ZERO : duration;
    Timer.builder("kkrepo_cleanup_run_duration_seconds")
        .description("Cleanup parent run duration")
        .tags(
            "outcome", tag(outcome),
            "mode", tag(mode),
            "trigger", tag(trigger))
        .register(registry)
        .record(safeDuration);
  }

  void retention(int deletedRuns) {
    if (deletedRuns <= 0) return;
    Counter.builder("kkrepo_cleanup_history_deleted_runs_total")
        .description("Terminal cleanup runs deleted by history retention")
        .register(registry)
        .increment(deletedRuns);
  }

  void retentionFailure() {
    Counter.builder("kkrepo_cleanup_history_retention_failures_total")
        .description("Cleanup history retention failures")
        .register(registry)
        .increment();
  }

  @Scheduled(fixedDelayString = "${kkrepo.cleanup.metrics-refresh:15s}")
  public void refresh() {
    if (!properties.isEnabled()) {
      updateOperational(new CleanupOperationalSummary(0, 0, 0, 0, null), null);
      return;
    }
    try {
      Instant now = cleanupDao.currentTime();
      updateOperational(cleanupDao.operationalSummary(), now);
    } catch (RuntimeException failure) {
      log.warn("Cleanup operational metric refresh failed", failure);
    }
  }

  private void updateOperational(CleanupOperationalSummary summary, Instant now) {
    pendingShards.set(Math.max(0, summary.pendingShards()));
    retryWaitingShards.set(Math.max(0, summary.retryWaitingShards()));
    runningShards.set(Math.max(0, summary.runningShards()));
    expiredRunningLeases.set(Math.max(0, summary.expiredRunningLeases()));
    oldestOutstandingAgeSeconds.set(summary.oldestOutstandingCreatedAt() == null || now == null
        ? 0
        : Math.max(0, Duration.between(summary.oldestOutstandingCreatedAt(), now).toSeconds()));
  }

  private void subjects(String kind, String mode, long count) {
    if (count <= 0) return;
    Counter.builder("kkrepo_cleanup_subjects_total")
        .description("Cleanup subjects processed by terminal repository shards")
        .tags("kind", kind, "mode", tag(mode))
        .register(registry)
        .increment(count);
  }

  private void gauge(String name, String description, AtomicLong value) {
    Gauge.builder(name, value, AtomicLong::get)
        .description(description)
        .register(registry);
  }

  private static String tag(String value) {
    return value == null || value.isBlank()
        ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
  }
}
