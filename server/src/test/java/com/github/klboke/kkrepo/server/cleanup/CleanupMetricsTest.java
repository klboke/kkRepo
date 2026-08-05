package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
    metrics.fenceRejected();
    metrics.usage("COALESCED");
    metrics.retention(7);
    metrics.retentionItems(11);
    metrics.retentionFailure();
    metrics.retention(0);
    metrics.retentionItems(-1);
    metrics.shard(" ", null, 0, 0, 0, 0);
    metrics.run(null, null, " ", Duration.ofSeconds(-1));

    assertEquals(1, registry.find("kkrepo_cleanup_run_duration_seconds")
        .tags("outcome", "succeeded", "mode", "execute", "trigger", "scheduled")
        .timer().count());
    assertEquals(10, registry.find("kkrepo_cleanup_subjects_total")
        .tags("kind", "scanned", "mode", "execute").counter().count());
    assertEquals(1, registry.find("kkrepo_cleanup_lease_takeovers_total").counter().count());
    assertEquals(1, registry.find("kkrepo_cleanup_cursor_conflicts_total").counter().count());
    assertEquals(1, registry.find("kkrepo_cleanup_fence_rejections_total").counter().count());
    assertEquals(1, registry.find("kkrepo_cleanup_usage_updates_total")
        .tags("outcome", "COALESCED").counter().count());
    assertEquals(7, registry.find("kkrepo_cleanup_history_deleted_runs_total").counter().count());
    assertEquals(11,
        registry.find("kkrepo_cleanup_history_deleted_items_total").counter().count());
    assertEquals(1,
        registry.find("kkrepo_cleanup_history_retention_failures_total").counter().count());
    assertEquals(1, registry.find("kkrepo_cleanup_repository_shards_total")
        .tags("outcome", "unknown", "mode", "unknown").counter().count());
  }

  @Test
  void disabledAndFailedRefreshesLeaveSafeGaugeValues() {
    CleanupPolicyDao disabledDao = mock(CleanupPolicyDao.class);
    CleanupRuntimeProperties disabledProperties = new CleanupRuntimeProperties();
    disabledProperties.setEnabled(false);
    SimpleMeterRegistry disabledRegistry = new SimpleMeterRegistry();
    CleanupMetrics disabled = new CleanupMetrics(
        disabledRegistry, disabledDao, disabledProperties);

    disabled.trackedRepositories(-5);
    disabled.refresh();

    assertEquals(0, gauge(disabledRegistry, "kkrepo_cleanup_usage_tracked_repositories"));
    assertEquals(0, gauge(disabledRegistry, "kkrepo_cleanup_pending_shards"));
    verifyNoInteractions(disabledDao);

    CleanupPolicyDao failingDao = mock(CleanupPolicyDao.class);
    when(failingDao.currentTime()).thenThrow(new IllegalStateException("database unavailable"));
    CleanupMetrics failing = new CleanupMetrics(
        new SimpleMeterRegistry(), failingDao, new CleanupRuntimeProperties());

    assertDoesNotThrow(failing::refresh);
  }

  private static double gauge(SimpleMeterRegistry registry, String name) {
    return registry.find(name).gauge().value();
  }
}
