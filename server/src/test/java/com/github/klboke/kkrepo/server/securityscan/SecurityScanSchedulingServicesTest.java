package com.github.klboke.kkrepo.server.securityscan;

import static com.github.klboke.kkrepo.security.scan.ScanEnums.SCANNER_OBSERVATION_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
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
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Adapter;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Capabilities;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Observation;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Readiness;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
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
    properties.getWorker().setCandidateBatchSize(20);
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
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(
        snapshot(99L, false, "db-failed", Instant.now(), "failed-snapshot")));
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
    ArgumentCaptor<TaskDraft> draft = ArgumentCaptor.forClass(TaskDraft.class);
    verify(scans).createTask(draft.capture());
    assertNull(
        draft.getValue().requestedScannerSnapshotId(),
        "a failed observation must leave new work unpinned for recovery");
    verify(scans, org.mockito.Mockito.times(4))
        .upsertAssetStateIfCurrent(any(AssetSecurityState.class));
  }

  @Test
  void unchangedCandidateBackfillCreatesFreshDeterministicWork() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    AssetDao assets = mock(AssetDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    SecurityScanCandidateClassifier classifier = mock(SecurityScanCandidateClassifier.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setCandidateBatchSize(1);
    SecurityScanRepositoryScope scope = mock(SecurityScanRepositoryScope.class);
    SecurityScanCandidateService service = new SecurityScanCandidateService(
        scans, assets, repositories, classifier, properties, scope);
    Instant firstMarker = Instant.parse("2026-07-28T12:00:00Z");
    Instant secondMarker = firstMarker.plusSeconds(1);
    ScanCandidate first = new ScanCandidate(11, 21L, 1, 0, firstMarker, firstMarker);
    ScanCandidate requeued = new ScanCandidate(11, 21L, 1, 0, firstMarker, secondMarker);
    when(scans.claimCandidates(1))
        .thenReturn(List.of(first))
        .thenReturn(List.of(requeued));
    AssetWithBlob content = content(11, 7, 21);
    ScanProfile profile = profile(3L, true);
    RepositoryRecord repository = repository(7, RepositoryType.HOSTED);
    when(assets.findAssetWithBlobById(11L)).thenReturn(Optional.of(content));
    when(repositories.findById(7L)).thenReturn(Optional.of(repository));
    when(scope.effectiveConfigsForSource(7L)).thenReturn(List.of(config(7, 3)));
    when(scans.findProfile(3L)).thenReturn(Optional.of(profile));
    when(classifier.classify(any(), any(), eq(profile)))
        .thenReturn(classification(CandidateDisposition.SCANNABLE));

    assertEquals(1, service.processBatch());
    assertEquals(1, service.processBatch());

    ArgumentCaptor<TaskDraft> drafts = ArgumentCaptor.forClass(TaskDraft.class);
    verify(scans, org.mockito.Mockito.times(2)).createTask(drafts.capture());
    assertEquals(
        drafts.getAllValues().getFirst().contentGeneration(),
        drafts.getAllValues().getLast().contentGeneration());
    assertNotEquals(
        drafts.getAllValues().getFirst().requestUuid(),
        drafts.getAllValues().getLast().requestUuid());
    assertNull(drafts.getAllValues().getFirst().requestedScannerSnapshotId());
    assertNull(drafts.getAllValues().getLast().requestedScannerSnapshotId());
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

    ScannerSnapshot unknownAge = new ScannerSnapshot(
        31L,
        "adapter",
        ScannerContract.API_VERSION,
        "grype",
        "2",
        "db-1",
        null,
        "cap",
        "fingerprint-31",
        Instant.now(),
        true,
        Map.of());
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(unknownAge));
    assertEquals(
        "SCANNER_DATABASE_AGE_UNKNOWN",
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
    Instant persistedFutureObservation = Instant.now().plus(Duration.ofDays(2));
    ScannerSnapshot futureSnapshot = new ScannerSnapshot(
        7L,
        "adapter",
        ScannerContract.API_VERSION,
        "grype",
        "2",
        "future-db",
        Instant.now(),
        "capability",
        "future-fingerprint",
        persistedFutureObservation,
        true,
        Map.of());
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(futureSnapshot));
    Capabilities capabilities = new Capabilities(
        ScannerContract.API_VERSION,
        "adapter",
        "1",
        List.of("CATALOG", "MATCH"),
        List.of("PACKAGE"),
        1024,
        2048,
        "capability");
    Instant adapterObservedAt = Instant.now().plus(Duration.ofDays(1));
    Readiness readiness = new Readiness(
        true,
        "READY",
        "grype",
        "2",
        "db-2",
        Instant.now(),
        adapterObservedAt,
        Map.of("catalogEngineVersion", "1"));
    when(adapter.observation()).thenReturn(new Observation(capabilities, readiness));
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
    ScannerSnapshot matched = service.snapshotFor(matchResponse(), observed);

    assertEquals(8L, observed.id());
    assertEquals("grype", matched.engineName());
    assertEquals("1", matched.details().get("catalogEngineVersion"));
    assertEquals(List.of("CATALOG", "MATCH"), matched.details().get("operations"));

    when(adapter.observation()).thenReturn(new Observation(
        new Capabilities(
            "v2", "adapter", "1", List.of(), List.of(), 1, 1, "cap"),
        readiness));
    assertEquals(
        "SCANNER_API_UNSUPPORTED",
        assertThrows(ScannerAdapterException.class, service::readySnapshot).code());

    when(adapter.observation())
        .thenThrow(new ScannerAdapterException("ADAPTER_DOWN", "down", true));
    assertEquals(
        "ADAPTER_DOWN",
        assertThrows(ScannerAdapterException.class, service::readySnapshot).code());
    org.mockito.Mockito.doReturn(null).when(adapter).observation();
    ScannerAdapterException observationFailure =
        assertThrows(ScannerAdapterException.class, service::readySnapshot);
    assertEquals("SCANNER_OBSERVATION_FAILED", observationFailure.code());
    assertTrue(observationFailure.retryable());
    assertTrue(observationFailure.getCause() instanceof NullPointerException);
    ArgumentCaptor<ScannerSnapshot> snapshots =
        ArgumentCaptor.forClass(ScannerSnapshot.class);
    verify(scans, org.mockito.Mockito.atLeast(4))
        .insertSnapshotOrFindExisting(snapshots.capture());
    ScannerSnapshot receivedObservation = snapshots.getAllValues().getFirst();
    assertTrue(
        receivedObservation.observedAt().isBefore(adapterObservedAt),
        "adapter clock time must not become the shared freshness timestamp");
    assertEquals(
        adapterObservedAt.toString(),
        receivedObservation.details().get("adapterObservedAt"));
    assertEquals(false, snapshots.getAllValues().getLast().ready());
    assertEquals(
        "SCANNER_OBSERVATION_FAILED",
        snapshots.getAllValues().getLast().details().get("reasonCode"));
  }

  @Test
  void snapshotWatcherSchedulesOnlyCurrentAssetsAndAuditsAChangedSnapshot() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    SecurityScannerSnapshotService snapshots = mock(SecurityScannerSnapshotService.class);
    SecurityScannerSnapshotRematchService rematches =
        mock(SecurityScannerSnapshotRematchService.class);
    SecurityScanAuditService audit = mock(SecurityScanAuditService.class);
    SecurityScannerSnapshotWatcher watcher =
        new SecurityScannerSnapshotWatcher(scans, snapshots, rematches, audit);
    ScannerSnapshot previous =
        snapshot(1L, true, "db-1", Instant.now(), "old");
    ScannerSnapshot current =
        snapshot(2L, true, "db-2", Instant.now(), "new");
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(previous));
    when(snapshots.readySnapshot()).thenReturn(current);
    ScanProfile profile = profile(3L, true);
    when(scans.listProfiles()).thenReturn(List.of(profile, profile(4L, false)));
    when(rematches.reconcileProfile(profile, current)).thenReturn(2);

    watcher.reconcile();

    verify(rematches).reconcileProfile(profile, current);
    verify(audit).recordSystem(
        eq("SCANNER_SNAPSHOT_CHANGED"), eq(null), any());

    when(snapshots.readySnapshot())
        .thenThrow(new ScannerAdapterException("DOWN", "down", true))
        .thenThrow(new IllegalStateException("down"));
    watcher.reconcile();
    watcher.reconcile();
  }

  @Test
  void snapshotRematchPersistsProgressPastStillEligibleEarlyAssets() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    AssetDao assets = mock(AssetDao.class);
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setSnapshotRematchBatchSize(1);
    properties.getWorker().setSnapshotRematchMaxBatches(2);
    SecurityScannerSnapshotRematchService rematches =
        new SecurityScannerSnapshotRematchService(scans, assets, cursors, properties);
    ScanProfile profile = profile(3L, true);
    ScannerSnapshot snapshot = snapshot(2L, true, "db-2", Instant.now(), "new");
    String cursorName = SecurityScannerSnapshotRematchService.cursorName(3L, 2L);
    AtomicLong durableCursor = new AtomicLong();
    when(cursors.tryLockLastSeenId(cursorName))
        .thenAnswer(invocation -> OptionalLong.of(durableCursor.get()));
    when(cursors.updateLastSeenId(eq(cursorName), anyLong())).thenAnswer(invocation -> {
      durableCursor.set(invocation.getArgument(1));
      return 1;
    });
    when(scans.listAssetStatesNeedingSnapshot(eq(3L), eq(2L), anyLong(), eq(1)))
        .thenAnswer(invocation -> {
          long after = invocation.getArgument(2);
          if (after >= 14) {
            return List.of();
          }
          long assetId = after == 0 ? 11 : after + 1;
          return List.of(state(assetId, 3L, 1L, 100L + assetId));
        });
    for (long assetId = 11; assetId <= 14; assetId++) {
      long blobId = 200 + assetId;
      AssetWithBlob currentContent = content(assetId, 7, blobId);
      when(assets.findAssetWithBlobById(assetId))
          .thenReturn(Optional.of(currentContent));
      when(scans.findCandidate(assetId))
          .thenReturn(Optional.of(
              new ScanCandidate(assetId, blobId, 1, 0, Instant.now(), Instant.now())));
    }

    assertEquals(2, rematches.reconcileProfile(profile, snapshot));
    assertEquals(12, durableCursor.get());
    assertEquals(2, rematches.reconcileProfile(profile, snapshot));
    assertEquals(14, durableCursor.get());

    ArgumentCaptor<TaskDraft> drafts = ArgumentCaptor.forClass(TaskDraft.class);
    verify(scans, org.mockito.Mockito.times(4)).createTask(drafts.capture());
    assertEquals(4, drafts.getAllValues().stream()
        .map(TaskDraft::requestUuid)
        .distinct()
        .count());
    verify(scans, org.mockito.Mockito.times(4))
        .reactivateSnapshotTask(eq(0L), eq(2L), any(), eq("security-scan-worker"));
    verify(scans).listAssetStatesNeedingSnapshot(3L, 2L, 12L, 1);
  }

  @Test
  void snapshotRematchRequeuesAFirstScanAfterObservationRecovers() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setSnapshotRematchBatchSize(10);
    properties.getWorker().setSnapshotRematchMaxBatches(1);
    SecurityScannerSnapshotRematchService rematches =
        new SecurityScannerSnapshotRematchService(
            scans, mock(AssetDao.class), cursors, properties);
    ScanProfile profile = profile(3L, true);
    ScannerSnapshot snapshot = snapshot(2L, true, "db-2", Instant.now(), "new");
    AssetSecurityState failed = new AssetSecurityState(
        11L,
        3L,
        1L,
        new byte[32],
        null,
        ScanState.FAILED,
        ScanCompleteness.UNKNOWN,
        false,
        com.github.klboke.kkrepo.security.scan.ScanEnums.Severity.UNKNOWN,
        Map.of(),
        null,
        null,
        com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision.ALLOW,
        SCANNER_OBSERVATION_UNAVAILABLE,
        null,
        Instant.now(),
        1L);
    String cursorName = SecurityScannerSnapshotRematchService.cursorName(3L, 2L);
    when(cursors.tryLockLastSeenId(cursorName)).thenReturn(OptionalLong.of(0));
    when(cursors.updateLastSeenId(cursorName, 11L)).thenReturn(1);
    when(scans.listAssetStatesNeedingSnapshot(3L, 2L, 0, 10))
        .thenReturn(List.of(failed));
    when(scans.requeueCandidateAfterObservationFailure(eq(11L), eq(3L), eq(1L), any()))
        .thenReturn(true);

    assertEquals(1, rematches.reconcileProfile(profile, snapshot));

    verify(scans)
        .requeueCandidateAfterObservationFailure(eq(11L), eq(3L), eq(1L), any());
    verify(cursors).updateLastSeenId(cursorName, 11L);
  }

  @Test
  void policyReconcilerSchedulesFreshAndPolicyOnlyWorkAndMaterializesTerminalAssets() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    SecurityScanCandidateClassifier classifier = mock(SecurityScanCandidateClassifier.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    SecurityPolicyReconciler reconciler =
        new SecurityPolicyReconciler(
            scans, repositories, assets, classifier, properties, mock(MaintenanceCursorDao.class));
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
        new PolicyEvaluationTarget(11, 7, 1, null, null, ScanState.PENDING, 2, null, 4);
    PolicyEvaluationTarget reusable =
        new PolicyEvaluationTarget(
            11, 7, 1, 1L, 44L, ScanState.COMPLETE, 3, now.plusSeconds(30), 4);
    PolicyEvaluationTarget reusableAfterWaiver =
        new PolicyEvaluationTarget(
            11, 7, 1, 1L, 44L, ScanState.COMPLETE, 3, now.plusSeconds(30), 5);
    PolicyEvaluationTarget ageExpired =
        new PolicyEvaluationTarget(
            11,
            7,
            1,
            1L,
            44L,
            ScanState.COMPLETE,
            3,
            now.minusSeconds(1),
            null,
            5);
    reconciler.reconcile(context, profile, fresh, now);
    reconciler.reconcile(context, profile, reusable, now);
    reconciler.reconcile(context, profile, reusableAfterWaiver, now);
    reconciler.reconcile(context, profile, ageExpired, now);

    ArgumentCaptor<TaskDraft> drafts = ArgumentCaptor.forClass(TaskDraft.class);
    verify(scans, org.mockito.Mockito.times(4)).createTask(drafts.capture());
    assertEquals(ScanStage.CATALOG_AND_MATCH, drafts.getAllValues().getFirst().stage());
    assertEquals(ScanStage.POLICY_ONLY, drafts.getAllValues().get(1).stage());
    assertEquals(ScanStage.POLICY_ONLY, drafts.getAllValues().get(2).stage());
    assertEquals(ScanStage.MATCH_ONLY, drafts.getAllValues().getLast().stage());
    assertEquals(
        RequestReason.MAX_AGE_EXPIRED,
        drafts.getAllValues().getLast().requestReason());
    assertNotEquals(
        drafts.getAllValues().get(1).requestUuid(),
        drafts.getAllValues().get(2).requestUuid());
  }

  @Test
  void policyReconcilerTraversesGroupSourcesAndPropagatesBatchFailuresForRollback() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    SecurityScanCandidateClassifier classifier = mock(SecurityScanCandidateClassifier.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    SecurityPolicyReconciler reconciler =
        new SecurityPolicyReconciler(
            scans, repositories, assets, classifier, properties, cursors);
    RepositoryRecord group = repository(100, RepositoryType.GROUP);
    RepositoryRecord member = repository(101, RepositoryType.HOSTED);
    RepositoryScanConfig config = config(100, 3);
    ScanProfile profile = profile(3L, true);
    when(repositories.list()).thenReturn(List.of(group, member));
    when(repositories.listAllGroupMembers())
        .thenReturn(Map.of(100L, List.of("repository-101")));
    when(scans.findRepositoryConfigs(List.of(100L, 101L))).thenReturn(List.of(config));
    when(scans.listProfiles()).thenReturn(List.of(profile));
    when(scans.listPolicies()).thenReturn(List.of());
    when(cursors.tryLockLastSeenId(SecurityPolicyReconciler.WORK_CURSOR))
        .thenReturn(OptionalLong.of(0));
    when(cursors.tryLockLastSeenId(
        SecurityPolicyReconciler.assetCursorName(100L, 101L)))
        .thenReturn(OptionalLong.of(0));
    when(cursors.updateLastSeenId(any(), anyLong())).thenReturn(1);
    when(scans.listPolicyEvaluationTargets(
        eq(101L), eq(100L), eq(3L), anyLong(), eq(null), eq(null), eq(0L), any(), anyInt()))
        .thenReturn(List.of());

    reconciler.runOnce();

    verify(scans).listPolicyEvaluationTargets(
        eq(101L), eq(100L), eq(3L), anyLong(), eq(null), eq(null), eq(0L), any(), anyInt());
    when(repositories.list()).thenThrow(new IllegalStateException("temporary"));
    assertThrows(IllegalStateException.class, reconciler::runOnce);
  }

  @Test
  void policyReconcilerChargesEmptyContextsAgainstTheVisitBudget() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setSnapshotRematchBatchSize(10);
    properties.getWorker().setSnapshotRematchMaxBatches(2);
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    SecurityPolicyReconciler reconciler = new SecurityPolicyReconciler(
        scans,
        repositories,
        mock(AssetDao.class),
        mock(SecurityScanCandidateClassifier.class),
        properties,
        cursors);
    RepositoryRecord first = repository(10, RepositoryType.HOSTED);
    RepositoryRecord second = repository(20, RepositoryType.HOSTED);
    RepositoryRecord third = repository(30, RepositoryType.HOSTED);
    when(repositories.list()).thenReturn(List.of(first, second, third));
    when(repositories.listAllGroupMembers()).thenReturn(Map.of());
    when(scans.findRepositoryConfigs(List.of(10L, 20L, 30L))).thenReturn(List.of(
        config(10, 3), config(20, 3), config(30, 3)));
    when(scans.listProfiles()).thenReturn(List.of(profile(3L, true)));
    when(scans.listPolicies()).thenReturn(List.of());
    when(cursors.tryLockLastSeenId(any())).thenReturn(OptionalLong.of(0));
    when(cursors.updateLastSeenId(any(), anyLong())).thenReturn(1);

    reconciler.runOnce();

    verify(scans).listPolicyEvaluationTargets(
        eq(10L), eq(10L), eq(3L), anyLong(), eq(null), eq(null), eq(0L), any(), eq(10));
    verify(scans).listPolicyEvaluationTargets(
        eq(20L), eq(20L), eq(3L), anyLong(), eq(null), eq(null), eq(0L), any(), eq(10));
    verify(scans, org.mockito.Mockito.never()).listPolicyEvaluationTargets(
        eq(30L), eq(30L), eq(3L), anyLong(), eq(null), eq(null), eq(0L), any(), anyInt());
    verify(cursors).updateLastSeenId(SecurityPolicyReconciler.WORK_CURSOR, 2L);
  }

  @Test
  void policyReconcilerRotatesAOneItemBudgetAcrossRepositoryContexts() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    AssetDao assets = mock(AssetDao.class);
    SecurityScanCandidateClassifier classifier = mock(SecurityScanCandidateClassifier.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setSnapshotRematchBatchSize(1);
    properties.getWorker().setSnapshotRematchMaxBatches(1);
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    SecurityPolicyReconciler reconciler =
        new SecurityPolicyReconciler(
            scans, repositories, assets, classifier, properties, cursors);
    RepositoryRecord first = repository(10, RepositoryType.HOSTED);
    RepositoryRecord second = repository(20, RepositoryType.HOSTED);
    RepositoryScanConfig firstConfig = config(10, 3);
    RepositoryScanConfig secondConfig = config(20, 3);
    ScanProfile profile = profile(3L, true);
    when(repositories.list()).thenReturn(List.of(first, second));
    when(repositories.listAllGroupMembers()).thenReturn(Map.of());
    when(scans.findRepositoryConfigs(List.of(10L, 20L)))
        .thenReturn(List.of(firstConfig, secondConfig));
    when(scans.listProfiles()).thenReturn(List.of(profile));
    when(scans.listPolicies()).thenReturn(List.of());
    Map<String, Long> durableCursors = new HashMap<>();
    when(cursors.tryLockLastSeenId(any())).thenAnswer(invocation ->
        OptionalLong.of(durableCursors.getOrDefault(invocation.getArgument(0), 0L)));
    when(cursors.updateLastSeenId(any(), anyLong())).thenAnswer(invocation -> {
      durableCursors.put(invocation.getArgument(0), invocation.getArgument(1));
      return 1;
    });
    Instant now = Instant.now();
    PolicyEvaluationTarget firstTarget =
        new PolicyEvaluationTarget(101, 10, 1, null, null, ScanState.PENDING, 0, null);
    PolicyEvaluationTarget secondTarget =
        new PolicyEvaluationTarget(201, 20, 1, null, null, ScanState.PENDING, 0, null);
    when(scans.listPolicyEvaluationTargets(
        eq(10L), eq(10L), eq(3L), anyLong(), eq(null), eq(null), eq(0L), any(), eq(1)))
        .thenReturn(List.of(firstTarget));
    when(scans.listPolicyEvaluationTargets(
        eq(20L), eq(20L), eq(3L), anyLong(), eq(null), eq(null), eq(0L), any(), eq(1)))
        .thenReturn(List.of(secondTarget));
    AssetWithBlob firstContent = content(101, 10, 301);
    AssetWithBlob secondContent = content(201, 20, 401);
    when(assets.findAssetWithBlobById(101L)).thenReturn(Optional.of(firstContent));
    when(assets.findAssetWithBlobById(201L)).thenReturn(Optional.of(secondContent));
    when(scans.findCandidate(101L))
        .thenReturn(Optional.of(new ScanCandidate(101, 301L, 1, 0, now, now)));
    when(scans.findCandidate(201L))
        .thenReturn(Optional.of(new ScanCandidate(201, 401L, 1, 0, now, now)));
    when(classifier.classify(any(), any(), eq(profile)))
        .thenReturn(classification(CandidateDisposition.SCANNABLE));

    reconciler.runOnce();
    reconciler.runOnce();

    verify(scans).listPolicyEvaluationTargets(
        eq(10L), eq(10L), eq(3L), anyLong(), eq(null), eq(null), eq(0L), any(), eq(1));
    verify(scans).listPolicyEvaluationTargets(
        eq(20L), eq(20L), eq(3L), anyLong(), eq(null), eq(null), eq(0L), any(), eq(1));
    verify(scans, org.mockito.Mockito.times(2)).createTask(any());
    assertEquals(2, durableCursors.get(SecurityPolicyReconciler.WORK_CURSOR));
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
    when(repository.name()).thenReturn("repository-" + id);
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
        "capability",
        ScanCompleteness.COMPLETE,
        "{}".getBytes(),
        List.of(),
        Map.of());
  }
}
