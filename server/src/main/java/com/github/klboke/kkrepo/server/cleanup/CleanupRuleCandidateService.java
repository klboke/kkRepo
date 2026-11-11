package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.plugin.api.CleanupCandidate;
import com.github.klboke.kkrepo.plugin.api.CleanupRequest;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Candidate;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Subject;
import com.github.klboke.kkrepo.server.scripts.ScriptRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Turns script-proposed paths into cleanup candidates. Scripts only propose; the existing scanner
 * re-resolves and the executor still performs lock, policy, protection, usage and deletion checks.
 */
@Service
public class CleanupRuleCandidateService {
  private static final Logger log = LoggerFactory.getLogger(CleanupRuleCandidateService.class);

  private final List<CleanupRule> rules;
  private final CleanupSubjectScanner scanner;

  @Autowired
  public CleanupRuleCandidateService(
      ObjectProvider<CleanupRule> rules,
      ObjectProvider<ScriptRegistry> scripts,
      CleanupSubjectScanner scanner) {
    this(combine(rules, scripts), scanner);
  }

  private static List<CleanupRule> combine(
      ObjectProvider<CleanupRule> rules, ObjectProvider<ScriptRegistry> scripts) {
    List<CleanupRule> combined = new ArrayList<>(rules.orderedStream().toList());
    scripts.ifAvailable(registry -> combined.addAll(registry.cleanupRules()));
    return combined;
  }

  CleanupRuleCandidateService(List<CleanupRule> rules, CleanupSubjectScanner scanner) {
    this.rules = rules == null ? List.of() : List.copyOf(rules);
    this.scanner = scanner;
  }

  public List<Candidate> candidates(
      RepositoryRecord repository, int limit, List<Candidate> existing) {
    if (rules.isEmpty() || limit <= 0) {
      return List.of();
    }
    Set<String> seen = new HashSet<>();
    if (existing != null) {
      existing.forEach(candidate -> seen.add(key(candidate.subject())));
    }
    List<Candidate> result = new ArrayList<>();
    for (CleanupRule rule : rules) {
      if (result.size() >= limit) break;
      try {
        List<CleanupCandidate> proposed = rule.candidates(
            new CleanupRequest(repository.name(), "", 0, limit - result.size()));
        if (proposed == null) continue;
        for (CleanupCandidate candidate : proposed) {
          if (result.size() >= limit) break;
          if (candidate == null || !repository.name().equals(candidate.repository())) continue;
          scanner.resolvePath(repository, candidate.path()).ifPresent(subject -> {
            if (seen.add(key(subject))) {
              Map<String, Object> reason = new LinkedHashMap<>();
              reason.put("source", "script");
              reason.put("scriptId", rule.id());
              reason.put("reason", candidate.reason());
              result.add(new Candidate(subject, reason));
            }
          });
        }
      } catch (RuntimeException failure) {
        log.warn(
            "Cleanup rule {} failed for repository {}; built-in candidates remain authoritative",
            rule.id(),
            repository.name(),
            failure);
      }
    }
    return List.copyOf(result);
  }

  static String key(Subject subject) {
    return subject.kind() + ":" + subject.identityId() + ":" + subject.deletePath();
  }
}
