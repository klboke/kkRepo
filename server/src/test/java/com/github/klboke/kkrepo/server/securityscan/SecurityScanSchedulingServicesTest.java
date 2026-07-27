package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.PolicyEvaluationTarget;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScannerSnapshot;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.TaskDraft;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.CandidateDisposition;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.OciPlatformPolicy;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Adapter;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Capabilities;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Readiness;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SecurityScanSchedulingServicesTest {
  @Test
  void candidateBatchHandlesEveryDispositionAndAlwaysAdvancesTheMarker() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    AssetDao assets = mock(AssetDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    SecurityScanCandidateClassifier classifier = mock(SecurityScanCandidateClassifier.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setBatchSize(20);
    SecurityScanRepositoryScope scope = mock(SecurityScanRepositoryScope.class);
    SecurityScanCandidateService service = new SecurityScanCandidateService(
        scans, assets, repositories, classifier, properties, scope);
    List<ScanCandidate> candidates = java.util.stream.LongStream.rangeClosed(1, 9)
        .mapToObj(id -> new ScanCandidate(id, id, 2, 1, Instant.now(), Instant.now()))
        .toList();
    when(scans.claimCandidates(20)).thenReturn(candidates);

    for (long id = 2; id <= 9; id++) {
      AssetWithBlob content = content(id, 100 + id, id);
      RepositoryRecord repository = repository(100 + id, RepositoryType.HOSTED);
      when(assets.findAssetWithBlobById(id)).thenReturn(Optional.of(content));
      when(repositories.findById(100 + id))
          .thenReturn(Optional.of(repository));
    }
    AssetWithBlob mismatch = content(2, 102, 20);
    RepositoryRecord group = repository(103, RepositoryType.GROUP);
    when(assets.findAssetWithBlobById(2L)).thenReturn(Optional.of(mismatch));
    when(repositories.findById(103L)).thenReturn(Optional.of(group));
    when(scope.effectiveConfigsForSource(104L)).thenReturn(List.of());

    for (long id = 5; id <= 9; id++) {
      when(scope.effectiveConfigsForSource(100 + id))
          .thenReturn(List.of(config(100 + id, id * 10)));
    }
    ScanProfile disabled = profile(60, false);
    ScanProfile scannable = profile(70, true);
    ScanProfile rejected = profile(80, true);
    ScanProfile notApplicable = profile(90, true);
    when(scans.findProfile(50L)).thenReturn(Optional.empty());
    when(scans.findProfile(60L)).thenReturn(Optional.of(disabled));
    when(scans.findProfile(70L)).thenReturn(Optional.of(scannable));
    when(scans.findProfile(80L)).thenReturn(Optional.of(rejected));
    when(scans.findProfile(90L)).thenReturn(Optional.of(notApplicable));
    when(classifier.classify(any(), any(), eq(scannable)))
        .thenReturn(classification(CandidateDisposition.SCANNABLE));
    when(classifier.classify(any(), any(), eq(rejected)))
        .thenReturn(new SecurityScanCandidateClassifier.Classification(
            CandidateDisposition.REJECTED_BY_LIMIT, null, null, "INPUT_SIZE_LIMIT"));
    when(classifier.classify(any(), any(), eq(notApplicable)))
        .thenReturn(new SecurityScanCandidateClassifier.Classification(
            CandidateDisposition.NOT_APPLICABLE, null, null, "METADATA"));

    assertEquals(9, service.processBatch());

    verify(scans, org.mockito.Mockito.times(9)).markCandidateEnqueued(anyLong(), eq(2L));
    verify(scans).createTask(any(TaskDraft.class));
    verify(scans, org.mockito.Mockito.times(4))
        .upsertAssetStateIfCurrent(any(AssetSecurityState.class));
  }

  @Test
  void snapshotServiceUsesSharedFreshObservationsAndValidatesProvenance() {
    Adapter adapter = mock(Adapter.class);
    SecurityScanDao scans = mock(SecurityScanDao.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    SecurityScanMetrics metrics = mock(SecurityScanMetrics.class);
    SecurityScannerSnapshotService service =
        new SecurityScannerSnapshotService(adapter, scans, properties, metrics);
    ScannerSnapshot fresh = snapshot(1L, true, "db-1", Instant.now(), "fingerprint-1");
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(fresh));

    assertEquals(fresh, service.readySnapshot());
    verify(metrics).observeScanner(true, fresh.vulnerabilityDatabaseUpdatedAt());

    ScannerSnapshot notReady =
        snapshot(2L, false, "db-1", Instant.now(), "fingerprint-2");
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(notReady));
    assertEquals(
        "SCANNER_NOT_READY",
        assertThrows(ScannerAdapterException.class, service::readySnapshot).code());

    ScannerSnapshot unknownDb =
        snapshot(3L, true, " ", Instant.now(), "fingerprint-3");
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(unknownDb));
    assertEquals(
        "SCANNER_DATABASE_UNKNOWN",
        assertThrows(ScannerAdapterException.class, service::readySnapshot).code());

    properties.setScannerDatabaseMaxAge(Duration.ofHours(1));
    ScannerSnapshot stale =
        snapshot(4L, true, "db-1", Instant.now().minusSeconds(7200), "fingerprint-4");
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(stale));
    assertEquals(
        "SCANNER_DATABASE_STALE",
        assertThrows(ScannerAdapterException.class, service::readySnapshot).code());
  }

  @Test
  void snapshotServiceObservesAdapterAndPersistsMatchProvenance() {
    Adapter adapter = mock(Adapter.class);
    SecurityScanDao scans = mock(SecurityScanDao.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    SecurityScanMetrics metrics = mock(SecurityScanMetrics.class);
    SecurityScannerSnapshotService service =
        new SecurityScannerSnapshotService(adapter, scans, properties, metrics);
    when(scans.latestScannerSnapshot()).thenReturn(Optional.empty());
    Capabilities capabilities = new Capabilities(
        ScannerContract.API_VERSION,
        "adapter",
        "1",
        List.of("CATALOG", "MATCH"),
        List.of("PACKAGE"),
        1024,
        2048,
        "capability");
    Readiness readiness = new Readiness(
        true,
        "READY",
        "grype",
        "2",
        "db-2",
        Instant.now(),
        null,
        Map.of("catalogEngineVersion", "1"));
    when(adapter.capabilities()).thenReturn(capabilities);
    when(adapter.readiness()).thenReturn(readiness);
    when(scans.insertSnapshotOrFindExisting(any()))
        .thenAnswer(invocation -> {
          ScannerSnapshot proposed = invocation.getArgument(0);
          return new ScannerSnapshot(
              8L,
              proposed.adapterName(),
              proposed.adapterApiVersion(),
              proposed.engineName(),
              proposed.engineVersion(),
              proposed.vulnerabilityDatabaseRevision(),
              proposed.vulnerabilityDatabaseUpdatedAt(),
              proposed.capabilityDigest(),
              proposed.snapshotFingerprint(),
              proposed.observedAt(),
              proposed.ready(),
              proposed.details());
        });

    ScannerSnapshot observed = service.readySnapshot();
    ScannerSnapshot matched = service.snapshotFor(matchResponse());

    assertEquals(8L, observed.id());
    assertEquals("grype", matched.engineName());

    when(adapter.capabilities()).thenReturn(new Capabilities(
        "v2", "adapter", "1", List.of(), List.of(), 1, 1, "cap"));
    assertEquals(
        "SCANNER_API_UNSUPPORTED",
        assertThrows(ScannerAdapterException.class, service::readySnapshot).code());
  }

  @Test
  void snapshotWatcherSchedulesOnlyCurrentAssetsAndAuditsAChangedSnapshot() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    AssetDao assets = mock(AssetDao.class);
    SecurityScannerSnapshotService snapshots = mock(SecurityScannerSnapshotService.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setSnapshotRematchBatchSize(10);
    SecurityScanAuditService audit = mock(SecurityScanAuditService.class);
    SecurityScannerSnapshotWatcher watcher =
        new SecurityScannerSnapshotWatcher(scans, assets, snapshots, properties, audit);
    ScannerSnapshot previous =
        snapshot(1L, true, "db-1", Instant.now(), "old");
    ScannerSnapshot current =
        snapshot(2L, true, "db-2", Instant.now(), "new");
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(previous));
    when(snapshots.readySnapshot()).thenReturn(current);
    ScanProfile profile = profile(3L, true);
    when(scans.listProfiles()).thenReturn(List.of(profile, profile(4L, false)));
    AssetSecurityState valid = state(11L, 3L, 1L, 5L);
    AssetSecurityState noRun = state(12L, 3L, 1L, null);
    when(scans.listAssetStatesNeedingSnapshot(3L, 2L, 0L, 10))
        .thenReturn(List.of(valid, noRun));
    AssetWithBlob currentContent = content(11, 7, 21);
    when(assets.findAssetWithBlobById(11L)).thenReturn(Optional.of(currentContent));
    when(scans.findCandidate(11L))
        .thenReturn(Optional.of(new ScanCandidate(11, 21L, 1, 0, Instant.now(), Instant.now())));

    watcher.reconcile();

    verify(scans).markAssetStateStale(eq(11L), eq(3L), eq(5L), any());
    verify(scans).createTask(any(TaskDraft.class));
    verify(audit).recordSystem(
        eq("SCANNER_SNAPSHOT_CHANGED"), eq(null), any());

    when(snapshots.readySnapshot())
        .thenThrow(new ScannerAdapterException("DOWN", "down", true))
        .thenThrow(new IllegalStateException("down"));
    watcher.reconcile();
    watcher.reconcile();
  }

  @Test
  void policyReconcilerSchedulesFreshAndPolicyOnlyWorkAndMaterializesTerminalAssets() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    SecurityScanCandidateClassifier classifier = mock(SecurityScanCandidateClassifier.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    SecurityPolicyReconciler reconciler =
        new SecurityPolicyReconciler(scans, repositories, assets, classifier, properties);
    ScanProfile profile = profile(3L, true);
    RepositoryScanConfig context = config(7L, 3L);
    Instant now = Instant.now();

    PolicyEvaluationTarget missing =
        new PolicyEvaluationTarget(10, 7, 1, null, null, null, 1, null);
    reconciler.reconcile(context, profile, missing, now);

    AssetWithBlob currentContent = content(11, 7, 21);
    when(assets.findAssetWithBlobById(11L)).thenReturn(Optional.of(currentContent));
    PolicyEvaluationTarget noCandidate =
        new PolicyEvaluationTarget(11, 7, 1, null, null, null, 1, null);
    reconciler.reconcile(context, profile, noCandidate, now);
    verify(scans).markRepositoryAssetsForBackfill(7L, 10L, 1);

    when(scans.findCandidate(11L))
        .thenReturn(Optional.of(new ScanCandidate(11, 21L, 1, 0, now, now)));
    when(classifier.classify(any(), any(), eq(profile)))
        .thenReturn(new SecurityScanCandidateClassifier.Classification(
            CandidateDisposition.NOT_APPLICABLE, null, null, "METADATA"));
    reconciler.reconcile(context, profile, noCandidate, now);
    verify(scans).upsertAssetStateIfCurrent(any());

    when(classifier.classify(any(), any(), eq(profile)))
        .thenReturn(classification(CandidateDisposition.SCANNABLE));
    PolicyEvaluationTarget fresh =
        new PolicyEvaluationTarget(11, 7, 1, null, null, ScanState.PENDING, 2, null);
    PolicyEvaluationTarget reusable =
        new PolicyEvaluationTarget(11, 7, 1, 1L, 44L, ScanState.COMPLETE, 3, now.plusSeconds(30));
    reconciler.reconcile(context, profile, fresh, now);
    reconciler.reconcile(context, profile, reusable, now);

    ArgumentCaptor<TaskDraft> drafts = ArgumentCaptor.forClass(TaskDraft.class);
    verify(scans, org.mockito.Mockito.times(2)).createTask(drafts.capture());
    assertEquals(ScanStage.CATALOG_AND_MATCH, drafts.getAllValues().getFirst().stage());
    assertEquals(ScanStage.POLICY_ONLY, drafts.getAllValues().getLast().stage());
  }

  @Test
  void policyReconcilerTraversesGroupSourcesAndContainsBatchFailures() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    SecurityScanCandidateClassifier classifier = mock(SecurityScanCandidateClassifier.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    SecurityPolicyReconciler reconciler =
        new SecurityPolicyReconciler(scans, repositories, assets, classifier, properties);
    RepositoryRecord group = repository(100, RepositoryType.GROUP);
    RepositoryRecord member = repository(101, RepositoryType.HOSTED);
    RepositoryScanConfig config = config(100, 3);
    ScanProfile profile = profile(3L, true);
    when(repositories.list()).thenReturn(List.of(group));
    when(repositories.listMembers(100L)).thenReturn(List.of(member));
    when(scans.findRepositoryConfig(100L)).thenReturn(Optional.of(config));
    when(scans.findProfile(3L)).thenReturn(Optional.of(profile));
    when(scans.listPolicyEvaluationTargets(
        eq(101L), eq(100L), eq(3L), anyLong(), eq(null), eq(null), eq(0L), any(), anyInt()))
        .thenReturn(List.of());

    reconciler.runOnce();

    verify(scans).listPolicyEvaluationTargets(
        eq(101L), eq(100L), eq(3L), anyLong(), eq(null), eq(null), eq(0L), any(), anyInt());
    when(repositories.list()).thenThrow(new IllegalStateException("temporary"));
    reconciler.runOnce();
  }

  private static SecurityScanCandidateClassifier.Classification classification(
      CandidateDisposition disposition) {
    return new SecurityScanCandidateClassifier.Classification(
        disposition, SubjectKind.ASSET_BLOB, TargetClassification.PACKAGE, "SCANNABLE");
  }

  private static ScanProfile profile(long id, boolean enabled) {
    return new ScanProfile(
        id,
        "profile-" + id,
        enabled,
        "syft",
        "grype",
        List.of("vulnerability"),
        Map.of(),
        1024 * 1024,
        1000,
        4 * 1024 * 1024,
        1024 * 1024,
        2,
        60,
        OciPlatformPolicy.REQUIRED_SET,
        List.of("linux/amd64"),
        "a".repeat(64),
        1,
        Instant.now(),
        Instant.now());
  }

  private static RepositoryScanConfig config(long repositoryId, long profileId) {
    return new RepositoryScanConfig(
        repositoryId,
        true,
        profileId,
        true,
        true,
        EnforcementMode.AUDIT,
        PolicyAction.BLOCK,
        PolicyAction.BLOCK,
        PolicyAction.BLOCK,
        3600L,
        null,
        1,
        Instant.now(),
        Instant.now());
  }

  private static RepositoryRecord repository(long id, RepositoryType type) {
    RepositoryRecord repository = mock(RepositoryRecord.class);
    when(repository.id()).thenReturn(id);
    when(repository.type()).thenReturn(type);
    return repository;
  }

  private static AssetWithBlob content(long assetId, long repositoryId, long blobId) {
    AssetRecord asset = mock(AssetRecord.class);
    when(asset.id()).thenReturn(assetId);
    when(asset.repositoryId()).thenReturn(repositoryId);
    when(asset.format()).thenReturn(RepositoryFormat.MAVEN2);
    when(asset.kind()).thenReturn("artifact");
    AssetBlobRecord blob = mock(AssetBlobRecord.class);
    when(blob.id()).thenReturn(blobId);
    when(blob.sha256()).thenReturn("a".repeat(64));
    when(blob.size()).thenReturn(100L);
    return new AssetWithBlob(asset, blob);
  }

  private static ScannerSnapshot snapshot(
      long id, boolean ready, String database, Instant updatedAt, String fingerprint) {
    return new ScannerSnapshot(
        id,
        "adapter",
        ScannerContract.API_VERSION,
        "grype",
        "2",
        database,
        updatedAt,
        "cap",
        fingerprint,
        Instant.now(),
        ready,
        Map.of());
  }

  private static AssetSecurityState state(
      long assetId, long profileId, long generation, Long runId) {
    return new AssetSecurityState(
        assetId,
        profileId,
        generation,
        new byte[32],
        runId,
        ScanState.COMPLETE,
        ScanCompleteness.COMPLETE,
        true,
        com.github.klboke.kkrepo.security.scan.ScanEnums.Severity.LOW,
        Map.of(),
        null,
        null,
        com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision.ALLOW,
        "ALLOW",
        null,
        Instant.now(),
        1);
  }

  private static MatchResponse matchResponse() {
    return new MatchResponse(
        "adapter",
        "1",
        "grype",
        "2",
        "db-2",
        Instant.now(),
        "cap",
        ScanCompleteness.COMPLETE,
        "{}".getBytes(),
        List.of(),
        Map.of());
  }
}
