package com.github.klboke.kkrepo.server.securityscan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.Sbom;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.SbomComponent;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanFinding;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanTask;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScannerSnapshot;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanFingerprints;
import com.github.klboke.kkrepo.security.scan.ScanSubject;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Adapter;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import com.github.klboke.kkrepo.security.scan.ScannerContract.SnapshotExpectation;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.docker.DockerAuthService;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/** Catalog -> immutable CycloneDX -> Match pipeline for ordinary and OCI subjects. */
@Service
public class SecurityScanExecutor {
  private static final int MAX_PROJECTED_COMPONENTS = 100_000;
  private static final int MAX_PROJECTED_FINDINGS = 100_000;

  private final SecurityScanDao scans;
  private final AssetDao assets;
  private final RepositoryDao repositories;
  private final DockerRegistryDao docker;
  private final BlobStorageRegistry storages;
  private final SecurityScanCandidateClassifier classifier;
  private final Adapter adapter;
  private final SecurityScannerSnapshotService snapshots;
  private final SecurityScanDocumentStore documents;
  private final SecurityScanFinalizer finalizer;
  private final DockerAuthService dockerAuth;
  private final SecurityScanningProperties properties;
  private final ObjectMapper objectMapper;
  private final SecurityScanMetrics metrics;
  private final SecurityScanRepositoryScope repositoryScope;

  public SecurityScanExecutor(
      SecurityScanDao scans,
      AssetDao assets,
      RepositoryDao repositories,
      DockerRegistryDao docker,
      BlobStorageRegistry storages,
      SecurityScanCandidateClassifier classifier,
      Adapter adapter,
      SecurityScannerSnapshotService snapshots,
      SecurityScanDocumentStore documents,
      SecurityScanFinalizer finalizer,
      DockerAuthService dockerAuth,
      SecurityScanningProperties properties,
      ObjectMapper objectMapper,
      SecurityScanMetrics metrics,
      SecurityScanRepositoryScope repositoryScope) {
    this.scans = scans;
    this.assets = assets;
    this.repositories = repositories;
    this.docker = docker;
    this.storages = storages;
    this.classifier = classifier;
    this.adapter = adapter;
    this.snapshots = snapshots;
    this.documents = documents;
    this.finalizer = finalizer;
    this.dockerAuth = dockerAuth;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.repositoryScope = repositoryScope;
  }

  public ScanRun execute(ScanTask task) {
    if (task.assetId() == null || task.leaseToken() == null) {
      throw new ScannerAdapterException(
          "TASK_SUBJECT_INVALID", "Scan task has no asset or lease", false);
    }
    AssetDao.AssetWithBlob content = assets.findAssetWithBlobById(task.assetId())
        .orElseThrow(() -> new ScannerAdapterException(
            "ASSET_NOT_FOUND", "Scan subject no longer exists", false));
    AssetRecord asset = content.asset();
    AssetBlobRecord blob = content.blob();
    if (blob == null || blank(blob.sha256())) {
      throw new ScannerAdapterException(
          "BLOB_IDENTITY_MISSING", "Scan subject has no SHA-256 identity", false);
    }
    var candidate = scans.findCandidate(task.assetId()).orElseThrow(() ->
        new ScannerAdapterException("CANDIDATE_NOT_FOUND", "Scan candidate no longer exists", false));
    if (candidate.contentGeneration() != task.contentGeneration()
        || !Objects.equals(candidate.assetBlobId(), blob.id())
        || !("sha256:" + blob.sha256()).equals(task.subjectKey())) {
      throw new SupersededSecurityScanTaskException(task.id());
    }
    RepositoryRecord repository = repositories.findById(task.repositoryId())
        .orElseThrow(() -> new ScannerAdapterException(
            "REPOSITORY_NOT_FOUND", "Scan repository no longer exists", false));
    ScanProfile profile = scans.findProfile(task.profileId())
        .filter(ScanProfile::enabled)
        .orElseThrow(() -> new ScannerAdapterException(
            "PROFILE_UNAVAILABLE", "Security scan profile is unavailable", false));
    RepositoryScanConfig config = executionConfig(task.repositoryId(), profile.id());
    var classification = classifier.classify(asset, blob, profile);
    if (classification.disposition()
        != com.github.klboke.kkrepo.security.scan.ScanEnums.CandidateDisposition.SCANNABLE) {
      throw new ScannerAdapterException(
          "SUBJECT_NOT_SCANNABLE", classification.reasonCode(), false);
    }

    if (task.stage() == ScanStage.POLICY_ONLY) {
      ScanRun existing = scans.findAssetState(asset.id(), profile.id())
          .filter(state -> state.contentGeneration() == task.contentGeneration())
          .flatMap(state -> state.latestScanRunId() == null
              ? java.util.Optional.empty() : scans.findRun(state.latestScanRunId()))
          .orElseThrow(() -> new ScannerAdapterException(
              "POLICY_SOURCE_RUN_MISSING",
              "Policy evaluation source run is unavailable",
              false));
      return finalizer.finalizeRun(
          task, profile, config, task.subjectKey(), existing, List.of());
    }

    ScannerSnapshot executionSnapshot = executionSnapshot(task);
    ScanSubject subject = new ScanSubject(
        classification.subjectKind(),
        task.repositoryId(),
        asset.id(),
        blob.id(),
        task.subjectKey(),
        blob.sha256(),
        blob.size(),
        asset.format().name(),
        asset.kind(),
        asset.contentType(),
        classification.targetClassification(),
        profile.requiredPlatforms(),
        Map.of("path", asset.path()));
    ResourceLimits limits = limits(profile);

    if (classification.subjectKind()
        == com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind.OCI_MANIFEST) {
      return executeOci(
          task, repository, asset, blob, profile, config, subject, limits, executionSnapshot);
    }
    Sbom sbom = resolveSbom(
        task, asset, blob, profile, subject, limits, executionSnapshot);
    return matchAndFinalize(task, profile, config, subject, sbom, limits, executionSnapshot);
  }

  private ScanRun executeOci(
      ScanTask task,
      RepositoryRecord repository,
      AssetRecord asset,
      AssetBlobRecord blob,
      ScanProfile profile,
      RepositoryScanConfig config,
      ScanSubject subject,
      ResourceLimits limits,
      ScannerSnapshot observedSnapshot) {
    DockerManifestRecord manifest = docker.findManifestsByAssetIds(List.of(asset.id()))
        .get(asset.id());
    if (manifest == null) {
      throw new ScannerAdapterException(
          "OCI_MANIFEST_NOT_FOUND", "OCI manifest metadata is unavailable", true);
    }
    String token = dockerAuth.grantScannerPull(
        repository.name(), manifest.imageName(), profile.timeoutSeconds() + 120L);
    OciScanRequest request = new OciScanRequest(
        ScannerContract.API_VERSION,
        Long.toString(task.id()),
        task.id() + ":oci:" + task.contentGeneration(),
        properties.getOciRegistryUrl(),
        repository.name() + "/" + manifest.imageName(),
        manifest.digest(),
        profile.requiredPlatforms(),
        token,
        profile.configurationDigest(),
        limits,
        expectation(observedSnapshot));
    OciScanResponse response;
    try {
      response = adapter.scanOci(request);
    } catch (IOException e) {
      throw new ScannerAdapterException(
          "SCANNER_IO", "OCI scanner request failed", true, e);
    }
    validateCatalogResponse(subject, response.catalog());
    Sbom sbom = persistSbom(
        task.id(),
        subject,
        profile,
        response.catalog(),
        response.scannedPlatforms(),
        response.missingPlatforms());
    MatchResponse match = response.match();
    ScanCompleteness completeness = response.missingPlatforms().isEmpty()
        ? match.completeness() : ScanCompleteness.PARTIAL;
    MatchResponse normalized = new MatchResponse(
        match.adapterName(),
        match.adapterVersion(),
        match.engineName(),
        match.engineVersion(),
        match.vulnerabilityDatabaseRevision(),
        match.vulnerabilityDatabaseUpdatedAt(),
        match.capabilityDigest(),
        completeness,
        match.reportJson(),
        match.findings(),
        mergeSummary(match.summary(), Map.of(
            "scannedPlatforms", response.scannedPlatforms(),
            "missingPlatforms", response.missingPlatforms())));
    return persistAndFinalizeMatch(
        task, profile, config, subject, sbom, normalized, observedSnapshot);
  }

  private Sbom resolveSbom(
      ScanTask task,
      AssetRecord asset,
      AssetBlobRecord blob,
      ScanProfile profile,
      ScanSubject subject,
      ResourceLimits limits,
      ScannerSnapshot snapshot) {
    if (task.stage() == ScanStage.MATCH_ONLY) {
      Sbom previous = scans.findAssetState(asset.id(), profile.id())
          .flatMap(state -> state.latestScanRunId() == null
              ? java.util.Optional.empty() : scans.findRun(state.latestScanRunId()))
          .flatMap(run -> scans.findSbom(run.sbomId()))
          .orElse(null);
      if (previous != null) return previous;
    }

    String catalogVersion = stringDetail(
        snapshot.details(), "catalogEngineVersion", snapshot.engineVersion());
    byte[] identityHash = PersistenceHashes.sha256(subject.identity());
    Sbom reusable = scans.findReusableSbom(
        subject.kind(),
        identityHash,
        profile.catalogEngine(),
        catalogVersion,
        profile.configurationDigest()).orElse(null);
    if (reusable != null) return reusable;

    CatalogRequest request = new CatalogRequest(
        ScannerContract.API_VERSION,
        Long.toString(task.id()),
        task.id() + ":catalog:" + task.contentGeneration(),
        subject,
        profile.configurationDigest(),
        limits);
    CatalogResponse response;
    Timer.Sample catalogTimer = metrics.start();
    try {
      metrics.recordInputBytes(asset.format().name(), blob.size());
      response = adapter.catalog(request, () -> openOriginal(blob));
      metrics.recordStage(asset.format().name(), "catalog", "success", catalogTimer);
    } catch (IOException e) {
      metrics.recordStage(asset.format().name(), "catalog", "failure", catalogTimer);
      throw new ScannerAdapterException(
          "SCANNER_IO", "Catalog scanner request failed", true, e);
    } catch (RuntimeException e) {
      metrics.recordStage(asset.format().name(), "catalog", "failure", catalogTimer);
      throw e;
    }
    validateCatalogResponse(subject, response);
    return persistSbom(task.id(), subject, profile, response);
  }

  private Sbom persistSbom(
      long provisionalOwnerId,
      ScanSubject subject,
      ScanProfile profile,
      CatalogResponse response) {
    return persistSbom(
        provisionalOwnerId, subject, profile, response, List.of(), List.of());
  }

  private Sbom persistSbom(
      long provisionalOwnerId,
      ScanSubject subject,
      ScanProfile profile,
      CatalogResponse response,
      List<String> scannedPlatforms,
      List<String> missingPlatforms) {
    validateJsonDocument(response.cyclonedxJson(), "SBOM_INVALID_JSON", true);
    var document = documents.store(
        subject.repositoryId(),
        provisionalOwnerId,
        "sbom",
        response.cyclonedxJson(),
        "application/vnd.cyclonedx+json");
    boolean published = false;
    try {
      String fingerprint = ScanFingerprints.catalog(
          subject,
          response.engineName(),
          response.engineVersion(),
          profile.configurationDigest(),
          scannedPlatforms,
          missingPlatforms);
      int projectedCount = Math.min(response.components().size(), MAX_PROJECTED_COMPONENTS);
      boolean inventoryComplete = response.completeness() == ScanCompleteness.COMPLETE
          && response.componentCount() <= projectedCount;
      Sbom proposed = new Sbom(
          null,
          subject.kind(),
          subject.identity(),
          PersistenceHashes.sha256(subject.identity()),
          response.engineName(),
          response.engineVersion(),
          profile.configurationDigest(),
          fingerprint,
          document.blobId(),
          document.sha256(),
          response.specName(),
          response.specVersion(),
          response.componentCount(),
          response.dependencyCount(),
          inventoryComplete,
          Instant.now());
      List<SbomComponent> components = response.components().stream()
          .limit(projectedCount)
          .map(component -> new SbomComponent(
              null,
              0,
              required(component.componentRef(), "componentRef"),
              null,
              component.packageUrl(),
              null,
              component.type(),
              component.namespace(),
              required(component.name(), "component name"),
              component.version(),
              component.directness(),
              component.locations(),
              component.licenses(),
              component.properties()))
          .toList();
      Sbom sbom = scans.publishSbom(proposed, components);
      published = true;
      return sbom;
    } finally {
      if (published) {
        documents.release(provisionalOwnerId, document);
      }
    }
  }

  private ScanRun matchAndFinalize(
      ScanTask task,
      ScanProfile profile,
      RepositoryScanConfig config,
      ScanSubject subject,
      Sbom sbom,
      ResourceLimits limits,
      ScannerSnapshot snapshot) {
    String expectedFingerprint = ScanFingerprints.match(
        sbom.documentSha256(),
        sbom.inventoryComplete(),
        profile.matcherEngine(),
        snapshot.engineVersion(),
        snapshot.vulnerabilityDatabaseRevision(),
        profile.configurationDigest());
    String reusableFingerprint = matchFingerprint(task, expectedFingerprint);
    ScanRun reusable = scans.findRunByMatchFingerprint(reusableFingerprint).orElse(null);
    if (reusable != null) {
      return finalizer.finalizeRun(
          task, profile, config, subject.identity(), reusable, List.of());
    }
    MatchRequest request = new MatchRequest(
        ScannerContract.API_VERSION,
        Long.toString(task.id()),
        task.id() + ":match:" + snapshot.snapshotFingerprint(),
        sbom.documentSha256(),
        profile.configurationDigest(),
        limits,
        expectation(snapshot));
    MatchResponse response;
    Timer.Sample matchTimer = metrics.start();
    try {
      response = adapter.match(request, () -> documents.open(sbom.documentBlobId()));
      metrics.recordStage(subject.format(), "match", "success", matchTimer);
    } catch (IOException e) {
      metrics.recordStage(subject.format(), "match", "failure", matchTimer);
      throw new ScannerAdapterException(
          "SCANNER_IO", "Vulnerability matcher request failed", true, e);
    } catch (RuntimeException e) {
      metrics.recordStage(subject.format(), "match", "failure", matchTimer);
      throw e;
    }
    return persistAndFinalizeMatch(
        task, profile, config, subject, sbom, response, snapshot);
  }

  private ScanRun persistAndFinalizeMatch(
      ScanTask task,
      ScanProfile profile,
      RepositoryScanConfig config,
      ScanSubject subject,
      Sbom sbom,
      MatchResponse response,
      ScannerSnapshot readinessSnapshot) {
    validateMatchResponse(response);
    ScannerSnapshot actualSnapshot = snapshots.snapshotFor(response, readinessSnapshot);
    requireRequestedSnapshot(task, actualSnapshot);
    List<String> scannedPlatforms = stringList(response.summary().get("scannedPlatforms"));
    List<String> missingPlatforms = stringList(response.summary().get("missingPlatforms"));
    String fingerprint = matchFingerprint(task, ScanFingerprints.match(
        sbom.documentSha256(),
        sbom.inventoryComplete(),
        response.engineName(),
        response.engineVersion(),
        response.vulnerabilityDatabaseRevision(),
        profile.configurationDigest(),
        scannedPlatforms,
        missingPlatforms));
    ScanRun reusable = scans.findRunByMatchFingerprint(fingerprint).orElse(null);
    if (reusable != null) {
      return finalizer.finalizeRun(
          task, profile, config, subject.identity(), reusable, List.of());
    }

    validateJsonDocument(response.reportJson(), "SCANNER_REPORT_INVALID_JSON", false);
    var report = documents.store(
        task.repositoryId(),
        task.id(),
        "vulnerability-report",
        response.reportJson(),
        "application/json");
    List<ScannerContract.Finding> normalizedFindings = response.findings().stream()
        .limit(MAX_PROJECTED_FINDINGS)
        .toList();
    boolean truncated = response.findings().size() > normalizedFindings.size();
    ScanCompleteness completeness =
        combinedCompleteness(response.completeness(), sbom.inventoryComplete(), truncated);
    ScanState status = completeness == ScanCompleteness.COMPLETE
        ? ScanState.COMPLETE : ScanState.PARTIAL;
    Map<Severity, Integer> counts = severityCounts(normalizedFindings);
    int fixable = (int) normalizedFindings.stream()
        .filter(ScannerContract.Finding::fixable).count();
    Severity maxSeverity = maxSeverity(normalizedFindings);
    Instant now = Instant.now();
    ScanRun proposed = new ScanRun(
        null,
        task.id(),
        sbom.id(),
        actualSnapshot.id(),
        profile.configurationDigest(),
        fingerprint,
        status,
        completeness,
        report.blobId(),
        report.sha256(),
        normalizedFindings.size(),
        fixable,
        counts.get(Severity.CRITICAL),
        counts.get(Severity.HIGH),
        counts.get(Severity.MEDIUM),
        counts.get(Severity.LOW) + counts.get(Severity.NEGLIGIBLE),
        counts.get(Severity.UNKNOWN),
        maxSeverity,
        scannedPlatforms,
        missingPlatforms,
        task.startedAt() == null ? now : task.startedAt(),
        now,
        now);
    List<ScanFinding> findings = normalizedFindings.stream()
        .map(finding -> toFinding(finding, now))
        .toList();
    return finalizer.finalizeRun(
        task, profile, config, subject.identity(), proposed, findings);
  }

  private static String matchFingerprint(ScanTask task, String baseFingerprint) {
    if (task.requestReason() != RequestReason.MAX_AGE_EXPIRED) {
      return baseFingerprint;
    }
    return ScanFingerprints.sha256(
        "max-age-refresh", baseFingerprint, Long.toString(task.id()));
  }

  private ScannerSnapshot executionSnapshot(ScanTask task) {
    if (task.requestedScannerSnapshotId() == null) {
      return snapshots.readySnapshot();
    }
    return scans.findScannerSnapshot(task.requestedScannerSnapshotId())
        .filter(ScannerSnapshot::ready)
        .orElseThrow(() -> new ScannerAdapterException(
            "SCANNER_SNAPSHOT_UNAVAILABLE",
            "Requested scanner snapshot is unavailable: "
                + task.requestedScannerSnapshotId(),
            false));
  }

  private static SnapshotExpectation expectation(ScannerSnapshot snapshot) {
    return new SnapshotExpectation(
        snapshot.adapterName(),
        snapshot.engineName(),
        snapshot.engineVersion(),
        snapshot.vulnerabilityDatabaseRevision(),
        snapshot.capabilityDigest());
  }

  private static void requireRequestedSnapshot(
      ScanTask task, ScannerSnapshot actualSnapshot) {
    Long requestedId = task.requestedScannerSnapshotId();
    if (requestedId == null || requestedId.equals(actualSnapshot.id())) {
      return;
    }
    throw new ScannerAdapterException(
        "SCANNER_SNAPSHOT_MISMATCH",
        "Scanner returned snapshot " + actualSnapshot.id()
            + " while task requested snapshot " + requestedId,
        true);
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> values)) {
      return List.of();
    }
    return values.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(item -> !item.isBlank())
        .distinct()
        .toList();
  }

  private static ScanCompleteness combinedCompleteness(
      ScanCompleteness matcherCompleteness,
      boolean inventoryComplete,
      boolean findingsTruncated) {
    if (matcherCompleteness == null || matcherCompleteness == ScanCompleteness.UNKNOWN) {
      return ScanCompleteness.UNKNOWN;
    }
    if (!inventoryComplete
        || findingsTruncated
        || matcherCompleteness == ScanCompleteness.PARTIAL) {
      return ScanCompleteness.PARTIAL;
    }
    return ScanCompleteness.COMPLETE;
  }

  private InputStream openOriginal(AssetBlobRecord blob) throws IOException {
    BlobStorage storage = storages.forBlobStoreId(blob.blobStoreId());
    BlobReference reference =
        BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    return storage.get(reference)
        .orElseThrow(() -> new IOException("Scan subject object not found"));
  }

  private void validateCatalogResponse(ScanSubject subject, CatalogResponse response) {
    if (response == null || response.cyclonedxJson().length == 0) {
      throw new ScannerAdapterException(
          "SCANNER_SBOM_MISSING", "Scanner returned no CycloneDX document", false);
    }
    if (!subject.sha256().equalsIgnoreCase(response.actualInputSha256())) {
      throw new ScannerAdapterException(
          "SCANNER_INPUT_CHECKSUM_MISMATCH",
          "Scanner input checksum did not match the expected content",
          false);
    }
    if (!"CycloneDX".equalsIgnoreCase(response.specName())) {
      throw new ScannerAdapterException(
          "SCANNER_SBOM_UNSUPPORTED", "Scanner did not return CycloneDX", false);
    }
  }

  private void validateMatchResponse(MatchResponse response) {
    if (response == null || blank(response.engineVersion())
        || blank(response.vulnerabilityDatabaseRevision())
        || response.vulnerabilityDatabaseUpdatedAt() == null
        || response.reportJson().length == 0) {
      throw new ScannerAdapterException(
          "SCANNER_PROVENANCE_MISSING",
          "Scanner match response did not include complete provenance",
          false);
    }
  }

  private void validateJsonDocument(byte[] bytes, String code, boolean cyclonedx) {
    try {
      JsonNode root = objectMapper.readTree(bytes);
      if (root == null || !root.isObject()
          || (cyclonedx && !"CycloneDX".equalsIgnoreCase(root.path("bomFormat").asText()))) {
        throw new ScannerAdapterException(code, "Scanner document schema is invalid", false);
      }
    } catch (IOException e) {
      throw new ScannerAdapterException(code, "Scanner document is invalid JSON", false, e);
    }
  }

  private static ScanFinding toFinding(ScannerContract.Finding finding, Instant now) {
    String packageIdentity = !blank(finding.packageUrl())
        ? finding.packageUrl() : finding.packageName();
    String locationIdentity = String.join("\n", finding.locations());
    String key = blank(finding.findingKey())
        ? ScanFingerprints.finding(
            finding.advisoryId(), packageIdentity, finding.installedVersion(), locationIdentity)
        : finding.findingKey();
    return new ScanFinding(
        null,
        0,
        key,
        PersistenceHashes.sha256(key),
        required(finding.advisoryId(), "advisoryId"),
        finding.aliases(),
        finding.dataSource(),
        finding.packageUrl(),
        required(finding.packageName(), "packageName"),
        finding.installedVersion(),
        finding.fixedVersions(),
        finding.severity(),
        finding.severitySource(),
        finding.cvssVector(),
        finding.cvssScore(),
        finding.title(),
        finding.description(),
        finding.primaryUrl(),
        finding.locations(),
        finding.sourceStatus(),
        now);
  }

  private static Map<Severity, Integer> severityCounts(
      List<ScannerContract.Finding> findings) {
    Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
    for (Severity severity : Severity.values()) counts.put(severity, 0);
    for (var finding : findings) {
      Severity severity = finding.severity() == null ? Severity.UNKNOWN : finding.severity();
      counts.put(severity, counts.get(severity) + 1);
    }
    return counts;
  }

  private static Severity maxSeverity(List<ScannerContract.Finding> findings) {
    Severity maximum = Severity.UNKNOWN;
    for (var finding : findings) {
      Severity severity = finding.severity() == null ? Severity.UNKNOWN : finding.severity();
      if (severity.rank() > maximum.rank()) maximum = severity;
    }
    return maximum;
  }

  private RepositoryScanConfig executionConfig(long sourceRepositoryId, long profileId) {
    return repositoryScope.effectiveConfig(sourceRepositoryId, profileId)
        .orElseThrow(() -> new ScannerAdapterException(
            "SCANNING_DISABLED",
            "No enabled repository policy requires this scan profile",
            false));
  }

  private static ResourceLimits limits(ScanProfile profile) {
    return new ResourceLimits(
        profile.maxInputBytes(),
        profile.maxArchiveEntries(),
        profile.maxUncompressedBytes(),
        profile.maxSingleFileBytes(),
        profile.maxNestedDepth(),
        profile.timeoutSeconds());
  }

  private static Map<String, Object> mergeSummary(
      Map<String, Object> original, Map<String, Object> additional) {
    Map<String, Object> merged = new java.util.LinkedHashMap<>();
    if (original != null) merged.putAll(original);
    merged.putAll(additional);
    return Map.copyOf(merged);
  }

  private static String stringDetail(
      Map<String, Object> details, String name, String fallback) {
    Object value = details == null ? null : details.get(name);
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  private static String required(String value, String field) {
    if (blank(value)) {
      throw new ScannerAdapterException(
          "SCANNER_SCHEMA_INVALID", "Scanner response is missing " + field, false);
    }
    return value;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public static final class SupersededSecurityScanTaskException extends RuntimeException {
    SupersededSecurityScanTaskException(long taskId) {
      super("Security scan task was superseded: " + taskId);
    }
  }
}
