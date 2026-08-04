package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.ClaimedRunRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupProtection;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.TargetRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService.CleanupDeleteSubject;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Candidate;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Subject;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CleanupExecutionServiceTest {
  private final Instant now = Instant.parse("2026-08-01T00:00:00Z");
  private CleanupPolicyDao cleanupDao;
  private RepositoryDao repositoryDao;
  private CleanupSubjectScanner scanner;
  private RepositoryContentDeletionService deletion;
  private CleanupExecutionService service;
  private ClaimedRunRepository claim;
  private CleanupRun run;
  private Subject expected;

  @BeforeEach
  void setUp() {
    cleanupDao = mock(CleanupPolicyDao.class);
    repositoryDao = mock(RepositoryDao.class);
    scanner = mock(CleanupSubjectScanner.class);
    deletion = mock(RepositoryContentDeletionService.class);
    service = new CleanupExecutionService(
        cleanupDao,
        repositoryDao,
        scanner,
        deletion,
        Clock.fixed(now, ZoneOffset.UTC));
    claim = new ClaimedRunRepository(
        20,
        10,
        1,
        "raw-releases",
        RepositoryFormat.RAW,
        RepositoryType.HOSTED,
        "worker",
        "lease",
        7,
        1,
        3,
        now.plusSeconds(60));
    run = run();
    expected = subject("token-a", 3);
    when(cleanupDao.lockCurrentRunRepositoryLease(20, "lease", 7, now)).thenReturn(true);
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy()));
    when(cleanupDao.listTargets(7)).thenReturn(List.of(
        new TargetRepository(1, "raw-releases", RepositoryFormat.RAW,
            RepositoryType.HOSTED, true)));
    when(repositoryDao.findById(1)).thenReturn(Optional.of(repository()));
  }

  @Test
  void aNewDownloadBetweenScanAndDeleteMakesTheCandidateStale() {
    when(scanner.resolveLocked(repository(), "ASSET", 9, "file.zip"))
        .thenReturn(Optional.of(subject("token-a", 4)));

    var result = service.apply(claim, run, new Candidate(expected, Map.of()), "cleanup");

    assertEquals("SKIPPED_STALE", result.decision());
    assertEquals("usage revision changed", result.reason().get("staleReason"));
    verify(deletion, never()).deleteForCleanup(any(), any(), anyLong(), any(), any());
  }

  @Test
  void republishingTheSameIdentityMakesTheContentTokenStale() {
    when(scanner.resolveLocked(repository(), "ASSET", 9, "file.zip"))
        .thenReturn(Optional.of(subject("token-b", 3)));

    var result = service.apply(claim, run, new Candidate(expected, Map.of()), "cleanup");

    assertEquals("SKIPPED_STALE", result.decision());
    assertEquals("content token changed", result.reason().get("staleReason"));
  }

  @Test
  void aProtectionCreatedAfterTheScanWinsInsideTheDeleteTransaction() {
    when(scanner.resolveLocked(repository(), "ASSET", 9, "file.zip"))
        .thenReturn(Optional.of(expected));
    when(cleanupDao.findActiveProtection(
        1, "ASSET", expected.key(), expected.keyHash(), now)).thenReturn(Optional.of(
        new CleanupProtection(
            55L, "SUBJECT", 1L, "ASSET", expected.key(), expected.keyHash(),
            "MANUAL", null, "release hold", true, null, null, "admin", now, now)));

    var result = service.apply(claim, run, new Candidate(expected, Map.of()), "cleanup");

    assertEquals("KEEP_PROTECTED", result.decision());
    assertEquals(55L, result.protectionId());
    verify(deletion, never()).deleteForCleanup(any(), any(), anyLong(), any(), any());
  }

  @Test
  void onlyTheCurrentFenceMayEnterTheDeletionPath() {
    when(cleanupDao.lockCurrentRunRepositoryLease(20, "lease", 7, now)).thenReturn(false);

    assertThrows(CleanupFenceLostException.class,
        () -> service.apply(claim, run, new Candidate(expected, Map.of()), "cleanup"));

    verify(scanner, never()).resolveLocked(any(), any(), anyLong(), any());
  }

  @Test
  void cancellationObservedInsideTheCurrentFenceStopsDeletionCleanly() {
    when(cleanupDao.isRunCancellationRequested(run.id())).thenReturn(true);

    var result = service.apply(claim, run, new Candidate(expected, Map.of()), "cleanup");

    assertEquals("CANCELLED", result.decision());
    verify(scanner, never()).resolveLocked(any(), any(), anyLong(), any());
    verify(deletion, never()).deleteForCleanup(any(), any(), anyLong(), any(), any());
  }

  @Test
  void unchangedCandidateUsesTheApplicationDeletionApi() {
    when(scanner.resolveLocked(repository(), "ASSET", 9, "file.zip"))
        .thenReturn(Optional.of(expected));
    List<CleanupDeleteSubject> subjects = List.of(
        new CleanupDeleteSubject("ASSET", 9, "file.zip"));
    when(deletion.deleteBatchForCleanup("raw-releases", subjects, "cleanup"))
        .thenReturn(List.of(1));

    var result = service.apply(claim, run, new Candidate(expected, Map.of()), "cleanup");

    assertEquals("DELETED", result.decision());
    verify(deletion).deleteBatchForCleanup("raw-releases", subjects, "cleanup");
  }

  private Subject subject(String token, long usageRevision) {
    return new Subject(
        9,
        "ASSET",
        "asset:9",
        new byte[32],
        "asset:file.zip",
        "file.zip",
        "file.zip",
        null,
        "file.zip",
        now.minusSeconds(86_400),
        now.minusSeconds(172_800),
        1,
        100,
        List.of(9L),
        token,
        usageRevision);
  }

  private CleanupPolicy policy() {
    return new CleanupPolicy(
        7L,
        "raw cleanup",
        RepositoryFormat.RAW,
        null,
        Map.of("publishedOlderThanDays", 1),
        1,
        "ACTIVE",
        100,
        10,
        now,
        now);
  }

  private CleanupRun run() {
    return new CleanupRun(
        10L,
        7,
        1,
        "EXECUTE",
        "MANUAL",
        "RUNNING",
        "admin",
        null,
        100,
        10,
        Map.of("publishedOlderThanDays", 1),
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

  private RepositoryRecord repository() {
    return new RepositoryRecord(
        1L,
        "raw-releases",
        RepositoryFormat.RAW,
        RepositoryType.HOSTED,
        "raw-hosted",
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
