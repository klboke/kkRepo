package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.plugin.api.CleanupCandidate;
import com.github.klboke.kkrepo.plugin.api.CleanupRequest;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Candidate;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.ScanResult;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Subject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CleanupRunServiceScriptRulesTest {

  @Test
  void appendsResolvedScriptCandidatesToTheRun() {
    CleanupSubjectScanner scanner = mock(CleanupSubjectScanner.class);
    Subject subject = subject("asset-a", "path/a");
    when(scanner.resolvePath(any(), eq("path/a"))).thenReturn(Optional.of(subject));

    CleanupRuleCandidateService scriptRules = new CleanupRuleCandidateService(
        List.of(rule("rule-1", "path/a")), scanner);
    CleanupRunService runs = new CleanupRunService(
        null, null, null, null, null, null, null, scriptRules);

    List<Candidate> candidates = runs.candidatesFor(repository(), 10, emptyScan());

    assertEquals(1, candidates.size());
    assertEquals(subject, candidates.getFirst().subject());
    assertEquals("script", candidates.getFirst().reason().get("source"));
    assertEquals("rule-1", candidates.getFirst().reason().get("scriptId"));
  }

  @Test
  void doesNotAppendScriptCandidatesPastTheScanLimit() {
    CleanupSubjectScanner scanner = mock(CleanupSubjectScanner.class);
    Subject existingSubject = subject("asset-a", "path/a");
    Subject scriptSubject = subject("asset-b", "path/b");
    when(scanner.resolvePath(any(), eq("path/b"))).thenReturn(Optional.of(scriptSubject));

    CleanupRuleCandidateService scriptRules = new CleanupRuleCandidateService(
        List.of(rule("rule-1", "path/b")), scanner);
    CleanupRunService runs = new CleanupRunService(
        null, null, null, null, null, null, null, scriptRules);
    ScanResult scan = new ScanResult(
        1,
        false,
        null,
        List.of(new Candidate(existingSubject, Map.of("source", "scanner"))),
        null,
        null,
        null,
        null);

    List<Candidate> candidates = runs.candidatesFor(repository(), 1, scan);

    assertEquals(1, candidates.size());
    assertEquals(existingSubject, candidates.getFirst().subject());
  }

  private static ScanResult emptyScan() {
    return new ScanResult(0, false, null, List.of(), null, null, null, null);
  }

  private static RepositoryRecord repository() {
    return new RepositoryRecord(
        1L,
        "repo",
        RepositoryFormat.RAW,
        RepositoryType.HOSTED,
        "raw",
        true,
        1L,
        null,
        null,
        null,
        null,
        "ALLOW",
        true,
        Map.of());
  }

  private static Subject subject(String identity, String path) {
    return new Subject(
        1L,
        "ASSET",
        identity,
        new byte[0],
        "family",
        "simple",
        "display",
        "1.0",
        path,
        null,
        Instant.EPOCH,
        1,
        10L,
        List.of(1L),
        "token",
        1L);
  }

  private static CleanupRule rule(String id, String path) {
    return new CleanupRule() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public List<CleanupCandidate> candidates(CleanupRequest request) {
        return List.of(new CleanupCandidate("repo", path, "stale", 10));
      }
    };
  }
}
