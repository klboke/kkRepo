package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupOperationalSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CleanupMetricsTest {
  @Test
  void refreshesOperationalGaugesFromDurableDatabaseState() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Instant now = Instant.parse("2026-08-02T00:00:00Z");
    when(cleanupDao.currentTime()).thenReturn(now);
    when(cleanupDao.operationalSummary()).thenReturn(new CleanupOperationalSummary(
        3, 2, 1, 1, now.minusSeconds(75)));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new CleanupMetrics(registry, cleanupDao, new CleanupRuntimeProperties()).refresh();

    assertEquals(3, gauge(registry, "kkrepo_cleanup_pending_shards"));
    assertEquals(2, gauge(registry, "kkrepo_cleanup_retry_waiting_shards"));
    assertEquals(1, gauge(registry, "kkrepo_cleanup_running_shards"));
    assertEquals(1, gauge(registry, "kkrepo_cleanup_expired_running_leases"));
    assertEquals(75, gauge(registry, "kkrepo_cleanup_oldest_outstanding_age_seconds"));
  }

  @Test
  void recordsLowCardinalityRuntimeAndRetentionOutcomes() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    CleanupMetrics metrics = new CleanupMetrics(
        registry, mock(CleanupPolicyDao.class), new CleanupRuntimeProperties());

    metrics.run("SUCCEEDED", "EXECUTE", "SCHEDULED", Duration.ofSeconds(2));
    metrics.shard("SUCCEEDED", "EXECUTE", 10, 4, 3, 1);
    metrics.takeover();
    metrics.cursorConflict();
    metrics.retention(7);

    assertEquals(1, registry.find("kkrepo_cleanup_run_duration_seconds")
        .tags("outcome", "succeeded", "mode", "execute", "trigger", "scheduled")
        .timer().count());
    assertEquals(10, registry.find("kkrepo_cleanup_subjects_total")
        .tags("kind", "scanned", "mode", "execute").counter().count());
    assertEquals(1, registry.find("kkrepo_cleanup_lease_takeovers_total").counter().count());
    assertEquals(1, registry.find("kkrepo_cleanup_cursor_conflicts_total").counter().count());
    assertEquals(7, registry.find("kkrepo_cleanup_history_deleted_runs_total").counter().count());
  }

  private static double gauge(SimpleMeterRegistry registry, String name) {
    return registry.find(name).gauge().value();
  }
}
