package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetPolicyState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.CandidateDisposition;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.server.docker.DockerAuthService;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Evaluates the concrete member asset after repository resolution and before any response body is
 * opened. Shared database state is always authoritative; no node-local cache is used for blocking.
 */
@Service
public class ArtifactDownloadPolicy {
  public static final String ENTRY_REPOSITORY_ID_ATTRIBUTE =
      ArtifactDownloadPolicy.class.getName() + ".ENTRY_REPOSITORY_ID";
  private static final int DEFAULT_RETRY_AFTER_SECONDS = 30;

  private final SecurityScanDao scans;
  private final RepositoryDao repositories;
  private final AssetDao assets;
  private final SecurityScanCandidateClassifier classifier;
  private final SecurityScanningProperties properties;
  private final SecurityScanMetrics metrics;

  public ArtifactDownloadPolicy(
      SecurityScanDao scans,
      RepositoryDao repositories,
      AssetDao assets,
      SecurityScanCandidateClassifier classifier,
      SecurityScanningProperties properties,
      SecurityScanMetrics metrics) {
    this.scans = scans;
    this.repositories = repositories;
    this.assets = assets;
    this.classifier = classifier;
    this.properties = properties;
    this.metrics = metrics;
  }

  public Decision beforeRead(long assetId) {
    return beforeRead(assetId, requestEntryRepositoryId());
  }

  public Decision beforeRead(long assetId, Long entryRepositoryId) {
    if (!properties.isEnabled() || internalScannerRequest()) return Decision.allow();
    AssetDao.AssetWithBlob content = assets.findAssetWithBlobById(assetId).orElse(null);
    if (content == null || content.blob() == null) return Decision.allow();
    AssetRecord asset = content.asset();
    RepositoryRecord source = repositories.findById(asset.repositoryId()).orElse(null);
    if (source == null) return Decision.allow();
    RepositoryRecord entry = entryRepositoryId == null
        ? source : repositories.findById(entryRepositoryId).orElse(source);

    List<RepositoryScanConfig> configs = effectiveConfigs(source.id(), entry.id());
    List<Evaluation> evaluations = new ArrayList<>();
    for (RepositoryScanConfig config : configs) {
      if (!config.enabled()) continue;
      ScanProfile profile = scans.findProfile(config.profileId()).filter(ScanProfile::enabled)
          .orElse(null);
      if (profile == null) {
        evaluations.add(action(config, config.failureAction(), PolicyDecision.BLOCK_SCAN_FAILED));
        continue;
      }
      var classification = classifier.classify(asset, content.blob(), profile);
      if (classification.disposition() != CandidateDisposition.SCANNABLE) {
        evaluations.add(new Evaluation(config, PolicyDecision.ALLOW, false));
        continue;
      }
      evaluations.add(evaluate(config, profile, asset.id()));
    }
    Evaluation strictest = evaluations.stream()
        .max(Comparator.comparingInt(ArtifactDownloadPolicy::rank))
        .orElse(null);
    if (strictest == null) return Decision.allow();
    boolean enforce = strictest.config().enforcementMode() == EnforcementMode.ENFORCE;
    metrics.recordPolicy(asset.format().name(), strictest.decision(), enforce);
    if (enforce && strictest.blocked()) {
      throw new ArtifactPolicyException(
          strictest.decision(), DEFAULT_RETRY_AFTER_SECONDS);
    }
    return new Decision(strictest.decision(), enforce, !enforce && strictest.blocked());
  }

  private Evaluation evaluate(
      RepositoryScanConfig config, ScanProfile profile, long assetId) {
    ScanCandidate candidate = scans.findCandidate(assetId).orElse(null);
    AssetSecurityState state = scans.findAssetState(assetId, profile.id()).orElse(null);
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
      case COMPLETE -> evaluateComplete(config, profile, candidate, state);
      case NOT_APPLICABLE -> new Evaluation(config, PolicyDecision.ALLOW, false);
    };
  }

  private Evaluation evaluateComplete(
      RepositoryScanConfig config,
      ScanProfile profile,
      ScanCandidate candidate,
      AssetSecurityState state) {
    Instant now = Instant.now();
    ScanPolicy currentPolicy =
        config.policyId() == null ? null : scans.findPolicy(config.policyId()).orElse(null);
    AssetPolicyState policyState =
        scans.findAssetPolicyState(state.assetId(), profile.id(), config.repositoryId()).orElse(null);
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

  private List<RepositoryScanConfig> effectiveConfigs(long sourceId, long entryId) {
    Set<Long> repositoryIds = new LinkedHashSet<>();
    repositoryIds.add(sourceId);
    repositoryIds.add(entryId);
    return repositoryIds.stream()
        .map(scans::findRepositoryConfig)
        .flatMap(java.util.Optional::stream)
        .toList();
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
