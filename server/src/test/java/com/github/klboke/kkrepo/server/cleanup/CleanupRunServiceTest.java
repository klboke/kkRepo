package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.ClaimedRunRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupCursorCompletion;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRunRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupScanCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.TargetRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cleanup.CleanupExecutionService.ExecutionResult;
import com.github.klboke.kkrepo.server.cleanup.CleanupRunService.RunCommand;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Candidate;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.ScanResult;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Subject;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CleanupRunServiceTest {
  private CleanupPolicyDao cleanupDao;
  private RepositoryDao repositoryDao;
  private CleanupSubjectScanner scanner;
  private CleanupExecutionService execution;
  private CleanupRunService service;
  private final Instant now = Instant.parse("2026-08-01T00:00:00Z");

  @BeforeEach
  void setUp() {
    cleanupDao = mock(CleanupPolicyDao.class);
    repositoryDao = mock(RepositoryDao.class);
    scanner = mock(CleanupSubjectScanner.class);
    execution = mock(CleanupExecutionService.class);
    service = new CleanupRunService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        scanner,
        execution,
        new CleanupRuntimeProperties(),
        mock(CleanupMetrics.class),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void manualRunOnlyPersistsParentAndRepositoryShards() {
    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 100);
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy));
    when(cleanupDao.listTargets(7)).thenReturn(List.of(
        target(1, "releases-a", RepositoryFormat.MAVEN2),
        target(2, "releases-b", RepositoryFormat.MAVEN2)));
    when(cleanupDao.createRun(any())).thenReturn(100L);
    AtomicLong shardId = new AtomicLong(200);
    when(cleanupDao.createRunRepository(any())).thenAnswer(ignored -> shardId.getAndIncrement());
    when(cleanupDao.findRun(100)).thenReturn(Optional.of(run(100, policy, "TRY_RUN", "PENDING", 5)));
    when(cleanupDao.listRunRepositories(100)).thenReturn(List.of());

    var view = service.startManual(7, new RunCommand("TRY_RUN", 3L, 5), "admin");

    assertEquals("PENDING", view.run().state());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<CleanupRunRepository> shards =
        ArgumentCaptor.forClass(CleanupRunRepository.class);
    verify(cleanupDao, org.mockito.Mockito.times(2)).createRunRepository(shards.capture());
    assertEquals(List.of(1L, 2L),
        shards.getAllValues().stream().map(CleanupRunRepository::repositoryId).toList());
    verify(scanner, never()).scan(any(), any(), any(Integer.class), any(Instant.class));
  }

  @Test
  void manualRunRejectsPersistedGroupTargetBeforeCreatingRun() {
    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 100);
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy));
    when(cleanupDao.listTargets(7)).thenReturn(List.of(
        target(1, "maven-group", RepositoryFormat.MAVEN2, RepositoryType.GROUP)));

    assertThrows(
        CleanupValidationException.class,
        () -> service.startManual(7, new RunCommand("TRY_RUN", 3L, 5), "admin"));

    verify(cleanupDao, never()).createRun(any());
  }

  @Test
  void claimedTryRunUsesItsDurablyReservedBudget() {
    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 100);
    CleanupRun run = run(100, policy, "TRY_RUN", "RUNNING", 100);
    ClaimedRunRepository claim = claim(200, 100, 1, RepositoryFormat.MAVEN2);
    when(cleanupDao.findRun(100)).thenReturn(Optional.of(run));
    when(repositoryDao.findById(1)).thenReturn(Optional.of(repository(
        1, "releases", RepositoryFormat.MAVEN2)));
    when(cleanupDao.reserveTryRunScanBudget(100, 200, 100, 50_000)).thenReturn(5);
    when(cleanupDao.heartbeatRunRepository(eq(200L), eq("lease"), eq(9L), any(), eq(now)))
        .thenReturn(true);
    when(scanner.scan(any(), eq(policy.criteria()), eq(5), eq(now)))
        .thenReturn(new ScanResult(5, true, "family", List.of()));
    when(cleanupDao.completeClaimedRunRepository(
        eq(200L), eq("lease"), eq(9L), eq("SUCCEEDED_TRUNCATED"),
        eq(5L), eq(0L), eq(0L), eq(0L), eq(0L), eq(true), any(), eq(now)))
        .thenReturn(true);
    when(cleanupDao.listRunRepositories(100)).thenReturn(List.of());

    service.process(claim);

    verify(scanner).scan(any(), eq(policy.criteria()), eq(5), eq(now));
  }

  @Test
  void exhaustedTryRunBudgetCompletesWithoutScanningRepository() {
    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 10_000);
    CleanupRun run = run(100, policy, "TRY_RUN", "RUNNING", 10_000);
    ClaimedRunRepository claim = claim(200, 100, 1, RepositoryFormat.MAVEN2);
    when(cleanupDao.findRun(100)).thenReturn(Optional.of(run));
    when(repositoryDao.findById(1)).thenReturn(Optional.of(repository(
        1, "releases", RepositoryFormat.MAVEN2)));
    when(cleanupDao.reserveTryRunScanBudget(100, 200, 10_000, 50_000)).thenReturn(0);
    when(cleanupDao.completeClaimedRunRepository(
        eq(200L), eq("lease"), eq(9L), eq("SUCCEEDED_TRUNCATED"),
        eq(0L), eq(0L), eq(0L), eq(0L), eq(0L), eq(true), any(), eq(now)))
        .thenReturn(true);
    when(cleanupDao.listRunRepositories(100)).thenReturn(List.of());

    service.process(claim);

    verify(scanner, never()).scan(any(), any(), any(Integer.class), any(Instant.class));
  }

  @Test
  void tryRunPersistsExactWouldDeleteCountExcludingProtectedCandidates() {
    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 100);
    CleanupRun run = run(100, policy, "TRY_RUN", "RUNNING", 100);
    ClaimedRunRepository claim = claim(200, 100, 1, RepositoryFormat.MAVEN2);
    Subject deletable = new Subject(
        9, "COMPONENT", "component:9", new byte[32], "example\u0000demo",
        "demo", "example:demo:1.0", "1.0", "example/demo/1.0", null,
        now.minusSeconds(86_400), 1, 100);
    Subject protectedSubject = new Subject(
        10, "COMPONENT", "component:10", new byte[32], "example\u0000demo",
        "demo", "example:demo:2.0", "2.0", "example/demo/2.0", null,
        now.minusSeconds(86_400), 1, 100);
    when(cleanupDao.findRun(100)).thenReturn(Optional.of(run));
    when(repositoryDao.findById(1)).thenReturn(Optional.of(repository(
        1, "releases", RepositoryFormat.MAVEN2)));
    when(cleanupDao.reserveTryRunScanBudget(100, 200, 100, 50_000)).thenReturn(100);
    when(cleanupDao.heartbeatRunRepository(eq(200L), eq("lease"), eq(9L), any(), eq(now)))
        .thenReturn(true);
    when(scanner.scan(any(), eq(policy.criteria()), eq(100), eq(now)))
        .thenReturn(new ScanResult(2, false, null, List.of(
            new Candidate(deletable, Map.of("publishedOlderThanDays", 30)),
            new Candidate(protectedSubject, Map.of("publishedOlderThanDays", 30), 77L))));
    when(cleanupDao.completeClaimedRunRepository(
        eq(200L), eq("lease"), eq(9L), eq("SUCCEEDED"),
        eq(2L), eq(2L), eq(1L), eq(0L), eq(0L), eq(false), eq(null), eq(now)))
        .thenReturn(true);
    when(cleanupDao.listRunRepositories(100)).thenReturn(List.of());

    service.process(claim);

    verify(cleanupDao).upsertRunItems(argThat(items ->
        items.size() == 2
            && items.stream().filter(item -> "WOULD_DELETE".equals(item.decision())).count() == 1
            && items.stream().filter(item -> "KEEP_PROTECTED".equals(item.decision())).count() == 1));
  }

  @Test
  void claimedNpmExecuteDelegatesEachFamilyToTheTransactionalBatchGate() {
    CleanupPolicy policy = policy(RepositoryFormat.NPM, 100);
    CleanupRun run = run(100, policy, "EXECUTE", "RUNNING", 100);
    ClaimedRunRepository claim = claim(200, 100, 2, RepositoryFormat.NPM);
    Subject subject = new Subject(
        9, "COMPONENT", "component:9", new byte[32], "scope\u0000name\u0000RELEASE",
        "name", "scope:name:1.0", "1.0", "@scope/name/-/name-1.0.tgz", null,
        now.minusSeconds(86_400), 1, 100);
    Candidate candidate = new Candidate(subject, Map.of("publishedOlderThanDays", 30));
    when(cleanupDao.findRun(100)).thenReturn(Optional.of(run));
    when(repositoryDao.findById(2)).thenReturn(Optional.of(repository(
        2, "npm-releases", RepositoryFormat.NPM)));
    when(cleanupDao.heartbeatRunRepository(eq(200L), eq("lease"), eq(9L), any(), eq(now)))
        .thenReturn(true);
    CleanupScanCursor startCursor = new CleanupScanCursor(
        7, 2, "COMPONENT", null, null, null, 0, 3, 0);
    CleanupScanCursor nextCursor = new CleanupScanCursor(
        7, 2, "COMPONENT", "scope", "name", "npm-package", 0, 3, 0);
    when(cleanupDao.acquireRunRepositoryScanCursor(
        200, "lease", 9, "COMPONENT", now)).thenReturn(startCursor);
    when(scanner.scan(any(), eq(policy.criteria()), eq(100), eq(now), eq(startCursor)))
        .thenReturn(new ScanResult(
            1, false, null, List.of(candidate), null, startCursor, nextCursor, null));
    when(execution.applyBatch(claim, run, List.of(candidate), "cleanup-run:100:admin"))
        .thenReturn(List.of(
            new ExecutionResult("DELETED", 1, null, Map.of("deletedAssets", 1))));
    when(cleanupDao.completeClaimedRunRepositoryAndAdvanceCursor(
        eq(200L), eq("lease"), eq(9L), eq("SUCCEEDED"),
        eq(1L), eq(1L), eq(0L), eq(1L), eq(0L), eq(false), eq(null), eq(now),
        eq(startCursor), eq(nextCursor)))
        .thenReturn(new CleanupCursorCompletion(true, true));
    when(cleanupDao.listRunRepositories(100)).thenReturn(List.of());

    service.process(claim);

    verify(execution).applyBatch(
        claim, run, List.of(candidate), "cleanup-run:100:admin");
    verify(cleanupDao).upsertRunItems(argThat(items ->
        items.size() == 1
            && "DELETED".equals(items.getFirst().decision())
            && Integer.valueOf(1).equals(items.getFirst().reason().get("deletedAssets"))));
  }

  private CleanupPolicy policy(RepositoryFormat format, int scanLimitPerRepository) {
    return new CleanupPolicy(
        7L,
        "old releases",
        format,
        null,
        Map.of("publishedOlderThanDays", 30),
        3,
        "PAUSED",
        scanLimitPerRepository,
        10,
        now,
        now);
  }

  private TargetRepository target(long id, String name, RepositoryFormat format) {
    return target(id, name, format, RepositoryType.HOSTED);
  }

  private TargetRepository target(
      long id, String name, RepositoryFormat format, RepositoryType type) {
    return new TargetRepository(id, name, format, type, true);
  }

  private RepositoryRecord repository(long id, String name, RepositoryFormat format) {
    return new RepositoryRecord(
        id,
        name,
        format,
        RepositoryType.HOSTED,
        format.id() + "-hosted",
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

  private CleanupRun run(
      long id, CleanupPolicy policy, String mode, String state, int scanLimit) {
    return new CleanupRun(
        id,
        policy.id(),
        policy.revision(),
        mode,
        "MANUAL",
        state,
        "admin",
        null,
        scanLimit,
        policy.deleteLimitPerRepository(),
        policy.criteria(),
        List.of(),
        0,
        0,
        0,
        0,
        0,
        null,
        now,
        null,
        now,
        now);
  }

  private ClaimedRunRepository claim(
      long id, long runId, long repositoryId, RepositoryFormat format) {
    return new ClaimedRunRepository(
        id,
        runId,
        repositoryId,
        "repository-" + repositoryId,
        format,
        RepositoryType.HOSTED,
        "worker",
        "lease",
        9,
        1,
        3,
        now.plusSeconds(120));
  }
}
