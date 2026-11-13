package com.github.klboke.kkrepo.server.maintenance;

import com.github.klboke.kkrepo.plugin.api.MaintenanceRequest;
import com.github.klboke.kkrepo.plugin.api.MaintenanceResult;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Runs one maintenance task with dry-run, quota and audit enforcement. */
@Service
public class MaintenanceTaskRunner {
  private final MaintenanceTaskRegistry registry;
  private final MaintenanceAudit audit;
  private final boolean dryRun;
  private final int maxDeletions;
  private final Clock clock;

  @Autowired
  public MaintenanceTaskRunner(
      MaintenanceTaskRegistry registry,
      MaintenanceAudit audit,
      @Value("${kkrepo.maintenance.dry-run:true}") boolean dryRun,
      @Value("${kkrepo.maintenance.max-deletions:100}") int maxDeletions) {
    this(registry, audit, dryRun, maxDeletions, Clock.systemUTC());
  }

  MaintenanceTaskRunner(
      MaintenanceTaskRegistry registry,
      MaintenanceAudit audit,
      boolean dryRun,
      int maxDeletions,
      Clock clock) {
    this.registry = registry;
    this.audit = audit;
    this.dryRun = dryRun;
    this.maxDeletions = Math.max(0, maxDeletions);
    this.clock = clock;
  }

  public MaintenanceResult run(String taskId) {
    MaintenanceTask task = registry.find(taskId)
        .orElseThrow(() -> new IllegalArgumentException("Maintenance task not found: " + taskId));
    Instant started = clock.instant();
    try {
      MaintenanceResult result = task.run(new MaintenanceRequest(dryRun, maxDeletions));
      if (result == null) {
        throw new IllegalStateException("Maintenance task returned no result: " + taskId);
      }
      if (result.proposed() > maxDeletions || result.deleted() > maxDeletions) {
        throw new MaintenanceQuotaExceededException(
            "Maintenance task " + taskId + " exceeded the deletion quota: proposed="
                + result.proposed() + " deleted=" + result.deleted() + " max=" + maxDeletions);
      }
      if (dryRun && result.deleted() > 0) {
        throw new IllegalStateException(
            "Maintenance task " + taskId + " reported deletions during a dry run");
      }
      audit.record(new MaintenanceAuditEvent(
          taskId,
          dryRun,
          result.proposed(),
          result.deleted(),
          durationMillis(started),
          "SUCCESS",
          null));
      return result;
    } catch (RuntimeException failure) {
      audit.record(new MaintenanceAuditEvent(
          taskId,
          dryRun,
          0,
          0,
          durationMillis(started),
          failure instanceof MaintenanceQuotaExceededException ? "QUOTA_EXCEEDED" : "FAILED",
          failure.getMessage()));
      throw failure;
    }
  }

  private long durationMillis(Instant started) {
    return Math.max(0, Duration.between(started, clock.instant()).toMillis());
  }
}
