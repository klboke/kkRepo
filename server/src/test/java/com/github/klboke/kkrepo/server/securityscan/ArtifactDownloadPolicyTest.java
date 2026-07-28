package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetPolicyState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.DownloadPolicySnapshot;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.OciPlatformPolicy;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArtifactDownloadPolicyTest {
  private final SecurityScanDao scans = mock(SecurityScanDao.class);
  private final SecurityScanningProperties properties = new SecurityScanningProperties();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private ArtifactDownloadPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new ArtifactDownloadPolicy(
        scans,
        new SecurityScanCandidateClassifier(),
        properties,
        new SecurityScanMetrics(registry, scans, properties));
  }

  @Test
  void disabledFeatureNeverTouchesTheHotPathDatabase() {
    assertEquals(PolicyDecision.ALLOW, policy.beforeRead(10L, 1L).decision());
    verifyNoInteractions(scans);
  }

  @Test
  void missingAssetOrConfigurationAllowsAfterOneSnapshotLookup() {
    properties.setEnabled(true);
    when(scans.findDownloadPolicySnapshots(10L, null)).thenReturn(List.of());

    assertEquals(PolicyDecision.ALLOW, policy.beforeRead(10L, null).decision());

    verify(scans).findDownloadPolicySnapshots(10L, null);
    verifyNoMoreInteractions(scans);
  }

  @Test
  void disabledRepositoryConfigurationAllowsAfterOneSnapshotLookup() {
    properties.setEnabled(true);
    when(scans.findDownloadPolicySnapshots(10L, 1L)).thenReturn(List.of(snapshot(
        disabledConfig(1L),
        profile(),
        null,
        null,
        null,
        "com/acme/demo/1/demo-1.jar",
        "artifact",
        "application/java-archive")));

    assertEquals(PolicyDecision.ALLOW, policy.beforeRead(10L, 1L).decision());

    assertSingleSnapshotLookup(1L);
  }

  @Test
  void snapshotFailureIsPropagatedAndTimedAsAnError() {
    properties.setEnabled(true);
    when(scans.findDownloadPolicySnapshots(10L, 1L))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThrows(IllegalStateException.class, () -> policy.beforeRead(10L, 1L));

    assertEquals(
        1L,
        registry.get("kkrepo_security_policy_evaluation_duration_seconds")
            .tags("format", "unknown", "outcome", "error")
            .timer()
            .count());
    assertSingleSnapshotLookup(1L);
  }

  @Test
  void auditModeRecordsShadowBlockWithoutChangingTheResponse() {
    arrange(EnforcementMode.AUDIT, PolicyAction.ALLOW, complete(PolicyDecision.BLOCK_VULNERABILITY));

    ArtifactDownloadPolicy.Decision decision = policy.beforeRead(10L, 1L);

    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, decision.decision());
    assertFalse(decision.enforced());
    assertTrue(decision.shadowBlocked());
    assertSingleSnapshotLookup(1L);
  }

  @Test
  void enforceModeBlocksAConfirmedVulnerability() {
    arrange(EnforcementMode.ENFORCE, PolicyAction.ALLOW, complete(PolicyDecision.BLOCK_VULNERABILITY));

    ArtifactPolicyException failure =
        assertThrows(ArtifactPolicyException.class, () -> policy.beforeRead(10L, 1L));

    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, failure.decision());
    assertFalse(failure.pending());
    assertSingleSnapshotLookup(1L);
  }

  @Test
  void generationMismatchUsesTheConfiguredPendingAction() {
    arrange(EnforcementMode.ENFORCE, PolicyAction.BLOCK, complete(PolicyDecision.ALLOW));
    when(scans.findDownloadPolicySnapshots(10L, 1L)).thenReturn(List.of(snapshot(
        config(1L, EnforcementMode.ENFORCE, PolicyAction.BLOCK),
        profile(),
        new ScanCandidate(10L, 100L, 2L, 2L, Instant.EPOCH, Instant.EPOCH),
        complete(PolicyDecision.ALLOW),
        policyState(1L, PolicyDecision.ALLOW),
        "com/acme/demo/1/demo-1.jar",
        "artifact",
        "application/java-archive")));

    ArtifactPolicyException failure =
        assertThrows(ArtifactPolicyException.class, () -> policy.beforeRead(10L, 1L));

    assertEquals(PolicyDecision.BLOCK_PENDING, failure.decision());
    assertTrue(failure.pending());
    assertSingleSnapshotLookup(1L);
  }

  @Test
  void globalWaiverWatermarkFencesAnOlderMaterializedDecision() {
    properties.setEnabled(true);
    RepositoryScanConfig config =
        config(1L, EnforcementMode.ENFORCE, PolicyAction.BLOCK);
    when(scans.findDownloadPolicySnapshots(10L, 1L)).thenReturn(List.of(snapshot(
        config,
        profile(),
        new ScanCandidate(10L, 100L, 1L, 1L, Instant.EPOCH, Instant.EPOCH),
        complete(PolicyDecision.ALLOW),
        policyState(1L, PolicyDecision.ALLOW, 4),
        "com/acme/demo/1/demo-1.jar",
        "artifact",
        "application/java-archive",
        5)));

    ArtifactPolicyException failure =
        assertThrows(ArtifactPolicyException.class, () -> policy.beforeRead(10L, 1L));

    assertEquals(PolicyDecision.BLOCK_PENDING, failure.decision());
    assertSingleSnapshotLookup(1L);
  }

  @Test
  void entryGroupAndMemberPoliciesChooseTheStricterEnforcedDecision() {
    arrange(EnforcementMode.AUDIT, PolicyAction.ALLOW, complete(PolicyDecision.BLOCK_VULNERABILITY));
    ScanCandidate candidate =
        new ScanCandidate(10L, 100L, 1L, 1L, Instant.EPOCH, Instant.EPOCH);
    AssetSecurityState state = complete(PolicyDecision.BLOCK_VULNERABILITY);
    when(scans.findDownloadPolicySnapshots(10L, 2L)).thenReturn(List.of(
        snapshot(
            config(1L, EnforcementMode.AUDIT, PolicyAction.ALLOW),
            profile(),
            candidate,
            state,
            policyState(1L, PolicyDecision.BLOCK_VULNERABILITY),
            "com/acme/demo/1/demo-1.jar",
            "artifact",
            "application/java-archive"),
        snapshot(
            config(2L, EnforcementMode.ENFORCE, PolicyAction.ALLOW),
            profile(),
            candidate,
            state,
            policyState(2L, PolicyDecision.BLOCK_VULNERABILITY),
            "com/acme/demo/1/demo-1.jar",
            "artifact",
            "application/java-archive")));

    ArtifactPolicyException failure =
        assertThrows(ArtifactPolicyException.class, () -> policy.beforeRead(10L, 2L));

    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, failure.decision());
    assertSingleSnapshotLookup(2L);
  }

  @Test
  void sharedDockerBlobUsesOneBatchQueryAndTheStrictestManifestDecision() {
    properties.setEnabled(true);
    List<Long> manifestAssetIds = List.of(10L, 11L);
    when(scans.findDownloadPolicySnapshots(manifestAssetIds, null)).thenReturn(List.of(snapshot(
        config(1L, EnforcementMode.ENFORCE, PolicyAction.ALLOW),
        profile(),
        new ScanCandidate(10L, 100L, 1L, 1L, Instant.EPOCH, Instant.EPOCH),
        complete(PolicyDecision.BLOCK_VULNERABILITY),
        policyState(1L, PolicyDecision.BLOCK_VULNERABILITY),
        "com/acme/demo/1/demo-1.jar",
        "artifact",
        "application/java-archive")));

    ArtifactPolicyException failure = assertThrows(
        ArtifactPolicyException.class,
        () -> policy.beforeReadAll(manifestAssetIds));

    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, failure.decision());
    verify(scans).findDownloadPolicySnapshots(manifestAssetIds, null);
    verifyNoMoreInteractions(scans);
  }

  @Test
  void protocolMetadataIsNotApplicableAndAlwaysAllowed() {
    properties.setEnabled(true);
    when(scans.findDownloadPolicySnapshots(10L, 1L)).thenReturn(List.of(snapshot(
        config(1L, EnforcementMode.ENFORCE, PolicyAction.BLOCK),
        profile(),
        null,
        null,
        null,
        "com/acme/maven-metadata.xml",
        "metadata",
        "application/xml")));

    ArtifactDownloadPolicy.Decision decision = policy.beforeRead(10L, 1L);

    assertEquals(PolicyDecision.ALLOW, decision.decision());
    assertTrue(decision.enforced());
    assertSingleSnapshotLookup(1L);
  }

  @Test
  void lifecycleStatesUseTheirConfiguredFailureActions() {
    arrange(EnforcementMode.ENFORCE, PolicyAction.BLOCK, complete(PolicyDecision.ALLOW));
    RepositoryScanConfig config =
        config(1L, EnforcementMode.ENFORCE, PolicyAction.BLOCK);
    ScanCandidate candidate =
        new ScanCandidate(10L, 100L, 1L, 1L, Instant.EPOCH, Instant.EPOCH);
    when(scans.findDownloadPolicySnapshots(10L, 1L))
        .thenReturn(
            List.of(snapshot(
                config, profile(), candidate, state(ScanState.NOT_APPLICABLE), null,
                "com/acme/demo/1/demo-1.jar", "artifact", "application/java-archive")),
            List.of(snapshot(
                config, profile(), candidate, state(ScanState.PENDING), null,
                "com/acme/demo/1/demo-1.jar", "artifact", "application/java-archive")),
            List.of(snapshot(
                config, profile(), candidate, state(ScanState.FAILED), null,
                "com/acme/demo/1/demo-1.jar", "artifact", "application/java-archive")),
            List.of(snapshot(
                config, profile(), candidate, state(ScanState.PARTIAL), null,
                "com/acme/demo/1/demo-1.jar", "artifact", "application/java-archive")));

    assertEquals(PolicyDecision.ALLOW, policy.beforeRead(10L, 1L).decision());
    assertEquals(
        PolicyDecision.BLOCK_PENDING,
        assertThrows(ArtifactPolicyException.class, () -> policy.beforeRead(10L, 1L))
            .decision());
    assertEquals(
        PolicyDecision.BLOCK_SCAN_FAILED,
        assertThrows(ArtifactPolicyException.class, () -> policy.beforeRead(10L, 1L))
            .decision());
    assertEquals(
        PolicyDecision.BLOCK_PARTIAL,
        assertThrows(ArtifactPolicyException.class, () -> policy.beforeRead(10L, 1L))
            .decision());
    verify(scans, times(4)).findDownloadPolicySnapshots(10L, 1L);
    verifyNoMoreInteractions(scans);
  }

  @Test
  void missingEnabledProfileUsesTheFailureAction() {
    arrange(EnforcementMode.ENFORCE, PolicyAction.ALLOW, complete(PolicyDecision.ALLOW));
    when(scans.findDownloadPolicySnapshots(10L, 1L)).thenReturn(List.of(snapshot(
        config(1L, EnforcementMode.ENFORCE, PolicyAction.ALLOW),
        null,
        null,
        null,
        null,
        "com/acme/demo/1/demo-1.jar",
        "artifact",
        "application/java-archive")));

    assertEquals(
        PolicyDecision.BLOCK_SCAN_FAILED,
        assertThrows(ArtifactPolicyException.class, () -> policy.beforeRead(10L, 1L))
            .decision());
    assertSingleSnapshotLookup(1L);
  }

  private void arrange(
      EnforcementMode mode, PolicyAction pendingAction, AssetSecurityState state) {
    properties.setEnabled(true);
    when(scans.findDownloadPolicySnapshots(10L, 1L)).thenReturn(List.of(snapshot(
        config(1L, mode, pendingAction),
        profile(),
        new ScanCandidate(10L, 100L, 1L, 1L, Instant.EPOCH, Instant.EPOCH),
        state,
        policyState(1L, state.policyDecision()),
        "com/acme/demo/1/demo-1.jar",
        "artifact",
        "application/java-archive")));
  }

  private void assertSingleSnapshotLookup(long entryRepositoryId) {
    verify(scans).findDownloadPolicySnapshots(10L, entryRepositoryId);
    verifyNoMoreInteractions(scans);
  }

  private static DownloadPolicySnapshot snapshot(
      RepositoryScanConfig config,
      ScanProfile profile,
      ScanCandidate candidate,
      AssetSecurityState state,
      AssetPolicyState policyState,
      String path,
      String kind,
      String contentType) {
    return snapshot(
        config,
        profile,
        candidate,
        state,
        policyState,
        path,
        kind,
        contentType,
        0);
  }

  private static DownloadPolicySnapshot snapshot(
      RepositoryScanConfig config,
      ScanProfile profile,
      ScanCandidate candidate,
      AssetSecurityState state,
      AssetPolicyState policyState,
      String path,
      String kind,
      String contentType,
      long requiredWaiverRevision) {
    return new DownloadPolicySnapshot(
        10L,
        1L,
        RepositoryFormat.MAVEN2,
        path,
        kind,
        contentType,
        42L,
        config,
        profile,
        candidate,
        state,
        null,
        policyState,
        requiredWaiverRevision);
  }

  private static RepositoryScanConfig config(
      long repositoryId, EnforcementMode mode, PolicyAction pendingAction) {
    return new RepositoryScanConfig(
        repositoryId, true, 1L, true, true, mode, pendingAction,
        PolicyAction.BLOCK, PolicyAction.BLOCK, 86400L, null, 1L,
        Instant.EPOCH, Instant.EPOCH);
  }

  private static RepositoryScanConfig disabledConfig(long repositoryId) {
    return new RepositoryScanConfig(
        repositoryId, false, 1L, true, true, EnforcementMode.AUDIT,
        PolicyAction.ALLOW, PolicyAction.ALLOW, PolicyAction.ALLOW,
        86400L, null, 1L, Instant.EPOCH, Instant.EPOCH);
  }

  private static AssetSecurityState complete(PolicyDecision decision) {
    return new AssetSecurityState(
        10L, 1L, 1L, new byte[32], 20L, ScanState.COMPLETE,
        ScanCompleteness.COMPLETE, true, Severity.CRITICAL,
        Map.of("critical", 1), null, null, decision, decision.name(),
        Instant.MAX, Instant.EPOCH, 1L);
  }

  private static AssetSecurityState state(ScanState state) {
    return new AssetSecurityState(
        10L, 1L, 1L, new byte[32], 20L, state,
        state == ScanState.PARTIAL ? ScanCompleteness.PARTIAL : ScanCompleteness.UNKNOWN,
        false, Severity.UNKNOWN, Map.of(), null, null, PolicyDecision.ALLOW, state.name(),
        Instant.MAX, Instant.EPOCH, 1L);
  }

  private static AssetPolicyState policyState(
      long repositoryId, PolicyDecision decision) {
    return policyState(repositoryId, decision, 0);
  }

  private static AssetPolicyState policyState(
      long repositoryId, PolicyDecision decision, long waiverRevision) {
    return new AssetPolicyState(
        10L, 1L, repositoryId, 1L, 20L, null, null, 1L,
        decision, decision.name(), 0, Instant.MAX, null, Instant.EPOCH, 1L,
        waiverRevision);
  }

  private static ScanProfile profile() {
    return new ScanProfile(
        1L, "default", true, "syft", "grype", List.of("vuln"), Map.of(),
        1024L, 100, 4096L, 1024L, 2, 30,
        OciPlatformPolicy.REQUIRED_SET, List.of("linux/amd64"),
        "a".repeat(64), 1L, Instant.EPOCH, Instant.EPOCH);
  }
}
