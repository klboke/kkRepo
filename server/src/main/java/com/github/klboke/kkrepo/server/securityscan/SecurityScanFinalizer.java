package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetPolicyState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanFinding;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanTask;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanWaiver;
import com.github.klboke.kkrepo.security.scan.PolicyEvaluator;
import com.github.klboke.kkrepo.security.scan.PolicyEvaluator.Evaluation;
import com.github.klboke.kkrepo.security.scan.PolicyEvaluator.FindingView;
import com.github.klboke.kkrepo.security.scan.PolicyEvaluator.Input;
import com.github.klboke.kkrepo.security.scan.PolicyEvaluator.Rule;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically publishes findings/state and fences task completion by lease token. */
@Service
public class SecurityScanFinalizer {
  private final SecurityScanDao scans;
  private final RepositoryDao repositories;
  private final SecurityScanAuditService audit;
  private final SecurityScanDocumentPersistence documents;

  public SecurityScanFinalizer(
      SecurityScanDao scans,
      RepositoryDao repositories,
      SecurityScanAuditService audit,
      SecurityScanDocumentPersistence documents) {
    this.scans = scans;
    this.repositories = repositories;
    this.audit = audit;
    this.documents = documents;
  }

  @Transactional
  public ScanRun finalizeRun(
      ScanTask task,
      ScanProfile profile,
      RepositoryScanConfig config,
      String subjectIdentity,
      ScanRun proposedRun,
      List<ScanFinding> proposedFindings) {
    ScanRun run = scans.insertRunOrFindExisting(proposedRun);
    if (run.id().equals(proposedRun.id()) || proposedRun.id() == null) {
      scans.insertFindings(run.id(), proposedFindings);
    }
    scans.associateRun(
        run.id(),
        task.repositoryId(),
        task.assetId(),
        profile.id(),
        task.contentGeneration(),
        Instant.now());
    List<ScanFinding> findings = loadAllFindings(run);
    Instant now = Instant.now();
    boolean ociSubject = scans.findSbom(run.sbomId())
        .map(sbom -> sbom.subjectKind()
            == com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind.OCI_MANIFEST)
        .orElse(false);
    ContextEvaluation primary =
        evaluate(config, task.assetId(), run, findings, now, ociSubject);
    ScanPolicy policy = primary.policy();
    Evaluation evaluation = primary.evaluation();
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("critical", run.criticalCount());
    counts.put("high", run.highCount());
    counts.put("medium", run.mediumCount());
    counts.put("low", run.lowCount());
    counts.put("unknown", run.unknownCount());
    counts.put("fixable", run.fixableFindingCount());
    counts.put("waived", evaluation.waivedFindings());
    Instant staleAt = primary.staleAt();

    AssetSecurityState previous = scans.findAssetState(task.assetId(), profile.id()).orElse(null);
    AssetSecurityState updated = scans.upsertAssetStateIfCurrent(new AssetSecurityState(
        task.assetId(),
        profile.id(),
        task.contentGeneration(),
        PersistenceHashes.sha256(subjectIdentity),
        run.id(),
        run.status(),
        run.scanCompleteness(),
        run.scanCompleteness()
            == com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness.COMPLETE,
        run.maxSeverity(),
        counts,
        policy == null ? null : policy.id(),
        policy == null ? null : policy.revision(),
        evaluation.decision(),
        evaluation.reasonCode(),
        staleAt,
        now,
        0));
    auditPolicyTransition(task.repositoryId(), previous, updated);
    materializePolicyContexts(
        task.repositoryId(),
        task.assetId(),
        task.contentGeneration(),
        profile,
        run,
        findings,
        config,
        primary,
        now,
        ociSubject);
    if (!scans.completeTask(task.id(), task.leaseToken(), now)) {
      throw new LostSecurityScanLeaseException(task.id());
    }
    documents.releaseOwner(task.id());
    return run;
  }

  @Transactional
  public void failCurrentTask(
      ScanTask task, String errorCode, String errorSummary, boolean retryable, Instant nextAttemptAt) {
    if (retryable && task.attempts() < task.maxAttempts()) {
      if (!scans.retryTask(
          task.id(), task.leaseToken(), nextAttemptAt, errorCode, errorSummary, Instant.now())) {
        throw new LostSecurityScanLeaseException(task.id());
      }
      documents.releaseOwner(task.id());
      return;
    }
    if (task.assetId() != null) {
      List<RepositoryScanConfig> contexts =
          applicablePolicyContexts(task.repositoryId(), task.profileId());
      var current = scans.findAssetState(task.assetId(), task.profileId()).orElse(null);
      if (current != null
          && current.contentGeneration() == task.contentGeneration()
          && !contexts.isEmpty()) {
        RepositoryScanConfig primary = contexts.getFirst();
        PolicyDecision decision = primary.failureAction() == PolicyAction.BLOCK
            ? PolicyDecision.BLOCK_SCAN_FAILED : PolicyDecision.ALLOW;
        Instant now = Instant.now();
        AssetSecurityState updated = scans.upsertAssetStateIfCurrent(new AssetSecurityState(
            current.assetId(),
            current.profileId(),
            current.contentGeneration(),
            current.subjectIdentityHash(),
            current.latestScanRunId(),
            ScanState.FAILED,
            current.scanCompleteness(),
            false,
            current.maxSeverity(),
            current.findingCounts(),
            primary.policyId(),
            policyRevision(primary),
            decision,
            errorCode,
            current.staleAt(),
            now,
            current.version()));
        auditPolicyTransition(task.repositoryId(), current, updated);
        materializeFailedPolicyContexts(
            contexts, current, task.contentGeneration(), errorCode, now);
      }
    }
    if (!scans.failTask(
        task.id(), task.leaseToken(), errorCode, errorSummary, Instant.now())) {
      throw new LostSecurityScanLeaseException(task.id());
    }
    documents.releaseOwner(task.id());
  }

  @Transactional
  public boolean cancelCurrentTask(ScanTask task, Instant cancelledAt) {
    boolean cancelled =
        scans.cancelClaimedTask(task.id(), task.leaseToken(), cancelledAt);
    if (cancelled) {
      documents.releaseOwner(task.id());
    }
    return cancelled;
  }

  private List<ScanFinding> loadAllFindings(ScanRun run) {
    List<ScanFinding> findings = new ArrayList<>(Math.max(0, run.findingCount()));
    long cursor = 0;
    while (findings.size() < run.findingCount()) {
      List<ScanFinding> page = scans.listFindings(null, run.id(), null, cursor, 1000);
      if (page.isEmpty()) break;
      findings.addAll(page);
      cursor = page.getLast().id();
    }
    return List.copyOf(findings);
  }

  private static Rule rule(RepositoryScanConfig config, ScanPolicy policy) {
    return new Rule(
        policy == null ? Severity.CRITICAL : policy.blockSeverity(),
        policy != null && policy.onlyFixable(),
        policy != null && policy.blockUnknownSeverity(),
        policy != null && policy.requireCompleteInventory(),
        config.pendingAction(),
        config.failureAction(),
        config.partialAction(),
        policy == null || policy.enabled(),
        policy == null ? List.of() : policy.requiredPlatforms());
  }

  private ContextEvaluation evaluate(
      RepositoryScanConfig config,
      long assetId,
      ScanRun run,
      List<ScanFinding> findings,
      Instant now,
      boolean ociSubject) {
    long waiverRevision = scans.waiverRevision().currentRevision();
    ScanPolicy policy = config.policyId() == null
        ? null : scans.findPolicy(config.policyId()).orElse(null);
    List<ScanWaiver> waivers =
        loadActiveWaivers(config.repositoryId(), assetId, now).stream()
            .filter(waiver -> waiverAppliesToPolicy(waiver, policy))
            .toList();
    Rule rule = rule(config, policy);
    List<FindingView> policyFindings = findings.stream()
        .map(finding -> new FindingView(
            finding.findingKey(),
            finding.severity(),
            !finding.fixedVersions().isEmpty(),
            waived(finding, waivers)))
        .toList();
    Evaluation evaluation = PolicyEvaluator.evaluate(rule, new Input(
        run.status(),
        run.scanCompleteness(),
        run.scanCompleteness()
            == com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness.COMPLETE,
        false,
        policyFindings,
        now,
        ociSubject,
        run.scannedPlatforms()));
    Instant nextWaiverExpiry = waivers.stream()
        .map(ScanWaiver::expiresAt)
        .filter(java.util.Objects::nonNull)
        .min(Instant::compareTo)
        .orElse(null);
    return new ContextEvaluation(
        policy,
        evaluation,
        staleAt(config, policy, run.completedAt()),
        nextWaiverExpiry,
        waiverRevision);
  }

  private void materializePolicyContexts(
      long sourceRepositoryId,
      long assetId,
      long contentGeneration,
      ScanProfile profile,
      ScanRun run,
      List<ScanFinding> findings,
      RepositoryScanConfig primaryConfig,
      ContextEvaluation primaryEvaluation,
      Instant now,
      boolean ociSubject) {
    for (Long repositoryId : policyContextRepositoryIds(sourceRepositoryId)) {
      RepositoryScanConfig context = scans.findRepositoryConfig(repositoryId)
          .filter(RepositoryScanConfig::enabled)
          .filter(config -> config.profileId() == profile.id())
          .filter(config -> appliesToSource(config, sourceRepositoryId))
          .orElse(null);
      if (context == null) continue;
      ContextEvaluation result =
          context.repositoryId() == primaryConfig.repositoryId()
                  && context.configRevision() == primaryConfig.configRevision()
              ? primaryEvaluation
              : evaluate(context, assetId, run, findings, now, ociSubject);
      ScanPolicy policy = result.policy();
      AssetPolicyState previous =
          scans.findAssetPolicyState(assetId, profile.id(), repositoryId).orElse(null);
      AssetPolicyState updated = scans.upsertAssetPolicyStateIfCurrent(new AssetPolicyState(
          assetId,
          profile.id(),
          repositoryId,
          contentGeneration,
          run.id(),
          policy == null ? null : policy.id(),
          policy == null ? null : policy.revision(),
          context.configRevision(),
          result.evaluation().decision(),
          result.evaluation().reasonCode(),
          result.evaluation().waivedFindings(),
          result.staleAt(),
          result.nextWaiverExpiry(),
          now,
          0,
          result.waiverRevision()));
      scans.associateRun(
          run.id(), repositoryId, assetId, profile.id(), contentGeneration, now);
      auditPolicyTransition(repositoryId, previous, updated);
    }
  }

  private Set<Long> policyContextRepositoryIds(long sourceRepositoryId) {
    Set<Long> contexts = new LinkedHashSet<>();
    ArrayDeque<Long> pending = new ArrayDeque<>();
    pending.add(sourceRepositoryId);
    while (!pending.isEmpty()) {
      long repositoryId = pending.removeFirst();
      if (!contexts.add(repositoryId)) continue;
      repositories.listGroupsContaining(repositoryId).stream()
          .map(repository -> repository.id())
          .filter(java.util.Objects::nonNull)
          .forEach(pending::addLast);
    }
    return contexts;
  }

  private List<RepositoryScanConfig> applicablePolicyContexts(
      long sourceRepositoryId, long profileId) {
    return policyContextRepositoryIds(sourceRepositoryId).stream()
        .map(scans::findRepositoryConfig)
        .flatMap(java.util.Optional::stream)
        .filter(RepositoryScanConfig::enabled)
        .filter(config -> config.profileId() == profileId)
        .filter(config -> appliesToSource(config, sourceRepositoryId))
        .toList();
  }

  private boolean appliesToSource(
      RepositoryScanConfig config, long sourceRepositoryId) {
    return repositories.findById(sourceRepositoryId)
        .map(repository ->
            SecurityScanRepositoryScope.appliesToSource(config, repository.type()))
        .orElse(false);
  }

  private void materializeFailedPolicyContexts(
      List<RepositoryScanConfig> contexts,
      AssetSecurityState current,
      long contentGeneration,
      String errorCode,
      Instant now) {
    long waiverRevision = scans.waiverRevision().currentRevision();
    for (RepositoryScanConfig context : contexts) {
      PolicyDecision decision = context.failureAction() == PolicyAction.BLOCK
          ? PolicyDecision.BLOCK_SCAN_FAILED : PolicyDecision.ALLOW;
      AssetPolicyState previous = scans.findAssetPolicyState(
          current.assetId(), current.profileId(), context.repositoryId()).orElse(null);
      AssetPolicyState updated = scans.upsertAssetPolicyStateIfCurrent(new AssetPolicyState(
          current.assetId(),
          current.profileId(),
          context.repositoryId(),
          contentGeneration,
          current.latestScanRunId(),
          context.policyId(),
          policyRevision(context),
          context.configRevision(),
          decision,
          errorCode,
          0,
          null,
          null,
          now,
          previous == null ? 0 : previous.version(),
          waiverRevision));
      auditPolicyTransition(context.repositoryId(), previous, updated);
    }
  }

  private Long policyRevision(RepositoryScanConfig config) {
    return config.policyId() == null
        ? null : scans.findPolicy(config.policyId()).map(ScanPolicy::revision).orElse(null);
  }

  private static boolean waived(ScanFinding finding, List<ScanWaiver> waivers) {
    return waivers.stream()
        .anyMatch(waiver -> SecurityScanWaiverMatcher.matchesFinding(waiver, finding));
  }

  private static boolean waiverAppliesToPolicy(ScanWaiver waiver, ScanPolicy policy) {
    if (!SecurityScanWaiverMatcher.isApproved(waiver)) return false;
    if (waiver.policyId() != null
        && (policy == null || !waiver.policyId().equals(policy.id()))) {
      return false;
    }
    return waiver.policyRevision() == null
        || (policy != null && waiver.policyRevision().equals(policy.revision()));
  }

  private List<ScanWaiver> loadActiveWaivers(
      long repositoryId, long assetId, Instant evaluatedAt) {
    List<ScanWaiver> waivers = new ArrayList<>();
    long cursor = 0;
    while (true) {
      List<ScanWaiver> page =
          scans.listActiveWaivers(repositoryId, assetId, evaluatedAt, cursor, 1000);
      if (page.isEmpty()) break;
      waivers.addAll(page);
      long nextCursor = page.getLast().id();
      if (page.size() < 1000 || nextCursor <= cursor) break;
      cursor = nextCursor;
    }
    return List.copyOf(waivers);
  }

  private static Instant staleAt(
      RepositoryScanConfig config, ScanPolicy policy, Instant completedAt) {
    Long configAge = config.maxResultAgeSeconds();
    Long policyAge =
        policy == null || !policy.enabled() ? null : policy.maxResultAgeSeconds();
    Long age = configAge == null ? policyAge
        : policyAge == null ? configAge : Math.min(configAge, policyAge);
    return age == null || completedAt == null ? null : completedAt.plusSeconds(Math.max(1, age));
  }

  private void auditPolicyTransition(
      long repositoryId, AssetSecurityState previous, AssetSecurityState updated) {
    if (previous == null
        || updated == null
        || previous.policyDecision() == null
        || updated.policyDecision() == null
        || previous.policyDecision().blocked() == updated.policyDecision().blocked()) {
      return;
    }
    audit.recordSystem(
        "POLICY_STATE_CHANGED",
        repositoryId,
        Map.of(
            "assetId", updated.assetId(),
            "profileId", updated.profileId(),
            "scanRunId", updated.latestScanRunId() == null ? 0 : updated.latestScanRunId(),
            "previousDecision", previous.policyDecision().name(),
            "decision", updated.policyDecision().name(),
            "reasonCode", reason(updated.policyReasonCode())));
  }

  private void auditPolicyTransition(
      long repositoryId, AssetPolicyState previous, AssetPolicyState updated) {
    if (previous == null
        || updated == null
        || previous.policyDecision() == null
        || updated.policyDecision() == null
        || previous.policyDecision().blocked() == updated.policyDecision().blocked()) {
      return;
    }
    audit.recordSystem(
        "POLICY_STATE_CHANGED",
        repositoryId,
        Map.of(
            "assetId", updated.assetId(),
            "profileId", updated.profileId(),
            "scanRunId", updated.latestScanRunId() == null ? 0 : updated.latestScanRunId(),
            "previousDecision", previous.policyDecision().name(),
            "decision", updated.policyDecision().name(),
            "reasonCode", reason(updated.policyReasonCode())));
  }

  private static String reason(String value) {
    return value == null || value.isBlank() ? "UNSPECIFIED" : value;
  }

  private record ContextEvaluation(
      ScanPolicy policy,
      Evaluation evaluation,
      Instant staleAt,
      Instant nextWaiverExpiry,
      long waiverRevision) {}

  public static final class LostSecurityScanLeaseException extends RuntimeException {
    LostSecurityScanLeaseException(long taskId) {
      super("Security scan task lease was lost: " + taskId);
    }
  }
}
