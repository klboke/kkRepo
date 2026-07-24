package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanSummary;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Security metrics use only bounded labels; repository, paths, CVEs, tokens, and IDs are absent. */
@Component
public class SecurityScanMetrics {
  private final MeterRegistry registry;
  private final SecurityScanDao scans;
  private final AtomicLong backlog = new AtomicLong();
  private final AtomicLong running = new AtomicLong();
  private final AtomicLong failures = new AtomicLong();
  private final AtomicLong partial = new AtomicLong();
  private final AtomicLong findings = new AtomicLong();
  private final AtomicLong oldestAgeSeconds = new AtomicLong();
  private final AtomicLong scannerReady = new AtomicLong();
  private final AtomicLong databaseAgeSeconds = new AtomicLong(-1);

  public SecurityScanMetrics(MeterRegistry registry, SecurityScanDao scans) {
    this.registry = registry;
    this.scans = scans;
    gauge("kkrepo_security_scan_backlog", "Security scan tasks ready or waiting", backlog);
    gauge("kkrepo_security_scan_running", "Security scan tasks with active leases", running);
    gauge("kkrepo_security_scan_failures", "Terminal security scan failures", failures);
    gauge("kkrepo_security_scan_partial", "Assets with partial security scan results", partial);
    gauge("kkrepo_security_scan_findings", "Critical and high security findings", findings);
    gauge(
        "kkrepo_security_scan_oldest_age_seconds",
        "Age of the oldest pending security scan task",
        oldestAgeSeconds);
    gauge("kkrepo_security_scan_scanner_ready", "Security scanner readiness", scannerReady);
    gauge(
        "kkrepo_security_scan_database_age_seconds",
        "Age of the scanner vulnerability database",
        databaseAgeSeconds);
  }

  public Timer.Sample start() {
    return Timer.start(registry);
  }

  public void recordTask(
      String format,
      ScanStage stage,
      RequestReason reason,
      String outcome,
      Timer.Sample sample) {
    Tags tags = Tags.of(
        "format", tag(format),
        "stage", tag(stage == null ? null : stage.name()),
        "reason", tag(reason == null ? null : reason.name()),
        "outcome", tag(outcome));
    Counter.builder("kkrepo_security_scan_tasks_total")
        .description("Security scan task outcomes")
        .tags(tags)
        .register(registry)
        .increment();
    if (sample != null) {
      sample.stop(Timer.builder("kkrepo_security_scan_task_duration_seconds")
          .description("Security scan task duration")
          .tags(tags)
          .serviceLevelObjectives(
              Duration.ofSeconds(1),
              Duration.ofSeconds(5),
              Duration.ofSeconds(30),
              Duration.ofMinutes(2),
              Duration.ofMinutes(10))
          .register(registry));
    }
  }

  public void recordPolicy(String format, PolicyDecision decision, boolean enforced) {
    Counter.builder("kkrepo_security_policy_decisions_total")
        .description("Artifact security policy decisions")
        .tags(
            "format", tag(format),
            "decision", tag(decision == null ? null : decision.name()),
            "outcome", enforced && decision != null && decision.blocked() ? "block" : "allow",
            "mode", enforced ? "enforce" : "shadow")
        .register(registry)
        .increment();
  }

  public void recordInputBytes(String format, long bytes) {
    if (bytes <= 0) return;
    Counter.builder("kkrepo_security_scan_input_bytes_total")
        .description("Bytes streamed to the security scanner")
        .tags("format", tag(format))
        .register(registry)
        .increment(bytes);
  }

  public void observeScanner(boolean ready, java.time.Instant databaseUpdatedAt) {
    scannerReady.set(ready ? 1 : 0);
    databaseAgeSeconds.set(databaseUpdatedAt == null
        ? -1
        : Math.max(0, java.time.Duration.between(databaseUpdatedAt, java.time.Instant.now()).toSeconds()));
  }

  public void recordStage(String format, String stage, String outcome, Timer.Sample sample) {
    if (sample == null) return;
    sample.stop(Timer.builder("kkrepo_security_scan_" + stage + "_duration_seconds")
        .description("Security scan " + stage + " duration")
        .tags("format", tag(format), "outcome", tag(outcome))
        .register(registry));
  }

  @Scheduled(fixedDelayString = "${kkrepo.security-scanning.metrics-refresh:15s}")
  public void refresh() {
    ScanSummary summary = scans.summary();
    backlog.set(summary.pendingTasks());
    running.set(summary.runningTasks());
    failures.set(summary.failedTasks());
    partial.set(summary.partialAssets());
    findings.set(summary.criticalFindings() + summary.highFindings());
    java.time.Instant now = java.time.Instant.now();
    oldestAgeSeconds.set(scans.oldestPendingTaskCreatedAt()
        .map(createdAt -> Math.max(0, java.time.Duration.between(createdAt, now).toSeconds()))
        .orElse(0L));
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
