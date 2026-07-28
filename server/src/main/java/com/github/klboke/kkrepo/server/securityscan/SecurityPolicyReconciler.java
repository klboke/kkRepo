package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Converges per-entry repository policy state after config, policy, group or waiver changes.
 * Duplicate schedulers are harmless because every task uses a deterministic database dedupe key.
 */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.security-scanning", name = "enabled", havingValue = "true")
public class SecurityPolicyReconciler {
  private static final Logger log = LoggerFactory.getLogger(SecurityPolicyReconciler.class);

  private final SecurityScanDao scans;
  private final RepositoryDao repositories;
  private final AssetDao assets;
  private final SecurityScanCandidateClassifier classifier;
  private final SecurityScanningProperties properties;

  public SecurityPolicyReconciler(
      SecurityScanDao scans,
      RepositoryDao repositories,
      AssetDao assets,
      SecurityScanCandidateClassifier classifier,
      SecurityScanningProperties properties) {
    this.scans = scans;
    this.repositories = repositories;
    this.assets = assets;
    this.classifier = classifier;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.policy-reconcile-delay:60s}",
      initialDelayString = "${kkrepo.security-scanning.policy-reconcile-initial-delay:20s}")
  public void runOnce() {
    int batchSize = properties.getWorker().getSnapshotRematchBatchSize();
    int remaining =
        batchSize * properties.getWorker().getSnapshotRematchMaxBatches();
    Instant now = Instant.now();
    try {
      for (RepositoryRecord contextRepository : repositories.list()) {
        if (remaining <= 0 || contextRepository.id() == null) break;
        RepositoryScanConfig config = scans.findRepositoryConfig(contextRepository.id())
            .filter(RepositoryScanConfig::enabled)
            .orElse(null);
        if (config == null) continue;
        ScanProfile profile = scans.findProfile(config.profileId())
            .filter(ScanProfile::enabled)
            .orElse(null);
        if (profile == null) continue;
        ScanPolicy policy = config.policyId() == null
            ? null : scans.findPolicy(config.policyId()).orElse(null);
        if (config.policyId() != null && policy == null) continue;
        for (RepositoryRecord sourceRepository : sourceRepositories(contextRepository)) {
          if (sourceRepository.id() == null
              || !SecurityScanRepositoryScope.appliesToSource(
                  config, sourceRepository.type())) {
            continue;
          }
          long sourceRepositoryId = sourceRepository.id();
          long cursor = 0;
          while (remaining > 0) {
            int limit = Math.min(batchSize, remaining);
            List<PolicyEvaluationTarget> targets = scans.listPolicyEvaluationTargets(
                sourceRepositoryId,
                contextRepository.id(),
                profile.id(),
                config.configRevision(),
                policy == null ? null : policy.id(),
                policy == null ? null : policy.revision(),
                cursor,
                now,
                limit);
            if (targets.isEmpty()) break;
            for (PolicyEvaluationTarget target : targets) {
              cursor = target.assetId();
              reconcile(config, profile, target, now);
              remaining--;
              if (remaining <= 0) break;
            }
            if (targets.size() < limit) break;
          }
          if (remaining <= 0) break;
        }
      }
    } catch (RuntimeException e) {
      log.warn("Security policy reconciliation failed", e);
    }
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
    ScanStage stage = reusableRun ? ScanStage.POLICY_ONLY : ScanStage.CATALOG_AND_MATCH;
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
        stage == ScanStage.POLICY_ONLY
            ? null : scans.latestScannerSnapshot().map(SecurityScanDao.ScannerSnapshot::id).orElse(null),
        stage,
        RequestReason.POLICY_CHANGED,
        20,
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

  private List<RepositoryRecord> sourceRepositories(RepositoryRecord context) {
    Map<Long, RepositoryRecord> sources = new java.util.LinkedHashMap<>();
    collectSources(context, sources, new LinkedHashSet<>());
    return List.copyOf(sources.values());
  }

  private void collectSources(
      RepositoryRecord repository,
      Map<Long, RepositoryRecord> sources,
      Set<Long> visited) {
    if (repository == null || repository.id() == null || !visited.add(repository.id())) return;
    if (repository.type() != RepositoryType.GROUP) {
      sources.put(repository.id(), repository);
      return;
    }
    for (RepositoryRecord member : repositories.listMembers(repository.id())) {
      collectSources(member, sources, visited);
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
    parts.add(target.nextWaiverExpiry() == null ? "" : target.nextWaiverExpiry().toString());
    parts.add(stage.name());
    return UUID.nameUUIDFromBytes(
        String.join("\0", parts).getBytes(StandardCharsets.UTF_8)).toString();
  }
}
