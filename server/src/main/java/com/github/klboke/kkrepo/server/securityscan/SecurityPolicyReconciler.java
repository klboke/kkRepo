package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.PolicyEvaluationTarget;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.TaskDraft;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.CandidateDisposition;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanTaskPriorities;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converges per-entry repository policy state after config, policy, group or waiver changes.
 * A shared row-locked work cursor rotates the bounded budget across repository contexts; per-work
 * cursors continue through assets. Deterministic task keys remain a second idempotency boundary.
 */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.security-scanning", name = "enabled", havingValue = "true")
public class SecurityPolicyReconciler {
  static final String WORK_CURSOR = "security_scan_policy_work";
  static final String ASSET_CURSOR_PREFIX = "security_scan_policy_assets:";

  private final SecurityScanDao scans;
  private final RepositoryDao repositories;
  private final AssetDao assets;
  private final SecurityScanCandidateClassifier classifier;
  private final SecurityScanningProperties properties;
  private final MaintenanceCursorDao cursors;

  public SecurityPolicyReconciler(
      SecurityScanDao scans,
      RepositoryDao repositories,
      AssetDao assets,
      SecurityScanCandidateClassifier classifier,
      SecurityScanningProperties properties,
      MaintenanceCursorDao cursors) {
    this.scans = scans;
    this.repositories = repositories;
    this.assets = assets;
    this.classifier = classifier;
    this.properties = properties;
    this.cursors = cursors;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.policy-reconcile-delay:60s}",
      initialDelayString = "${kkrepo.security-scanning.policy-reconcile-initial-delay:20s}")
  @Transactional
  public void runOnce() {
    int batchSize = properties.getWorker().getSnapshotRematchBatchSize();
    int maxBatches = properties.getWorker().getSnapshotRematchMaxBatches();
    int remaining = batchSize * maxBatches;
    int remainingVisits = maxBatches;
    Instant now = Instant.now();
    cursors.ensureCursor(WORK_CURSOR);
    OptionalLong lockedWorkCursor = cursors.tryLockLastSeenId(WORK_CURSOR);
    if (lockedWorkCursor.isEmpty()) {
      return;
    }

    List<PolicyWork> work = policyWork();
    if (work.isEmpty()) {
      updateCursor(WORK_CURSOR, 0);
      return;
    }
    long sequence = lockedWorkCursor.getAsLong();
    int start = (int) Math.floorMod(sequence, (long) work.size());
    int visited = 0;
    while (visited < work.size() && remaining > 0 && remainingVisits > 0) {
      PolicyWork current = work.get((start + visited) % work.size());
      remaining -= reconcileWork(current, now, Math.min(batchSize, remaining));
      visited++;
      remainingVisits--;
    }
    long nextSequence = sequence > Long.MAX_VALUE - visited ? 0 : sequence + visited;
    updateCursor(WORK_CURSOR, nextSequence);
  }

  private List<PolicyWork> policyWork() {
    List<RepositoryRecord> repositoryRows = repositories.list();
    Map<Long, RepositoryRecord> repositoriesById = new java.util.LinkedHashMap<>();
    Map<String, RepositoryRecord> repositoriesByName = new java.util.LinkedHashMap<>();
    for (RepositoryRecord repository : repositoryRows) {
      if (repository.id() != null) {
        repositoriesById.put(repository.id(), repository);
      }
      if (repository.name() != null) {
        repositoriesByName.put(repository.name(), repository);
      }
    }
    Map<Long, RepositoryScanConfig> configs = new java.util.LinkedHashMap<>();
    if (!repositoriesById.isEmpty()) {
      for (RepositoryScanConfig config :
          scans.findRepositoryConfigs(List.copyOf(repositoriesById.keySet()))) {
        configs.put(config.repositoryId(), config);
      }
    }
    Map<Long, ScanProfile> profiles = new java.util.LinkedHashMap<>();
    for (ScanProfile profile : scans.listProfiles()) {
      if (profile.id() != null) {
        profiles.put(profile.id(), profile);
      }
    }
    Map<Long, ScanPolicy> policies = new java.util.LinkedHashMap<>();
    for (ScanPolicy policy : scans.listPolicies()) {
      if (policy.id() != null) {
        policies.put(policy.id(), policy);
      }
    }
    Map<Long, List<String>> groupMembers = repositories.listAllGroupMembers();
    List<PolicyWork> work = new ArrayList<>();
    for (RepositoryRecord contextRepository : repositoryRows) {
      if (contextRepository.id() == null) {
        continue;
      }
      RepositoryScanConfig config = configs.get(contextRepository.id());
      if (config == null || !config.enabled()) {
        continue;
      }
      ScanProfile profile = profiles.get(config.profileId());
      if (profile == null || !profile.enabled()) {
        continue;
      }
      ScanPolicy policy = config.policyId() == null
          ? null
          : policies.get(config.policyId());
      if (config.policyId() != null && policy == null) {
        continue;
      }
      for (RepositoryRecord sourceRepository :
          sourceRepositories(contextRepository, repositoriesByName, groupMembers)) {
        if (sourceRepository.id() != null
            && SecurityScanRepositoryScope.appliesToSource(
                config, sourceRepository.type())) {
          work.add(new PolicyWork(
              contextRepository, sourceRepository, config, profile, policy));
        }
      }
    }
    work.sort(Comparator
        .comparingLong((PolicyWork item) -> item.context().id())
        .thenComparingLong(item -> item.source().id()));
    return List.copyOf(work);
  }

  private int reconcileWork(PolicyWork work, Instant now, int limit) {
    String cursorName = assetCursorName(work.context().id(), work.source().id());
    cursors.ensureCursor(cursorName);
    OptionalLong locked = cursors.tryLockLastSeenId(cursorName);
    if (locked.isEmpty()) {
      return 0;
    }
    long cursor = locked.getAsLong();
    ScanPolicy policy = work.policy();
    List<PolicyEvaluationTarget> targets = scans.listPolicyEvaluationTargets(
        work.source().id(),
        work.context().id(),
        work.profile().id(),
        work.config().configRevision(),
        policy == null ? null : policy.id(),
        policy == null ? null : policy.revision(),
        cursor,
        now,
        limit);
    if (targets.isEmpty()) {
      // Wrap only for the next visit so pending work before this cursor cannot consume the same
      // repository's entire share repeatedly.
      updateCursor(cursorName, 0);
      return 0;
    }
    for (PolicyEvaluationTarget target : targets) {
      reconcile(work.config(), work.profile(), target, now);
      cursor = target.assetId();
    }
    updateCursor(cursorName, cursor);
    return targets.size();
  }

  private void updateCursor(String cursorName, long value) {
    if (cursors.updateLastSeenId(cursorName, value) != 1) {
      throw new IllegalStateException("Security policy reconciliation cursor disappeared while locked");
    }
  }

  static String assetCursorName(long contextRepositoryId, long sourceRepositoryId) {
    return ASSET_CURSOR_PREFIX + contextRepositoryId + ":" + sourceRepositoryId;
  }

  void reconcile(
      RepositoryScanConfig context,
      ScanProfile profile,
      PolicyEvaluationTarget target,
      Instant now) {
    AssetDao.AssetWithBlob content = assets.findAssetWithBlobById(target.assetId()).orElse(null);
    if (content == null
        || content.blob() == null
        || content.blob().id() == null
        || content.blob().sha256() == null) {
      return;
    }
    var candidate = scans.findCandidate(target.assetId()).orElse(null);
    if (candidate == null) {
      scans.markRepositoryAssetsForBackfill(
          target.sourceRepositoryId(), Math.max(0, target.assetId() - 1), 1);
      return;
    }
    if (candidate.contentGeneration() != target.contentGeneration()
        || !Objects.equals(candidate.assetBlobId(), content.blob().id())) {
      return;
    }
    var classification = classifier.classify(content.asset(), content.blob(), profile);
    if (classification.disposition() != CandidateDisposition.SCANNABLE) {
      materializeTerminal(profile, target, classification.disposition(), classification.reasonCode(), now);
      return;
    }

    boolean reusableRun = target.scanState() == ScanState.COMPLETE
        && target.latestScanRunId() != null
        && target.stateContentGeneration() != null
        && target.stateContentGeneration() == target.contentGeneration();
    boolean resultAgeExpired =
        target.staleAt() != null && !target.staleAt().isAfter(now);
    ScanStage stage = resultAgeExpired
        ? ScanStage.MATCH_ONLY
        : reusableRun ? ScanStage.POLICY_ONLY : ScanStage.CATALOG_AND_MATCH;
    String requestUuid = requestUuid(context, profile, target, stage);
    String subjectKey = "sha256:" + content.blob().sha256();
    scans.createTask(new TaskDraft(
        target.sourceRepositoryId(),
        target.assetId(),
        classification.subjectKind(),
        subjectKey,
        target.contentGeneration(),
        profile.id(),
        profile.revision(),
        null, // only vulnerability-DB rematch tasks are pinned to a requested snapshot
        stage,
        resultAgeExpired ? RequestReason.MAX_AGE_EXPIRED : RequestReason.POLICY_CHANGED,
        ScanTaskPriorities.POLICY,
        properties.getWorker().getMaxAttempts(),
        "security-policy-reconciler",
        requestUuid,
        "policy:" + requestUuid,
        now));
  }

  private void materializeTerminal(
      ScanProfile profile,
      PolicyEvaluationTarget target,
      CandidateDisposition disposition,
      String reasonCode,
      Instant now) {
    ScanState state = disposition == CandidateDisposition.NOT_APPLICABLE
        ? ScanState.NOT_APPLICABLE : ScanState.FAILED;
    ScanCompleteness completeness = disposition == CandidateDisposition.NOT_APPLICABLE
        ? ScanCompleteness.COMPLETE : ScanCompleteness.UNKNOWN;
    scans.upsertAssetStateIfCurrent(new AssetSecurityState(
        target.assetId(),
        profile.id(),
        target.contentGeneration(),
        PersistenceHashes.sha256("asset:" + target.assetId() + ":" + target.contentGeneration()),
        null,
        state,
        completeness,
        disposition == CandidateDisposition.NOT_APPLICABLE,
        Severity.UNKNOWN,
        Map.of(),
        null,
        null,
        PolicyDecision.ALLOW,
        reasonCode,
        null,
        now,
        0));
  }

  private List<RepositoryRecord> sourceRepositories(
      RepositoryRecord context,
      Map<String, RepositoryRecord> repositoriesByName,
      Map<Long, List<String>> groupMembers) {
    Map<Long, RepositoryRecord> sources = new java.util.LinkedHashMap<>();
    collectSources(
        context, repositoriesByName, groupMembers, sources, new LinkedHashSet<>());
    return List.copyOf(sources.values());
  }

  private void collectSources(
      RepositoryRecord repository,
      Map<String, RepositoryRecord> repositoriesByName,
      Map<Long, List<String>> groupMembers,
      Map<Long, RepositoryRecord> sources,
      Set<Long> visited) {
    if (repository == null || repository.id() == null || !visited.add(repository.id())) return;
    if (repository.type() != RepositoryType.GROUP) {
      sources.put(repository.id(), repository);
      return;
    }
    for (String memberName : groupMembers.getOrDefault(repository.id(), List.of())) {
      collectSources(
          repositoriesByName.get(memberName),
          repositoriesByName,
          groupMembers,
          sources,
          visited);
    }
  }

  private static String requestUuid(
      RepositoryScanConfig context,
      ScanProfile profile,
      PolicyEvaluationTarget target,
      ScanStage stage) {
    List<String> parts = new ArrayList<>();
    parts.add("policy");
    parts.add(Long.toString(context.repositoryId()));
    parts.add(Long.toString(context.configRevision()));
    parts.add(Long.toString(profile.id()));
    parts.add(Long.toString(profile.revision()));
    parts.add(Long.toString(target.sourceRepositoryId()));
    parts.add(Long.toString(target.assetId()));
    parts.add(Long.toString(target.contentGeneration()));
    parts.add(Long.toString(target.policyStateVersion()));
    parts.add(Long.toString(target.waiverRevision()));
    parts.add(target.staleAt() == null ? "" : target.staleAt().toString());
    parts.add(target.nextWaiverExpiry() == null ? "" : target.nextWaiverExpiry().toString());
    parts.add(stage.name());
    return UUID.nameUUIDFromBytes(
        String.join("\0", parts).getBytes(StandardCharsets.UTF_8)).toString();
  }

  private record PolicyWork(
      RepositoryRecord context,
      RepositoryRecord source,
      RepositoryScanConfig config,
      ScanProfile profile,
      ScanPolicy policy) {}
}
