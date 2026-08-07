package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.ClaimedRunRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupCursorCompletion;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRunItemSummary;
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
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CleanupRunServiceTest {
  private CleanupPolicyDao cleanupDao;
  private RepositoryDao repositoryDao;
  private CleanupSubjectScanner scanner;
  private CleanupExecutionService execution;
  private CleanupMetrics metrics;
  private CleanupRunService service;
  private final Instant now = Instant.parse("2026-08-01T00:00:00Z");

  @BeforeEach
  void setUp() {
    cleanupDao = mock(CleanupPolicyDao.class);
    repositoryDao = mock(RepositoryDao.class);
    scanner = mock(CleanupSubjectScanner.class);
    execution = mock(CleanupExecutionService.class);
    metrics = mock(CleanupMetrics.class);
    service = new CleanupRunService(
        cleanupDao,
        repositoryDao,
        new CleanupPolicyCapabilities(),
        scanner,
        execution,
        new CleanupRuntimeProperties(),
        metrics,
        Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void validatesManualCommandsAndBoundsRunHistoryQueries() {
    assertThrows(
        CleanupValidationException.class,
        () -> service.startManual(7, null, "admin"));
    assertThrows(
        CleanupNotFoundException.class,
        () -> service.startManual(99, new RunCommand("TRY_RUN", 1L, 10), "admin"));

    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 100);
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy));
    assertThrows(
        CleanupRevisionConflictException.class,
        () -> service.startManual(7, new RunCommand("TRY_RUN", 2L, 10), "admin"));
    assertThrows(
        CleanupValidationException.class,
        () -> service.startManual(7, new RunCommand("invalid", 3L, 10), "admin"));
    assertThrows(
        CleanupValidationException.class,
        () -> service.startManual(7, new RunCommand("TRY_RUN", 3L, null), "admin"));
    assertThrows(
        CleanupValidationException.class,
        () -> service.startManual(7, new RunCommand("TRY_RUN", 3L, 0), "admin"));
    assertThrows(
        CleanupValidationException.class,
        () -> service.startManual(7, new RunCommand("TRY_RUN", 3L, 100_001), "admin"));

    service.listRuns(null, 5, 0);
    service.listRuns(7L, 6, 1_000);
    verify(cleanupDao).listRuns(null, 5, 1);
    verify(cleanupDao).listRuns(7L, 6, 100);

    when(cleanupDao.findRunRepository(100, 200)).thenReturn(Optional.of(
        shard(200, 100, "SUCCEEDED", 1, 1, 1, 1, 0, false, null)));
    service.listItems(100, 200, 9, 0);
    service.listItems(100, 200, 10, 1_000);
    verify(cleanupDao).listRunItems(200, 9, 1);
    verify(cleanupDao).listRunItems(200, 10, 200);
    assertThrows(
        CleanupNotFoundException.class,
        () -> service.listItems(100, 999, 0, 10));

    CleanupRun run = run(100, policy, "TRY_RUN", "SUCCEEDED", 100);
    CleanupRunRepository first = shard(
        200, 100, "SUCCEEDED", 1, 1, 1, 0, 0, false, null);
    CleanupRunRepository second = shard(
        201, 100, "SUCCEEDED", 1, 1, 1, 0, 0, false, null);
    when(cleanupDao.findRun(100)).thenReturn(Optional.of(run));
    when(cleanupDao.listRunRepositories(100)).thenReturn(List.of(first, second));
    when(cleanupDao.listRunItems(List.of(200L, 201L), 200)).thenReturn(Map.of());

    service.getRunDetails(100, 1_000);

    verify(cleanupDao).listRunItems(List.of(200L, 201L), 200);
  }

  @Test
  void pagesNewestRunsWithAnExclusiveKeysetCursorAndBoundedLookahead() {
    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 100);
    CleanupRun newest = run(103, policy, "TRY_RUN", "SUCCEEDED", 100);
    CleanupRun middle = run(102, policy, "TRY_RUN", "SUCCEEDED", 100);
    CleanupRun lookahead = run(101, policy, "TRY_RUN", "SUCCEEDED", 100);
    when(cleanupDao.listRunsBefore(null, 0, 3))
        .thenReturn(List.of(newest, middle, lookahead));

    CleanupRunService.RunPage firstPage = service.listRunPage(null, -1, 2);

    assertEquals(List.of(newest, middle), firstPage.items());
    assertEquals(102L, firstPage.nextBefore());
    verify(cleanupDao).listRunsBefore(null, 0, 3);

    when(cleanupDao.listRunsBefore(7L, 102, 101)).thenReturn(List.of(lookahead));
    CleanupRunService.RunPage lastPage = service.listRunPage(7L, 102, 1_000);

    assertEquals(List.of(lookahead), lastPage.items());
    assertEquals(null, lastPage.nextBefore());
    verify(cleanupDao).listRunsBefore(7L, 102, 101);

    when(cleanupDao.listRunsBefore(7L, 101, 2)).thenReturn(List.of());
    assertEquals(List.of(), service.listRunPage(7L, 101, 0).items());
    verify(cleanupDao).listRunsBefore(7L, 101, 2);
  }

  @Test
  void scheduledRunIsIdempotentAndReportsInvisibleWinner() {
    Instant scheduledFor = now.plusSeconds(60);
    CleanupPolicy active = policy(RepositoryFormat.MAVEN2, 100, "ACTIVE", 10);
    CleanupRun winner = run(101, active, "EXECUTE", "PENDING", 100);
    when(cleanupDao.findScheduledRun(7, scheduledFor))
        .thenReturn(Optional.of(winner), Optional.empty(), Optional.empty(), Optional.empty());
    when(cleanupDao.listRunRepositories(101)).thenReturn(List.of());

    assertEquals(101, service.startScheduled(7, scheduledFor).run().id());

    when(cleanupDao.findPolicy(7))
        .thenReturn(Optional.of(policy(RepositoryFormat.MAVEN2, 100)))
        .thenReturn(Optional.of(active));
    assertThrows(
        CleanupValidationException.class,
        () -> service.startScheduled(7, scheduledFor));

    when(cleanupDao.listTargets(7)).thenReturn(List.of(
        target(1, "releases", RepositoryFormat.MAVEN2)));
    when(cleanupDao.tryCreateRun(any())).thenReturn(OptionalLong.empty());
    assertThrows(
        IllegalStateException.class,
        () -> service.startScheduled(7, scheduledFor));
  }

  @Test
  void rejectsPolicyWithoutTargetsAndCancelsOnlyActiveRuns() {
    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 100);
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy));
    when(cleanupDao.listTargets(7)).thenReturn(List.of());
    assertThrows(
        CleanupValidationException.class,
        () -> service.startManual(7, new RunCommand("EXECUTE", 3L, null), "admin"));

    CleanupRun run = run(100, policy, "EXECUTE", "RUNNING", 100);
    when(cleanupDao.findRun(100)).thenReturn(Optional.of(run));
    when(cleanupDao.requestRunCancellation(100, now)).thenReturn(false, true);
    assertThrows(CleanupValidationException.class, () -> service.cancel(100));
    when(cleanupDao.listRunRepositories(100)).thenReturn(List.of());
    assertEquals(100, service.cancel(100).run().id());
    assertThrows(CleanupNotFoundException.class, () -> service.getRun(999));
  }

  @Test
  void manualRunOnlyPersistsParentAndRepositoryShards() {
    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 100);
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy));
    when(cleanupDao.listTargets(7)).thenReturn(List.of(
        target(1, "releases-a", RepositoryFormat.MAVEN2),
        target(2, "releases-b", RepositoryFormat.MAVEN2)));
    when(cleanupDao.createRun(any())).thenReturn(100L);
    when(cleanupDao.findRun(100)).thenReturn(Optional.of(run(100, policy, "TRY_RUN", "PENDING", 5)));
    when(cleanupDao.listRunRepositories(100)).thenReturn(List.of());

    var view = service.startManual(7, new RunCommand("TRY_RUN", 3L, 5), "admin");

    assertEquals("PENDING", view.run().state());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<CleanupRunRepository>> shards = ArgumentCaptor.forClass(List.class);
    verify(cleanupDao).createRunRepositories(shards.capture());
    assertEquals(List.of(1L, 2L),
        shards.getValue().stream().map(CleanupRunRepository::repositoryId).toList());
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
    verify(cleanupDao, never()).upsertRunItems(any());
  }

  @Test
  void takeoverRestoresDurableDeleteCountBeforeApplyingTheDeleteLimit() {
    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 100, "PAUSED", 1);
    CleanupRun run = run(112, policy, "EXECUTE", "RUNNING", 100);
    ClaimedRunRepository claim = claim(
        212, 112, 3, RepositoryFormat.MAVEN2, 2, 3, true);
    CleanupScanCursor startCursor = cursor(212, 3, null);
    CleanupScanCursor nextCursor = cursor(212, 3, "demo");
    Candidate candidate = new Candidate(
        subject(12, "demo:4.0", "4.0"), Map.of("age", 30));
    when(cleanupDao.findRun(112)).thenReturn(Optional.of(run));
    when(repositoryDao.findById(3)).thenReturn(Optional.of(
        repository(3, "takeover-releases", RepositoryFormat.MAVEN2)));
    when(cleanupDao.heartbeatRunRepository(
        eq(212L), eq("lease"), eq(9L), any(), eq(now))).thenReturn(true);
    when(cleanupDao.acquireRunRepositoryScanCursor(
        212, "lease", 9, "COMPONENT", now)).thenReturn(startCursor);
    when(cleanupDao.summarizeRunItems(212))
        .thenReturn(new CleanupRunItemSummary(1, 0, 1, 0));
    when(scanner.scan(any(), eq(policy.criteria()), eq(100), eq(now), eq(startCursor)))
        .thenReturn(new ScanResult(
            1, false, null, List.of(candidate), null, startCursor, nextCursor, null));
    when(cleanupDao.completeClaimedRunRepository(
        eq(212L), eq("lease"), eq(9L), eq("PARTIAL_LIMIT_REACHED"),
        eq(1L), eq(1L), eq(0L), eq(1L), eq(0L), eq(false), eq(null), eq(now)))
        .thenReturn(true);
    when(cleanupDao.listRunRepositories(112)).thenReturn(List.of());

    service.process(claim);

    verify(execution, never()).apply(any(), any(), any(), any());
    verify(cleanupDao, never()).completeClaimedRunRepositoryAndAdvanceCursor(
        any(Long.class), any(), any(Long.class), any(), any(Long.class), any(Long.class),
        any(Long.class), any(Long.class), any(Long.class), any(Boolean.class), any(),
        any(), any(), any());
  }

  @Test
  void retriesTransientShardFailuresAndFailsExhaustedOrStaleClaims() {
    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 100);
    CleanupRun run = run(100, policy, "EXECUTE", "RUNNING", 100);
    when(cleanupDao.findRun(100)).thenReturn(Optional.of(run));
    when(cleanupDao.retryClaimedRunRepository(
        eq(201L), eq("lease"), eq(9L), eq(now.plusSeconds(5)),
        eq("CleanupValidationException"), any(), eq(now)))
        .thenReturn(true);

    service.process(claim(201, 100, 21, RepositoryFormat.MAVEN2, 1, 3, true));
    verify(metrics).takeover();
    verify(cleanupDao).retryClaimedRunRepository(
        eq(201L), eq("lease"), eq(9L), eq(now.plusSeconds(5)),
        eq("CleanupValidationException"), any(), eq(now));

    when(cleanupDao.completeClaimedRunRepository(
        any(Long.class), eq("lease"), eq(9L), eq("FAILED"),
        eq(0L), eq(0L), eq(0L), eq(0L), eq(1L), eq(false), any(), eq(now)))
        .thenReturn(true);
    when(cleanupDao.listRunRepositories(100)).thenReturn(List.of());
    service.process(claim(202, 100, 22, RepositoryFormat.MAVEN2, 3, 3, false));
    service.process(claim(203, 100, 23, RepositoryFormat.MAVEN2, 4, 3, false));

    when(repositoryDao.findById(24)).thenReturn(Optional.of(
        repository(24, "releases", RepositoryFormat.MAVEN2)));
    when(cleanupDao.heartbeatRunRepository(
        eq(204L), eq("lease"), eq(9L), any(), eq(now))).thenReturn(false);
    service.process(claim(204, 100, 24, RepositoryFormat.MAVEN2, 1, 3, false));

    verify(cleanupDao, times(2)).completeClaimedRunRepository(
        any(Long.class), eq("lease"), eq(9L), eq("FAILED"),
        eq(0L), eq(0L), eq(0L), eq(0L), eq(1L), eq(false), any(), eq(now));
    verify(metrics).fenceRejected();
  }

  @Test
  void executeEnforcesDeleteLimitAndPersistsPerItemFailureWithWarnings() {
    CleanupPolicy limitedPolicy = policy(RepositoryFormat.MAVEN2, 100, "PAUSED", 1);
    CleanupRun limitedRun = run(110, limitedPolicy, "EXECUTE", "RUNNING", 100);
    ClaimedRunRepository limitedClaim = claim(210, 110, 1, RepositoryFormat.MAVEN2);
    CleanupScanCursor limitedCursor = cursor(210, 1, null);
    Subject first = subject(9, "demo:1.0", "1.0");
    Subject second = subject(10, "demo:2.0", "2.0");
    Candidate firstCandidate = new Candidate(first, Map.of("age", 30));
    Candidate secondCandidate = new Candidate(second, Map.of("age", 30));
    when(cleanupDao.findRun(110)).thenReturn(Optional.of(limitedRun));
    when(repositoryDao.findById(1)).thenReturn(Optional.of(
        repository(1, "releases", RepositoryFormat.MAVEN2)));
    when(cleanupDao.heartbeatRunRepository(
        eq(210L), eq("lease"), eq(9L), any(), eq(now))).thenReturn(true);
    when(cleanupDao.acquireRunRepositoryScanCursor(210, "lease", 9, "COMPONENT", now))
        .thenReturn(limitedCursor);
    when(scanner.scan(any(), eq(limitedPolicy.criteria()), eq(100), eq(now), eq(limitedCursor)))
        .thenReturn(new ScanResult(
            2, false, null, List.of(firstCandidate, secondCandidate),
            null, limitedCursor, cursor(210, 1, "demo"), null));
    when(execution.apply(limitedClaim, limitedRun, firstCandidate, "cleanup-run:110:admin"))
        .thenReturn(new ExecutionResult("DELETED", 1, null, Map.of("deletedAssets", 1)));
    when(cleanupDao.completeClaimedRunRepository(
        eq(210L), eq("lease"), eq(9L), eq("PARTIAL_LIMIT_REACHED"),
        eq(2L), eq(2L), eq(0L), eq(1L), eq(0L), eq(false), eq(null), eq(now)))
        .thenReturn(true);
    when(cleanupDao.listRunRepositories(110)).thenReturn(List.of());

    service.process(limitedClaim);

    verify(execution, never()).apply(
        limitedClaim, limitedRun, secondCandidate, "cleanup-run:110:admin");

    CleanupPolicy failurePolicy = policy(RepositoryFormat.MAVEN2, 100);
    CleanupRun failureRun = run(111, failurePolicy, "EXECUTE", "RUNNING", 100);
    ClaimedRunRepository failureClaim = claim(211, 111, 2, RepositoryFormat.MAVEN2);
    CleanupScanCursor failureCursor = cursor(211, 2, null);
    CleanupScanCursor nextCursor = cursor(211, 2, "demo");
    Candidate failing = new Candidate(subject(11, "demo:3.0", "3.0"), Map.of("age", 30));
    when(cleanupDao.findRun(111)).thenReturn(Optional.of(failureRun));
    when(repositoryDao.findById(2)).thenReturn(Optional.of(
        repository(2, "other-releases", RepositoryFormat.MAVEN2)));
    when(cleanupDao.heartbeatRunRepository(
        eq(211L), eq("lease"), eq(9L), any(), eq(now))).thenReturn(true);
    when(cleanupDao.acquireRunRepositoryScanCursor(211, "lease", 9, "COMPONENT", now))
        .thenReturn(failureCursor);
    when(scanner.scan(any(), eq(failurePolicy.criteria()), eq(100), eq(now), eq(failureCursor)))
        .thenReturn(new ScanResult(
            1, false, "demo", List.of(failing), "usage tracking warming",
            failureCursor, nextCursor, "cursor reset"));
    doThrow(new IllegalStateException()).when(execution)
        .apply(failureClaim, failureRun, failing, "cleanup-run:111:admin");
    when(cleanupDao.completeClaimedRunRepositoryAndAdvanceCursor(
        eq(211L), eq("lease"), eq(9L), eq("PARTIAL"),
        eq(1L), eq(1L), eq(0L), eq(0L), eq(1L), eq(false),
        argThat(error -> error.contains("usage tracking warming")
            && error.contains("incomplete family") && error.contains("cursor reset")),
        eq(now), eq(failureCursor), eq(nextCursor)))
        .thenReturn(new CleanupCursorCompletion(true, false));
    when(cleanupDao.listRunRepositories(111)).thenReturn(List.of());

    service.process(failureClaim);

    verify(cleanupDao).upsertRunItems(argThat(items ->
        items.size() == 1
            && "FAILED".equals(items.getFirst().decision())
            && "IllegalStateException".equals(items.getFirst().errorSummary())));
    verify(metrics).cursorConflict();
  }

  @Test
  void cancellationBeforeAndAfterScanningTerminatesShardWithoutAdvancingCursor() {
    CleanupPolicy policy = policy(RepositoryFormat.MAVEN2, 100);
    CleanupRun run = run(120, policy, "EXECUTE", "RUNNING", 100);
    when(cleanupDao.findRun(120)).thenReturn(Optional.of(run));
    when(cleanupDao.isRunCancellationRequested(120)).thenReturn(true);
    when(cleanupDao.completeClaimedRunRepository(
        eq(220L), eq("lease"), eq(9L), eq("CANCELLED"),
        eq(0L), eq(0L), eq(0L), eq(0L), eq(0L), eq(false),
        eq("run cancellation requested"), eq(now)))
        .thenReturn(true);
    when(cleanupDao.listRunRepositories(120)).thenReturn(List.of());
    service.process(claim(220, 120, 1, RepositoryFormat.MAVEN2));
    verify(scanner, never()).scan(any(), any(), any(Integer.class), any(Instant.class));

    CleanupRun secondRun = run(121, policy, "EXECUTE", "RUNNING", 100);
    ClaimedRunRepository secondClaim = claim(221, 121, 2, RepositoryFormat.MAVEN2);
    CleanupScanCursor start = cursor(221, 2, null);
    CleanupScanCursor next = cursor(221, 2, "next");
    Candidate candidate = new Candidate(subject(12, "demo:4.0", "4.0"), Map.of());
    when(cleanupDao.findRun(121)).thenReturn(Optional.of(secondRun));
    when(cleanupDao.isRunCancellationRequested(121)).thenReturn(false, true);
    when(repositoryDao.findById(2)).thenReturn(Optional.of(
        repository(2, "other", RepositoryFormat.MAVEN2)));
    when(cleanupDao.heartbeatRunRepository(
        eq(221L), eq("lease"), eq(9L), any(), eq(now))).thenReturn(true);
    when(cleanupDao.acquireRunRepositoryScanCursor(221, "lease", 9, "COMPONENT", now))
        .thenReturn(start);
    when(scanner.scan(any(), eq(policy.criteria()), eq(100), eq(now), eq(start)))
        .thenReturn(new ScanResult(1, false, null, List.of(candidate), null, start, next, null));
    when(execution.apply(secondClaim, secondRun, candidate, "cleanup-run:121:admin"))
        .thenReturn(new ExecutionResult("DELETED", 1, null, Map.of()));
    when(cleanupDao.completeClaimedRunRepository(
        eq(221L), eq("lease"), eq(9L), eq("CANCELLED"),
        eq(1L), eq(1L), eq(0L), eq(1L), eq(0L), eq(false),
        eq("run cancellation requested"), eq(now)))
        .thenReturn(true);
    when(cleanupDao.listRunRepositories(121)).thenReturn(List.of());

    service.process(secondClaim);

    verify(cleanupDao, never()).completeClaimedRunRepositoryAndAdvanceCursor(
        eq(221L), any(), any(Long.class), any(), any(Long.class), any(Long.class),
        any(Long.class), any(Long.class), any(Long.class), any(Boolean.class), any(),
        any(), any(), any());
  }

  @Test
  void aggregateDerivesEveryTerminalParentStateAndTotals() {
    Map<Long, CleanupRun> runs = Map.of(
        1L, run(1, policy(RepositoryFormat.MAVEN2, 100), "EXECUTE", "RUNNING", 100),
        2L, run(2, policy(RepositoryFormat.MAVEN2, 100), "EXECUTE", "RUNNING", 100),
        3L, run(3, policy(RepositoryFormat.MAVEN2, 100), "EXECUTE", "RUNNING", 100),
        4L, run(4, policy(RepositoryFormat.MAVEN2, 100), "EXECUTE", "RUNNING", 100),
        5L, run(5, policy(RepositoryFormat.MAVEN2, 100), "EXECUTE", "RUNNING", 100),
        6L, run(6, policy(RepositoryFormat.MAVEN2, 100), "EXECUTE", "RUNNING", 100),
        7L, run(7, policy(RepositoryFormat.MAVEN2, 100), "EXECUTE", "RUNNING", 100),
        8L, run(8, policy(RepositoryFormat.MAVEN2, 100), "EXECUTE", "RUNNING", 100),
        9L, run(9, policy(RepositoryFormat.MAVEN2, 100), "EXECUTE", "SUCCEEDED", 100));
    when(cleanupDao.findRun(any(Long.class))).thenAnswer(invocation ->
        Optional.ofNullable(runs.get(invocation.getArgument(0, Long.class))));
    when(cleanupDao.listRunRepositories(1)).thenReturn(List.of(
        shard(11, 1, "SUCCEEDED", 2, 1, 0, 1, 0, false, null)));
    when(cleanupDao.listRunRepositories(2)).thenReturn(List.of(
        shard(21, 2, "FAILED", 2, 1, 0, 0, 1, false, "first"),
        shard(22, 2, "FAILED", 3, 2, 0, 0, 2, false, "second")));
    when(cleanupDao.listRunRepositories(3)).thenReturn(List.of(
        shard(31, 3, "FAILED", 2, 1, 0, 0, 1, false, "failed"),
        shard(32, 3, "SUCCEEDED", 3, 2, 0, 2, 0, false, null)));
    when(cleanupDao.listRunRepositories(4)).thenReturn(List.of(
        shard(41, 4, "PARTIAL_LIMIT_REACHED", 4, 3, 0, 2, 0, false, null)));
    when(cleanupDao.listRunRepositories(5)).thenReturn(List.of(
        shard(51, 5, "SUCCEEDED_TRUNCATED", 5, 4, 0, 3, 0, true, null)));
    when(cleanupDao.listRunRepositories(6)).thenReturn(List.of(
        shard(61, 6, "SUCCEEDED", 6, 5, 2, 4, 0, false, null)));
    when(cleanupDao.listRunRepositories(7)).thenReturn(List.of());
    when(cleanupDao.listRunRepositories(8)).thenReturn(List.of(
        shard(81, 8, "RUNNING", 0, 0, 0, 0, 0, false, null)));
    when(cleanupDao.isRunCancellationRequested(any(Long.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Long.class) == 1L);
    when(cleanupDao.completeRun(
        any(Long.class), any(), any(Long.class), any(Long.class), any(Long.class),
        any(Long.class), any(Long.class), any(Integer.class), any(), eq(now)))
        .thenReturn(true);

    for (long runId = 1; runId <= 9; runId++) service.aggregate(runId);
    service.aggregate(999);

    verifyParentState(1, "CANCELLED");
    verifyParentState(2, "FAILED");
    verifyParentState(3, "PARTIAL");
    verifyParentState(4, "PARTIAL_LIMIT_REACHED");
    verifyParentState(5, "SUCCEEDED_TRUNCATED");
    verifyParentState(6, "SUCCEEDED");
    verify(cleanupDao, times(6)).completeRun(
        any(Long.class), any(), any(Long.class), any(Long.class), any(Long.class),
        any(Long.class), any(Long.class), any(Integer.class), any(), eq(now));
  }

  private CleanupPolicy policy(RepositoryFormat format, int scanLimitPerRepository) {
    return policy(format, scanLimitPerRepository, "PAUSED", 10);
  }

  private CleanupPolicy policy(
      RepositoryFormat format, int scanLimitPerRepository, String state, int deleteLimit) {
    return new CleanupPolicy(
        7L,
        "old releases",
        format,
        null,
        Map.of("publishedOlderThanDays", 30),
        3,
        state,
        scanLimitPerRepository,
        deleteLimit,
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
    return claim(id, runId, repositoryId, format, 1, 3, false);
  }

  private ClaimedRunRepository claim(
      long id,
      long runId,
      long repositoryId,
      RepositoryFormat format,
      int attemptCount,
      int maxAttempts,
      boolean takeover) {
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
        attemptCount,
        maxAttempts,
        now.plusSeconds(120),
        takeover);
  }

  private Subject subject(long id, String displayName, String version) {
    return new Subject(
        id, "COMPONENT", "component:" + id, new byte[32], "example\u0000demo",
        "demo", displayName, version, "example/demo/" + version, null,
        now.minusSeconds(86_400), 1, 100);
  }

  private CleanupScanCursor cursor(long id, long repositoryId, String name) {
    return new CleanupScanCursor(7, repositoryId, "COMPONENT", "example", name,
        "maven-component", id, 1, 0);
  }

  private CleanupRunRepository shard(
      long id,
      long runId,
      String state,
      long scanned,
      long matched,
      long wouldDelete,
      long deleted,
      long failed,
      boolean truncated,
      String error) {
    return new CleanupRunRepository(
        id, runId, id, "repository-" + id, RepositoryFormat.MAVEN2,
        RepositoryType.HOSTED, state, null, 1, 3, now, null, null, null, 1,
        null, scanned, matched, wouldDelete, deleted, failed, truncated, error,
        now, now, now, now);
  }

  private void verifyParentState(long runId, String state) {
    verify(cleanupDao).completeRun(
        eq(runId), eq(state), any(Long.class), any(Long.class), any(Long.class),
        any(Long.class), any(Long.class), any(Integer.class), any(), eq(now));
  }
}
