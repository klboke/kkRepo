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
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Subject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CleanupRuleCandidateServiceTest {

  @Test
  void resolvesScriptPathsAndAddsAuditMetadata() {
    CleanupSubjectScanner scanner = mock(CleanupSubjectScanner.class);
    Subject subject = subject("asset-a", "path/a");
    when(scanner.resolvePath(any(), eq("path/a"))).thenReturn(Optional.of(subject));
    when(scanner.resolvePath(any(), eq("path/b"))).thenReturn(Optional.empty());

    CleanupRule rule = rule("rule-1", List.of(
        new CleanupCandidate("repo", "path/a", "stale", 10),
        new CleanupCandidate("repo", "path/b", "missing", 20)));
    CleanupRuleCandidateService service =
        new CleanupRuleCandidateService(List.of(rule), scanner);

    List<Candidate> candidates = service.candidates(repository(), 10, List.of());

    assertEquals(1, candidates.size());
    assertEquals(subject, candidates.getFirst().subject());
    assertEquals("script", candidates.getFirst().reason().get("source"));
    assertEquals("rule-1", candidates.getFirst().reason().get("scriptId"));
    assertEquals("stale", candidates.getFirst().reason().get("reason"));
  }

  @Test
  void enforcesLimitAndExistingCandidates() {
    CleanupSubjectScanner scanner = mock(CleanupSubjectScanner.class);
    Subject existingSubject = subject("asset-a", "path/a");
    Subject newSubject = subject("asset-b", "path/b");
    when(scanner.resolvePath(any(), eq("path/a"))).thenReturn(Optional.of(existingSubject));
    when(scanner.resolvePath(any(), eq("path/b"))).thenReturn(Optional.of(newSubject));

    CleanupRule rule = rule("rule-1", List.of(
        new CleanupCandidate("repo", "path/a", "duplicate", 10),
        new CleanupCandidate("repo", "path/b", "new", 20),
        new CleanupCandidate("other", "path/c", "wrong repo", 30)));
    CleanupRuleCandidateService service =
        new CleanupRuleCandidateService(List.of(rule), scanner);
    Candidate existing = new Candidate(existingSubject, Map.of("source", "scanner"));

    List<Candidate> candidates =
        service.candidates(repository(), 1, List.of(existing));

    assertEquals(1, candidates.size());
    assertEquals("path/b", candidates.getFirst().subject().deletePath());
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

  private static CleanupRule rule(String id, List<CleanupCandidate> candidates) {
    return new CleanupRule() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public List<CleanupCandidate> candidates(CleanupRequest request) {
        return candidates;
      }
    };
  }
}
