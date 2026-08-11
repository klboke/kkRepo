package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.TaskDraft;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.CandidateDisposition;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanTaskPriorities;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Converts durable content-change markers into idempotent scan tasks. */
@Service
public class SecurityScanCandidateService {
  private final SecurityScanDao scans;
  private final AssetDao assets;
  private final RepositoryDao repositories;
  private final SecurityScanCandidateClassifier classifier;
  private final SecurityScanningProperties properties;
  private final SecurityScanRepositoryScope repositoryScope;

  public SecurityScanCandidateService(
      SecurityScanDao scans,
      AssetDao assets,
      RepositoryDao repositories,
      SecurityScanCandidateClassifier classifier,
      SecurityScanningProperties properties,
      SecurityScanRepositoryScope repositoryScope) {
    this.scans = scans;
    this.assets = assets;
    this.repositories = repositories;
    this.classifier = classifier;
    this.properties = properties;
    this.repositoryScope = repositoryScope;
  }

  @Transactional
  public int processBatch() {
    int processed = 0;
    for (ScanCandidate candidate :
        scans.claimCandidates(properties.getWorker().getCandidateBatchSize())) {
      process(candidate);
      processed++;
    }
    return processed;
  }

  private void process(ScanCandidate candidate) {
    AssetDao.AssetWithBlob content = assets.findAssetWithBlobById(candidate.assetId()).orElse(null);
    if (content == null || content.blob() == null
        || !Objects.equals(candidate.assetBlobId(), content.blob().id())) {
      scans.markCandidateEnqueued(candidate.assetId(), candidate.contentGeneration());
      return;
    }
    RepositoryRecord repository =
        repositories.findById(content.asset().repositoryId()).orElse(null);
    if (repository == null || repository.type() == RepositoryType.GROUP) {
      scans.markCandidateEnqueued(candidate.assetId(), candidate.contentGeneration());
      return;
    }
    requeueConanPackageArchive(content);
    Map<Long, RepositoryScanConfig> configsByProfile = new LinkedHashMap<>();
    for (RepositoryScanConfig config :
        repositoryScope.effectiveConfigsForSource(repository.id())) {
      configsByProfile.putIfAbsent(config.profileId(), config);
    }
    if (configsByProfile.isEmpty()) {
      scans.markCandidateEnqueued(candidate.assetId(), candidate.contentGeneration());
      return;
    }
    for (RepositoryScanConfig config : configsByProfile.values()) {
      processProfile(candidate, content, config);
    }
    scans.markCandidateEnqueued(candidate.assetId(), candidate.contentGeneration());
  }

  private void processProfile(
      ScanCandidate candidate,
      AssetDao.AssetWithBlob content,
      RepositoryScanConfig config) {
    ScanProfile profile = scans.findProfile(config.profileId()).orElse(null);
    if (profile == null) {
      return;
    }
    if (!profile.enabled()) {
      materialize(
          candidate, config, ScanState.FAILED, ScanCompleteness.UNKNOWN,
          PolicyDecision.ALLOW, "PROFILE_UNAVAILABLE", null);
      return;
    }

    var classification = classifier.classify(content.asset(), content.blob(), profile);
    var identity = classifier.subjectIdentity(content.asset(), content.blob());
    if (identity == null) {
      identity = SecurityScanCandidateClassifier.blobIdentity(
          content.asset(), content.blob());
    }
    if (classification.disposition() == CandidateDisposition.SCANNABLE) {
      String subjectKey = identity.key();
      if (!identity.complete()) {
        PolicyDecision pending = config.pendingAction() == PolicyAction.BLOCK
            ? PolicyDecision.BLOCK_PENDING : PolicyDecision.ALLOW;
        materialize(
            candidate, config, ScanState.PENDING, ScanCompleteness.UNKNOWN,
            pending, "CONANINFO_IDENTITY_PENDING", subjectKey);
        return;
      }
      Instant now = Instant.now();
      scans.createTask(new TaskDraft(
          content.asset().repositoryId(),
          content.asset().id(),
          classification.subjectKind(),
          subjectKey,
          candidate.contentGeneration(),
          profile.id(),
          profile.revision(),
          null, // ordinary work acquires a verified ready snapshot when execution begins
          ScanStage.CATALOG_AND_MATCH,
          RequestReason.CONTENT_CHANGED,
          ScanTaskPriorities.CONTENT,
          properties.getWorker().getMaxAttempts(),
          "system",
          requestUuid(candidate, profile),
          null,
          now));
      PolicyDecision pending = config.pendingAction() == PolicyAction.BLOCK
          ? PolicyDecision.BLOCK_PENDING : PolicyDecision.ALLOW;
      materialize(
          candidate, config, ScanState.PENDING, ScanCompleteness.UNKNOWN,
          pending, "SCAN_PENDING", subjectKey);
    } else if (classification.disposition() == CandidateDisposition.REJECTED_BY_LIMIT) {
      PolicyDecision failed = config.failureAction() == PolicyAction.BLOCK
          ? PolicyDecision.BLOCK_SCAN_FAILED : PolicyDecision.ALLOW;
      materialize(
          candidate, config, ScanState.FAILED, ScanCompleteness.UNKNOWN,
          failed, classification.reasonCode(), identity.key());
    } else if (classification.disposition() == CandidateDisposition.NOT_APPLICABLE) {
      materialize(
          candidate, config, ScanState.NOT_APPLICABLE, ScanCompleteness.COMPLETE,
          PolicyDecision.ALLOW, classification.reasonCode(), identity.key());
    }
  }

  private void requeueConanPackageArchive(AssetDao.AssetWithBlob content) {
    if (content.asset().format()
        != com.github.klboke.kkrepo.core.RepositoryFormat.CONAN) return;
    var context = classifier.conanPackageScanContext(content.asset().id()).orElse(null);
    if (context == null || context.archive().file().assetId() == null
        || context.archive().file().assetId().equals(content.asset().id())) return;
    long archiveAssetId = context.archive().file().assetId();
    scans.markRepositoryAssetsForBackfill(
        content.asset().repositoryId(), Math.max(0, archiveAssetId - 1), 1);
  }

  private static String requestUuid(ScanCandidate candidate, ScanProfile profile) {
    String marker = candidate.updatedAt() == null
        ? candidate.changedAt() == null ? "" : candidate.changedAt().toString()
        : candidate.updatedAt().toString();
    return UUID.nameUUIDFromBytes(String.join(
        "\0",
        "candidate",
        Long.toString(candidate.assetId()),
        Long.toString(candidate.contentGeneration()),
        Long.toString(profile.id()),
        Long.toString(profile.revision()),
        marker).getBytes(StandardCharsets.UTF_8)).toString();
  }

  private void materialize(
      ScanCandidate candidate,
      RepositoryScanConfig config,
      ScanState state,
      ScanCompleteness completeness,
      PolicyDecision decision,
      String reasonCode,
      String subjectKey) {
    long policyRevision = config.policyId() == null
        ? 0L : scans.findPolicy(config.policyId()).map(SecurityScanDao.ScanPolicy::revision).orElse(0L);
    scans.upsertAssetStateIfCurrent(new AssetSecurityState(
        candidate.assetId(),
        config.profileId(),
        candidate.contentGeneration(),
        PersistenceHashes.sha256(subjectKey == null ? "asset:" + candidate.assetId() : subjectKey),
        null,
        state,
        completeness,
        state == ScanState.NOT_APPLICABLE,
        Severity.UNKNOWN,
        Map.of(),
        config.policyId(),
        policyRevision == 0 ? null : policyRevision,
        decision,
        reasonCode,
        null,
        Instant.now(),
        0));
  }

}
