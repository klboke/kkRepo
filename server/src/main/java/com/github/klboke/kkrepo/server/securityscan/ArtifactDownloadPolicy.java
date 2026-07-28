package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetPolicyState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.DownloadPolicySnapshot;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.CandidateDisposition;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.server.docker.DockerAuthService;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Evaluates the concrete member asset after repository resolution and before any response body is
 * opened. All authoritative inputs are loaded in one database snapshot query; no node-local cache
 * is used for blocking.
 */
@Service
public class ArtifactDownloadPolicy {
  public static final String ENTRY_REPOSITORY_ID_ATTRIBUTE =
      ArtifactDownloadPolicy.class.getName() + ".ENTRY_REPOSITORY_ID";
  private static final int DEFAULT_RETRY_AFTER_SECONDS = 30;

  private final SecurityScanDao scans;
  private final SecurityScanCandidateClassifier classifier;
  private final SecurityScanningProperties properties;
  private final SecurityScanMetrics metrics;

  public ArtifactDownloadPolicy(
      SecurityScanDao scans,
      SecurityScanCandidateClassifier classifier,
      SecurityScanningProperties properties,
      SecurityScanMetrics metrics) {
    this.scans = scans;
    this.classifier = classifier;
    this.properties = properties;
    this.metrics = metrics;
  }

  public Decision beforeRead(long assetId) {
    return beforeRead(assetId, requestEntryRepositoryId());
  }

  public Decision beforeRead(long assetId, Long entryRepositoryId) {
    if (!properties.isEnabled() || internalScannerRequest()) return Decision.allow();
    return evaluateSnapshots(
        () -> scans.findDownloadPolicySnapshots(assetId, entryRepositoryId),
        false);
  }

  /**
   * Evaluates a bounded set of manifest assets that authorize one shared Docker blob.
   *
   * <p>The DAO loads the batch in one statement; the strictest applicable manifest decision wins.
   */
  public Decision beforeReadAll(List<Long> assetIds) {
    return beforeReadAll(assetIds, false);
  }

  /**
   * Evaluates a bounded shared-blob reference set.
   *
   * <p>If {@code referencesTruncated} is true, an applicable enforcement configuration fails
   * closed instead of issuing additional hot-path queries whose count depends on tag cardinality.
   */
  public Decision beforeReadAll(List<Long> assetIds, boolean referencesTruncated) {
    if (!properties.isEnabled() || internalScannerRequest()) return Decision.allow();
    List<Long> ids = assetIds == null
        ? List.of()
        : assetIds.stream()
            .filter(java.util.Objects::nonNull)
            .filter(id -> id > 0)
            .distinct()
            .toList();
    if (ids.isEmpty()) return Decision.allow();
    boolean truncated =
        referencesTruncated || ids.size() > SecurityScanDao.MAX_DOWNLOAD_POLICY_BATCH;
    if (ids.size() > SecurityScanDao.MAX_DOWNLOAD_POLICY_BATCH) {
      ids = ids.subList(0, SecurityScanDao.MAX_DOWNLOAD_POLICY_BATCH);
    }
    List<Long> boundedIds = ids;
    return evaluateSnapshots(
        () -> scans.findDownloadPolicySnapshots(boundedIds, requestEntryRepositoryId()),
        truncated);
  }

  private Decision evaluateSnapshots(
      java.util.function.Supplier<List<DownloadPolicySnapshot>> snapshotLoader,
      boolean referencesTruncated) {
    Timer.Sample sample = metrics.start();
    String format = null;
    String outcome = "allow";
    try {
      List<DownloadPolicySnapshot> snapshots = snapshotLoader.get();
      for (DownloadPolicySnapshot snapshot : snapshots) {
        if (format == null && snapshot.format() != null) {
          format = snapshot.format().name();
        }
      }
      boolean enforcedOverflow = referencesTruncated
          && snapshots.stream()
              .map(DownloadPolicySnapshot::config)
              .anyMatch(config ->
                  config.enabled() && config.enforcementMode() == EnforcementMode.ENFORCE);
      if (enforcedOverflow) {
        metrics.recordPolicy(format, PolicyDecision.BLOCK_PENDING, true);
        outcome = "block";
        throw new ArtifactPolicyException(
            PolicyDecision.BLOCK_PENDING, DEFAULT_RETRY_AFTER_SECONDS);
      }
      List<Evaluation> evaluations = new ArrayList<>(snapshots.size());
      for (DownloadPolicySnapshot snapshot : snapshots) {
        RepositoryScanConfig config = snapshot.config();
        if (!config.enabled()) continue;
        ScanProfile profile = snapshot.profile();
        if (profile == null || !profile.enabled()) {
          evaluations.add(action(
              config, config.failureAction(), PolicyDecision.BLOCK_SCAN_FAILED));
          continue;
        }
        var classification = classifier.classify(
            snapshot.format(),
            snapshot.path(),
            snapshot.kind(),
            snapshot.contentType(),
            snapshot.blobSize(),
            profile);
        if (classification.disposition() == CandidateDisposition.NOT_APPLICABLE) {
          evaluations.add(new Evaluation(config, PolicyDecision.ALLOW, false));
          continue;
        }
        if (classification.disposition() == CandidateDisposition.REJECTED_BY_LIMIT) {
          evaluations.add(action(
              config, config.failureAction(), PolicyDecision.BLOCK_SCAN_FAILED));
          continue;
        }
        evaluations.add(evaluate(snapshot));
      }
      Evaluation strictest = evaluations.stream()
          .max(Comparator.comparingInt(ArtifactDownloadPolicy::rank))
          .orElse(null);
      if (strictest == null) return Decision.allow();
      boolean enforce = strictest.config().enforcementMode() == EnforcementMode.ENFORCE;
      metrics.recordPolicy(format, strictest.decision(), enforce);
      if (enforce && strictest.blocked()) {
        outcome = "block";
        throw new ArtifactPolicyException(
            strictest.decision(), DEFAULT_RETRY_AFTER_SECONDS);
      }
      outcome = !enforce && strictest.blocked() ? "shadow" : "allow";
      return new Decision(strictest.decision(), enforce, !enforce && strictest.blocked());
    } catch (RuntimeException e) {
      if (!"block".equals(outcome)) outcome = "error";
      throw e;
    } finally {
      metrics.recordPolicyEvaluation(format, outcome, sample);
    }
  }

  private Evaluation evaluate(DownloadPolicySnapshot snapshot) {
    RepositoryScanConfig config = snapshot.config();
    ScanCandidate candidate = snapshot.candidate();
    AssetSecurityState state = snapshot.assetState();
    if (candidate == null || state == null
        || state.contentGeneration() != candidate.contentGeneration()) {
      return action(config, config.pendingAction(), PolicyDecision.BLOCK_PENDING);
    }
    if (state.scanState() == ScanState.NOT_APPLICABLE) {
      return new Evaluation(config, PolicyDecision.ALLOW, false);
    }
    return switch (state.scanState()) {
      case PENDING, RUNNING, STALE ->
          action(config, config.pendingAction(), PolicyDecision.BLOCK_PENDING);
      case FAILED, CANCELLED ->
          action(config, config.failureAction(), PolicyDecision.BLOCK_SCAN_FAILED);
      case PARTIAL ->
          action(config, config.partialAction(), PolicyDecision.BLOCK_PARTIAL);
      case COMPLETE -> evaluateComplete(snapshot, candidate, state);
      case NOT_APPLICABLE -> new Evaluation(config, PolicyDecision.ALLOW, false);
    };
  }

  private Evaluation evaluateComplete(
      DownloadPolicySnapshot snapshot,
      ScanCandidate candidate,
      AssetSecurityState state) {
    RepositoryScanConfig config = snapshot.config();
    Instant now = Instant.now();
    ScanPolicy currentPolicy = snapshot.policy();
    AssetPolicyState policyState = snapshot.policyState();
    boolean policyMismatch = config.policyId() == null
        ? policyState != null
            && (policyState.policyId() != null || policyState.policyRevision() != null)
        : currentPolicy == null
            || policyState == null
            || policyState.policyId() == null
            || !policyState.policyId().equals(currentPolicy.id())
            || policyState.policyRevision() == null
            || policyState.policyRevision() != currentPolicy.revision();
    if (policyState == null
        || policyState.contentGeneration() != candidate.contentGeneration()
        || !java.util.Objects.equals(policyState.latestScanRunId(), state.latestScanRunId())
        || policyState.configRevision() != config.configRevision()
        || policyState.waiverRevision() < snapshot.requiredWaiverRevision()
        || policyMismatch
        || (policyState.staleAt() != null && !policyState.staleAt().isAfter(now))
        || (policyState.nextWaiverExpiry() != null
            && !policyState.nextWaiverExpiry().isAfter(now))) {
      return action(config, config.pendingAction(), PolicyDecision.BLOCK_PENDING);
    }
    PolicyDecision decision = policyState.policyDecision() == null
        ? PolicyDecision.ALLOW : policyState.policyDecision();
    return new Evaluation(config, decision, decision.blocked());
  }

  private static Evaluation action(
      RepositoryScanConfig config, PolicyAction action, PolicyDecision blockedDecision) {
    return action == PolicyAction.BLOCK
        ? new Evaluation(config, blockedDecision, true)
        : new Evaluation(config, PolicyDecision.ALLOW, false);
  }

  private static int rank(Evaluation evaluation) {
    if (!evaluation.blocked()) return 0;
    int enforcementRank =
        evaluation.config().enforcementMode() == EnforcementMode.ENFORCE ? 100 : 0;
    return enforcementRank + switch (evaluation.decision()) {
      case BLOCK_PENDING -> 1;
      case BLOCK_PARTIAL -> 2;
      case BLOCK_SCAN_FAILED -> 3;
      case BLOCK_VULNERABILITY -> 4;
      case ALLOW -> 0;
    };
  }

  private Long requestEntryRepositoryId() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
      return null;
    }
    Object repositoryId = attrs.getRequest().getAttribute(ENTRY_REPOSITORY_ID_ATTRIBUTE);
    if (repositoryId instanceof Number number) {
      return number.longValue();
    }
    Object value = attrs.getRequest().getAttribute(
        RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE);
    return value instanceof RepositoryRecord repository ? repository.id() : null;
  }

  private boolean internalScannerRequest() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
      return false;
    }
    Object value = attrs.getRequest().getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    return value instanceof AuthenticatedSubject subject
        && DockerAuthService.SCANNER_SUBJECT_SOURCE.equals(subject.source());
  }

  private record Evaluation(
      RepositoryScanConfig config, PolicyDecision decision, boolean blocked) {}

  public record Decision(PolicyDecision decision, boolean enforced, boolean shadowBlocked) {
    static Decision allow() {
      return new Decision(PolicyDecision.ALLOW, false, false);
    }
  }
}
