package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyService.PolicyCommand;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyService.ScheduleCommand;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyService.SchedulePreview;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CleanupPolicyServiceTest {
  @Test
  void rejectsPatternOnlyPolicyWithoutADeletionCriterion() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

    assertThrows(CleanupValidationException.class, () -> service.create(new PolicyCommand(
        "dangerous pattern only",
        RepositoryFormat.RAW,
        null,
        Map.of("pattern", "releases/**", "patternType", "GLOB"),
        List.of(1L),
        100,
        10,
        null,
        null)));

    verifyNoInteractions(cleanupDao, repositoryDao);
  }

  @Test
  void rejectsRepositoriesFromDifferentFormats() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    when(repositoryDao.findById(1)).thenReturn(Optional.of(repository(
        1, "maven", RepositoryFormat.MAVEN2)));
    when(repositoryDao.findById(2)).thenReturn(Optional.of(repository(
        2, "npm", RepositoryFormat.NPM)));
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

    assertThrows(CleanupValidationException.class, () -> service.create(new PolicyCommand(
        "releases",
        RepositoryFormat.MAVEN2,
        null,
        Map.of("retainCount", 5),
        List.of(1L, 2L),
        100,
        10,
        null,
        null)));

    verifyNoInteractions(cleanupDao);
  }

  @Test
  void rejectsGroupRepositoryTargets() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    when(repositoryDao.findById(1)).thenReturn(Optional.of(repository(
        1, "maven-group", RepositoryFormat.MAVEN2, RepositoryType.GROUP)));
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

    CleanupValidationException error = assertThrows(
        CleanupValidationException.class,
        () -> service.create(new PolicyCommand(
            "group cleanup",
            RepositoryFormat.MAVEN2,
            null,
            Map.of("publishedOlderThanDays", 30),
            List.of(1L),
            100,
            10,
            null,
            null)));

    assertEquals(
        "group repositories cannot be cleanup targets; select hosted or proxy repositories instead",
        error.getMessage());
    verifyNoInteractions(cleanupDao);
  }

  @Test
  void calculatesIndependentCronSchedulesInTheirOwnTimeZone() {
    Instant after = Instant.parse("2026-08-02T01:00:00Z");
    Instant daily = CleanupPolicyService.nextRunAt(
        new ScheduleCommand("0 0 10 * * ?", "Asia/Shanghai", true),
        after);
    Instant weekly = CleanupPolicyService.nextRunAt(
        new ScheduleCommand("0 0 9 ? * MON", "Asia/Shanghai", true),
        after);

    assertEquals(Instant.parse("2026-08-02T02:00:00Z"), daily);
    assertEquals(Instant.parse("2026-08-03T01:00:00Z"), weekly);
  }

  @Test
  void previewsTheNextTwoRunsUsingTheRequestedTimeZone() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        mock(RepositoryDao.class),
        new CleanupPolicyCapabilities(),
        Clock.fixed(Instant.parse("2026-08-02T01:00:00Z"), ZoneOffset.UTC));

    SchedulePreview preview = service.previewSchedule(
        new ScheduleCommand("0 0 10 * * ?", "Asia/Shanghai", false));

    assertEquals("0 0 10 * * ?", preview.cronExpression());
    assertEquals("Asia/Shanghai", preview.timeZone());
    assertEquals(Instant.parse("2026-08-02T01:00:00Z"), preview.evaluatedAt());
    assertEquals(List.of(
        Instant.parse("2026-08-02T02:00:00Z"),
        Instant.parse("2026-08-03T02:00:00Z")), preview.nextRuns());
  }

  @Test
  void acceptsLastDownloadRuleForEveryRepositoryFormat() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    when(repositoryDao.findById(2)).thenReturn(Optional.of(repository(
        2, "npm", RepositoryFormat.NPM)));
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

    when(cleanupDao.createPolicy(org.mockito.ArgumentMatchers.any())).thenReturn(9L);
    when(cleanupDao.findPolicy(9L)).thenReturn(Optional.of(
        new CleanupPolicyDao.CleanupPolicy(
            9L,
            "inactive packages",
            RepositoryFormat.NPM,
            null,
            Map.of("lastDownloadedOlderThanDays", 30),
            1,
            "PAUSED",
            100,
            10,
            Instant.parse("2026-08-01T00:00:00Z"),
            Instant.parse("2026-08-01T00:00:00Z"))));

    service.create(new PolicyCommand(
        "inactive packages",
        RepositoryFormat.NPM,
        null,
        Map.of("lastDownloadedOlderThanDays", 30),
        List.of(2L),
        100,
        10,
        null,
        null));

    verify(cleanupDao).replaceTargets(9L, List.of(2L));
  }

  @Test
  void rejectsInvalidQuartzCronAndTimeZone() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    when(repositoryDao.findById(1)).thenReturn(Optional.of(repository(
        1, "maven", RepositoryFormat.MAVEN2)));
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

    assertThrows(CleanupValidationException.class, () -> service.create(new PolicyCommand(
        "bad cron", RepositoryFormat.MAVEN2, null, Map.of("publishedOlderThanDays", 30),
        List.of(1L), 100, 10, new ScheduleCommand("not-a-cron", "UTC", false), null)));
    assertThrows(CleanupValidationException.class, () -> service.create(new PolicyCommand(
        "bad zone", RepositoryFormat.MAVEN2, null, Map.of("publishedOlderThanDays", 30),
        List.of(1L), 100, 10, new ScheduleCommand("0 0 2 * * ?", "Mars/Olympus", false), null)));

    verifyNoInteractions(cleanupDao);
  }

  @Test
  void rejectsNotesThatCannotFitThePersistenceContract() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    when(repositoryDao.findById(1)).thenReturn(Optional.of(repository(
        1, "maven", RepositoryFormat.MAVEN2)));
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

    assertThrows(CleanupValidationException.class, () -> service.create(new PolicyCommand(
        "oversized notes",
        RepositoryFormat.MAVEN2,
        "x".repeat(2_049),
        Map.of("publishedOlderThanDays", 30),
        List.of(1L),
        100,
        10,
        null,
        null)));

    verifyNoInteractions(cleanupDao);
  }

  @Test
  void expiredCronRemainsReadableWithoutAdvertisingANextRun() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    when(cleanupDao.findPolicy(7L)).thenReturn(Optional.of(
        new CleanupPolicyDao.CleanupPolicy(
            7L,
            "expired schedule",
            RepositoryFormat.MAVEN2,
            null,
            Map.of("publishedOlderThanDays", 30),
            1,
            "ACTIVE",
            100,
            10,
            now,
            now)));
    when(cleanupDao.findSchedule(7L)).thenReturn(Optional.of(
        new CleanupPolicyDao.CleanupSchedule(
            7L,
            "0 0 0 1 1 ? 2025",
            "UTC",
            true,
            null,
            now,
            now)));
    when(cleanupDao.listTargets(7L)).thenReturn(List.of());
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        Clock.fixed(now, ZoneOffset.UTC));

    assertNull(service.get(7L).schedule().nextRunAt());
  }

  @Test
  void createPersistsPausedScheduleAndReconcilesClusterServices() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    CleanupQuartzScheduleManager scheduleManager = mock(CleanupQuartzScheduleManager.class);
    CleanupUsageTrackingService usageTracking = mock(CleanupUsageTrackingService.class);
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    when(repositoryDao.findById(1)).thenReturn(Optional.of(repository(
        1, "maven", RepositoryFormat.MAVEN2)));
    when(cleanupDao.createPolicy(any())).thenReturn(7L);
    CleanupPolicyDao.CleanupPolicy persisted = new CleanupPolicyDao.CleanupPolicy(
        7L, "scheduled", RepositoryFormat.MAVEN2, "notes",
        Map.of("publishedOlderThanDays", 30), 1, "PAUSED", 1_000, 100, now, now);
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(persisted));
    when(cleanupDao.listTargets(7)).thenReturn(List.of());
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        scheduleManager,
        usageTracking,
        Clock.fixed(now, ZoneOffset.UTC));

    service.create(new PolicyCommand(
        " scheduled ",
        RepositoryFormat.MAVEN2,
        " notes ",
        Map.of("publishedOlderThanDays", 30),
        List.of(1L, 1L),
        null,
        null,
        new ScheduleCommand("0 0 2 * * ?", "UTC", true),
        null));

    verify(cleanupDao).replaceTargets(7, List.of(1L));
    ArgumentCaptor<CleanupPolicyDao.CleanupSchedule> schedule =
        ArgumentCaptor.forClass(CleanupPolicyDao.CleanupSchedule.class);
    verify(cleanupDao).upsertSchedule(schedule.capture());
    assertEquals(false, schedule.getValue().enabled());
    assertNull(schedule.getValue().nextRunAt());
    verify(scheduleManager).reconcileAfterCommit(7);
    verify(usageTracking).reconcileAfterCommit();
  }

  @Test
  void updateEnablesUnchangedScheduleAndDeleteUsesRevisionFence() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    CleanupQuartzScheduleManager scheduleManager = mock(CleanupQuartzScheduleManager.class);
    CleanupUsageTrackingService usageTracking = mock(CleanupUsageTrackingService.class);
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    CleanupPolicyDao.CleanupPolicy existing = new CleanupPolicyDao.CleanupPolicy(
        7L, "scheduled", RepositoryFormat.MAVEN2, null,
        Map.of("publishedOlderThanDays", 30), 1, "PAUSED", 100, 10, now, now);
    CleanupPolicyDao.CleanupPolicy updated = new CleanupPolicyDao.CleanupPolicy(
        7L, "scheduled", RepositoryFormat.MAVEN2, null,
        Map.of("publishedOlderThanDays", 30), 2, "ACTIVE", 100, 10, now, now);
    when(cleanupDao.findPolicy(7)).thenReturn(
        Optional.of(existing), Optional.of(updated), Optional.of(updated));
    when(cleanupDao.listTargets(7)).thenReturn(List.of(
        new CleanupPolicyDao.TargetRepository(
            1, "maven", RepositoryFormat.MAVEN2, RepositoryType.HOSTED, true)));
    when(repositoryDao.findById(1)).thenReturn(Optional.of(repository(
        1, "maven", RepositoryFormat.MAVEN2)));
    when(cleanupDao.findSchedule(7)).thenReturn(Optional.of(
        new CleanupPolicyDao.CleanupSchedule(
            7, "0 0 2 * * ?", "UTC", true, null, now, now)));
    when(cleanupDao.updatePolicy(any(), eq(1L))).thenReturn(true);
    when(cleanupDao.markPolicyDeleted(7, 2, now)).thenReturn(true);
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        scheduleManager,
        usageTracking,
        Clock.fixed(now, ZoneOffset.UTC));

    CleanupPolicyService.PolicyView view = service.update(7, new PolicyCommand(
        "scheduled",
        RepositoryFormat.MAVEN2,
        null,
        Map.of("publishedOlderThanDays", 30),
        List.of(1L),
        100,
        10,
        new ScheduleCommand("0 0 2 * * ?", "UTC", true),
        1L));

    assertEquals("ACTIVE", view.policy().state());
    ArgumentCaptor<CleanupPolicyDao.CleanupSchedule> schedules =
        ArgumentCaptor.forClass(CleanupPolicyDao.CleanupSchedule.class);
    verify(cleanupDao).upsertSchedule(schedules.capture());
    assertEquals(true, schedules.getValue().enabled());
    assertEquals(Instant.parse("2026-08-01T02:00:00Z"), schedules.getValue().nextRunAt());

    service.delete(7, 2);
    verify(cleanupDao).markPolicyDeleted(7, 2, now);
    verify(scheduleManager, times(2)).reconcileAfterCommit(7);
    verify(usageTracking, times(2)).reconcileAfterCommit();
  }

  @Test
  void validatesRequiredFieldsLimitsRepositoryIdsAndRevisionFences() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        Clock.fixed(now, ZoneOffset.UTC));

    assertThrows(CleanupValidationException.class, () -> service.create(null));
    assertThrows(CleanupValidationException.class, () -> service.create(command(
        " ", RepositoryFormat.RAW, List.of(1L), 10, 10)));
    assertThrows(CleanupValidationException.class, () -> service.create(command(
        "x".repeat(201), RepositoryFormat.RAW, List.of(1L), 10, 10)));
    assertThrows(CleanupValidationException.class, () -> service.create(command(
        "missing format", null, List.of(1L), 10, 10)));
    assertThrows(CleanupValidationException.class, () -> service.create(command(
        "missing targets", RepositoryFormat.RAW, null, 10, 10)));
    assertThrows(CleanupValidationException.class, () -> service.create(command(
        "invalid target", RepositoryFormat.RAW, List.of(0L), 10, 10)));
    assertThrows(CleanupValidationException.class, () -> service.create(command(
        "scan limit", RepositoryFormat.RAW, List.of(1L), 0, 10)));
    assertThrows(CleanupValidationException.class, () -> service.create(command(
        "delete limit", RepositoryFormat.RAW, List.of(1L), 10, 1_001)));
    assertThrows(CleanupValidationException.class, () -> service.create(command(
        "too many", RepositoryFormat.RAW,
        java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList(), 10, 10)));
    assertThrows(CleanupValidationException.class, () -> service.create(command(
        "missing repository", RepositoryFormat.RAW, List.of(999L), 10, 10)));
    assertThrows(CleanupValidationException.class, () -> service.previewSchedule(null));
    assertEquals(List.of(), CleanupPolicyService.nextRunTimes(
        new ScheduleCommand("0 0 2 * * ?", "UTC", true), now, 0));

    CleanupPolicyDao.CleanupPolicy existing = new CleanupPolicyDao.CleanupPolicy(
        7L, "existing", RepositoryFormat.RAW, null,
        Map.of("publishedOlderThanDays", 30), 3, "PAUSED", 10, 10, now, now);
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(existing));
    assertThrows(CleanupValidationException.class, () -> service.update(7, command(
        "existing", RepositoryFormat.RAW, List.of(1L), 10, 10)));
    PolicyCommand stale = new PolicyCommand(
        "existing", RepositoryFormat.RAW, null, Map.of("publishedOlderThanDays", 30),
        List.of(1L), 10, 10, null, 2L);
    assertThrows(CleanupRevisionConflictException.class, () -> service.update(7, stale));
    assertThrows(CleanupRevisionConflictException.class, () -> service.delete(7, 2));
    assertThrows(CleanupNotFoundException.class, () -> service.get(999));
  }

  @Test
  void pagesPoliciesAndLoadsTargetsAndSchedulesInBatches() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    CleanupPolicyDao.CleanupPolicy first = new CleanupPolicyDao.CleanupPolicy(
        11L, "first", RepositoryFormat.RAW, null,
        Map.of("publishedOlderThanDays", 30), 1, "PAUSED", 100, 10, now, now);
    CleanupPolicyDao.CleanupPolicy second = new CleanupPolicyDao.CleanupPolicy(
        12L, "second", RepositoryFormat.RAW, null,
        Map.of("publishedOlderThanDays", 30), 1, "PAUSED", 100, 10, now, now);
    CleanupPolicyDao.CleanupPolicy lookahead = new CleanupPolicyDao.CleanupPolicy(
        13L, "lookahead", RepositoryFormat.RAW, null,
        Map.of("publishedOlderThanDays", 30), 1, "PAUSED", 100, 10, now, now);
    when(cleanupDao.listPolicies(10, 3)).thenReturn(List.of(first, second, lookahead));
    when(cleanupDao.listTargets(List.of(11L, 12L))).thenReturn(Map.of(
        11L, List.of(new CleanupPolicyDao.TargetRepository(
            1, "raw-a", RepositoryFormat.RAW, RepositoryType.HOSTED, true)),
        12L, List.of(new CleanupPolicyDao.TargetRepository(
            2, "raw-b", RepositoryFormat.RAW, RepositoryType.PROXY, true))));
    when(cleanupDao.findSchedules(List.of(11L, 12L))).thenReturn(Map.of());
    CleanupPolicyService service = new CleanupPolicyService(
        cleanupDao,
        mock(RepositoryDao.class),
        new CleanupPolicyCapabilities(),
        Clock.fixed(now, ZoneOffset.UTC));

    CleanupPolicyService.PolicyPage page = service.listPage(10, 2);

    assertEquals(List.of(11L, 12L),
        page.items().stream().map(view -> view.policy().id()).toList());
    assertEquals(12L, page.nextAfter());
    assertEquals("raw-a", page.items().getFirst().repositories().getFirst().name());
    verify(cleanupDao).listTargets(List.of(11L, 12L));
    verify(cleanupDao).findSchedules(List.of(11L, 12L));
  }

  private static PolicyCommand command(
      String name,
      RepositoryFormat format,
      List<Long> repositoryIds,
      Integer scanLimit,
      Integer deleteLimit) {
    return new PolicyCommand(
        name,
        format,
        null,
        Map.of("publishedOlderThanDays", 30),
        repositoryIds,
        scanLimit,
        deleteLimit,
        null,
        null);
  }

  private static RepositoryRecord repository(
      long id, String name, RepositoryFormat format) {
    return repository(id, name, format, RepositoryType.HOSTED);
  }

  private static RepositoryRecord repository(
      long id, String name, RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        id,
        name,
        format,
        type,
        format.id() + "-" + type.name().toLowerCase(java.util.Locale.ROOT),
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }
}
