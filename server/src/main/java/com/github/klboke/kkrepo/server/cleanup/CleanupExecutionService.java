package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.ClaimedRunRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupProtection;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService.CleanupDeleteSubject;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Candidate;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Subject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Performs the final native lock, fence, usage, protection and content checks with deletion. */
@Service
public class CleanupExecutionService {
  private final CleanupPolicyDao cleanupDao;
  private final RepositoryDao repositoryDao;
  private final CleanupSubjectScanner scanner;
  private final RepositoryContentDeletionService deletionService;
  private final Clock clock;

  @Autowired
  public CleanupExecutionService(
      CleanupPolicyDao cleanupDao,
      RepositoryDao repositoryDao,
      CleanupSubjectScanner scanner,
      RepositoryContentDeletionService deletionService) {
    this(cleanupDao, repositoryDao, scanner, deletionService, Clock.systemUTC());
  }

  CleanupExecutionService(
      CleanupPolicyDao cleanupDao,
      RepositoryDao repositoryDao,
      CleanupSubjectScanner scanner,
      RepositoryContentDeletionService deletionService,
      Clock clock) {
    this.cleanupDao = cleanupDao;
    this.repositoryDao = repositoryDao;
    this.scanner = scanner;
    this.deletionService = deletionService;
    this.clock = clock;
  }

  @Transactional
  public ExecutionResult apply(
      ClaimedRunRepository claim,
      CleanupRun run,
      Candidate candidate,
      String actorId) {
    return applyBatch(claim, run, List.of(candidate), actorId).getFirst();
  }

  /** Revalidates and deletes one bounded protocol family in a single fenced transaction. */
  @Transactional
  public List<ExecutionResult> applyBatch(
      ClaimedRunRepository claim,
      CleanupRun run,
      List<Candidate> candidates,
      String actorId) {
    if (candidates == null || candidates.isEmpty()) return List.of();
    Instant now = databaseNow();
    if (!cleanupDao.lockCurrentRunRepositoryLease(
        claim.id(), claim.leaseToken(), claim.fencingToken(), now)) {
      throw new CleanupFenceLostException(claim.id());
    }
    if (cleanupDao.isRunCancellationRequested(run.id())) {
      return repeated(
          candidates.size(),
          new ExecutionResult("CANCELLED", 0, null, Map.of("cancelled", true)));
    }
    if (cleanupDao.findPolicy(run.policyId()).isEmpty()) {
      return repeated(candidates.size(), stale("policy is no longer active"));
    }
    boolean stillTargeted = cleanupDao.listTargets(run.policyId()).stream()
        .anyMatch(target -> target.id() == claim.repositoryId());
    if (!stillTargeted) {
      return repeated(candidates.size(), stale("repository is no longer targeted by the policy"));
    }

    RepositoryRecord repository = repositoryDao.findById(claim.repositoryId()).orElse(null);
    if (repository == null || !repository.online() || repository.format() != claim.format()) {
      return repeated(candidates.size(), stale("repository state changed"));
    }
    List<ExecutionResult> results = new ArrayList<>(
        java.util.Collections.nCopies(candidates.size(), null));
    List<CleanupDeleteSubject> deletions = new ArrayList<>();
    List<Integer> deletionIndexes = new ArrayList<>();
    for (int index = 0; index < candidates.size(); index++) {
      Subject expected = candidates.get(index).subject();
      Subject current = scanner.resolveLocked(
          repository, expected.kind(), expected.identityId(), expected.deletePath()).orElse(null);
      if (current == null) {
        results.set(index,
            new ExecutionResult("SKIPPED_MISSING", 0, null, Map.of("missing", true)));
        continue;
      }
      if (expected.contentToken() != null
          && !Objects.equals(expected.contentToken(), current.contentToken())) {
        results.set(index, stale("content token changed"));
        continue;
      }
      if (expected.usageRevision() != current.usageRevision()) {
        results.set(index, stale("usage revision changed"));
        continue;
      }
      CleanupProtection protection = cleanupDao.findActiveProtection(
          repository.id(),
          current.kind(),
          current.key(),
          current.keyHash(),
          now).orElse(null);
      if (protection != null) {
        results.set(index, new ExecutionResult(
            "KEEP_PROTECTED",
            0,
            protection.id(),
            Map.of(
                "protectionId", protection.id(),
                "protectionReason", protection.reason())));
        continue;
      }
      deletionIndexes.add(index);
      deletions.add(new CleanupDeleteSubject(
          current.kind(), current.identityId(), current.deletePath()));
    }
    List<Integer> deletedCounts = deletionService.deleteBatchForCleanup(
        repository.name(), deletions, actorId);
    if (deletedCounts.size() != deletions.size()) {
      throw new IllegalStateException("cleanup deletion batch returned an invalid result count");
    }
    for (int deletionIndex = 0; deletionIndex < deletionIndexes.size(); deletionIndex++) {
      int deletedAssets = Math.max(0, deletedCounts.get(deletionIndex));
      results.set(deletionIndexes.get(deletionIndex), new ExecutionResult(
          deletedAssets > 0 ? "DELETED" : "SKIPPED_MISSING",
          deletedAssets,
          null,
          Map.of("deletedAssets", deletedAssets)));
    }
    return List.copyOf(results);
  }

  private static List<ExecutionResult> repeated(int count, ExecutionResult result) {
    return List.copyOf(java.util.Collections.nCopies(count, result));
  }

  private static ExecutionResult stale(String reason) {
    return new ExecutionResult("SKIPPED_STALE", 0, null, Map.of("staleReason", reason));
  }

  private Instant databaseNow() {
    Instant value = cleanupDao.currentTime();
    return value == null ? clock.instant() : value;
  }

  public record ExecutionResult(
      String decision, int deletedAssets, Long protectionId, Map<String, Object> reason) {
  }
}
