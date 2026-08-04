package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
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
