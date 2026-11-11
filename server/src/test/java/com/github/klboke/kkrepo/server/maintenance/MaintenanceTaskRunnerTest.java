package com.github.klboke.kkrepo.server.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.plugin.api.MaintenanceRequest;
import com.github.klboke.kkrepo.plugin.api.MaintenanceResult;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaintenanceTaskRunnerTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void runsDryRunAndAuditsSuccess() {
    FakeAudit audit = new FakeAudit();
    MaintenanceTaskRunner runner = runner(
        task("task-1", new MaintenanceResult(2, 0, List.of("planned"))), audit, true, 10);

    MaintenanceResult result = runner.run("task-1");

    assertEquals(2, result.proposed());
    assertEquals(0, result.deleted());
    assertEquals(1, audit.events.size());
    MaintenanceAuditEvent event = audit.events.getFirst();
    assertEquals("task-1", event.taskId());
    assertTrue(event.dryRun());
    assertEquals("SUCCESS", event.outcome());
    assertEquals(2, event.proposed());
  }

  @Test
  void rejectsQuotaOverflowAndAuditsIt() {
    FakeAudit audit = new FakeAudit();
    MaintenanceTaskRunner runner = runner(
        task("task-1", new MaintenanceResult(11, 0, List.of())), audit, true, 10);

    assertThrows(MaintenanceQuotaExceededException.class, () -> runner.run("task-1"));

    assertEquals(1, audit.events.size());
    assertEquals("QUOTA_EXCEEDED", audit.events.getFirst().outcome());
  }

  @Test
  void rejectsDeletionDuringDryRun() {
    FakeAudit audit = new FakeAudit();
    MaintenanceTaskRunner runner = runner(
        task("task-1", new MaintenanceResult(1, 1, List.of())), audit, true, 10);

    assertThrows(IllegalStateException.class, () -> runner.run("task-1"));

    assertEquals(1, audit.events.size());
    assertEquals("FAILED", audit.events.getFirst().outcome());
  }

  @Test
  void rejectsUnknownTask() {
    FakeAudit audit = new FakeAudit();
    MaintenanceTaskRunner runner = runner(
        task("task-1", new MaintenanceResult(0, 0, List.of())), audit, true, 10);

    assertThrows(IllegalArgumentException.class, () -> runner.run("missing"));
  }

  private static MaintenanceTaskRunner runner(
      MaintenanceTask task, MaintenanceAudit audit, boolean dryRun, int maxDeletions) {
    return new MaintenanceTaskRunner(
        new MaintenanceTaskRegistry(List.of(task)), audit, dryRun, maxDeletions, CLOCK);
  }

  private static MaintenanceTask task(String id, MaintenanceResult result) {
    return new MaintenanceTask() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public MaintenanceResult run(MaintenanceRequest request) {
        return result;
      }
    };
  }

  private static final class FakeAudit implements MaintenanceAudit {
    private final List<MaintenanceAuditEvent> events = new ArrayList<>();

    @Override
    public void record(MaintenanceAuditEvent event) {
      events.add(event);
    }
  }
}
