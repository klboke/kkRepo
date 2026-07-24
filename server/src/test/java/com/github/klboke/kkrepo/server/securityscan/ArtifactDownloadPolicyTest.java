package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetPolicyState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArtifactDownloadPolicyTest {
  private final SecurityScanDao scans = mock(SecurityScanDao.class);
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final AssetDao assets = mock(AssetDao.class);
  private final SecurityScanningProperties properties = new SecurityScanningProperties();
  private ArtifactDownloadPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new ArtifactDownloadPolicy(
        scans,
        repositories,
        assets,
        new SecurityScanCandidateClassifier(),
        properties,
        new SecurityScanMetrics(new SimpleMeterRegistry(), scans));
  }

  @Test
  void disabledFeatureNeverTouchesTheHotPathDatabase() {
    assertEquals(PolicyDecision.ALLOW, policy.beforeRead(10L, 1L).decision());
    verifyNoInteractions(assets, repositories, scans);
  }

  @Test
  void auditModeRecordsShadowBlockWithoutChangingTheResponse() {
    arrange(EnforcementMode.AUDIT, PolicyAction.ALLOW, complete(PolicyDecision.BLOCK_VULNERABILITY));

    ArtifactDownloadPolicy.Decision decision = policy.beforeRead(10L, 1L);

    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, decision.decision());
    assertFalse(decision.enforced());
    assertTrue(decision.shadowBlocked());
  }

  @Test
  void enforceModeBlocksAConfirmedVulnerability() {
    arrange(EnforcementMode.ENFORCE, PolicyAction.ALLOW, complete(PolicyDecision.BLOCK_VULNERABILITY));

    ArtifactPolicyException failure =
        assertThrows(ArtifactPolicyException.class, () -> policy.beforeRead(10L, 1L));

    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, failure.decision());
    assertFalse(failure.pending());
  }

  @Test
  void generationMismatchUsesTheConfiguredPendingAction() {
    arrange(EnforcementMode.ENFORCE, PolicyAction.BLOCK, complete(PolicyDecision.ALLOW));
    when(scans.findCandidate(10L)).thenReturn(Optional.of(
        new ScanCandidate(10L, 100L, 2L, 2L, Instant.EPOCH, Instant.EPOCH)));

    ArtifactPolicyException failure =
        assertThrows(ArtifactPolicyException.class, () -> policy.beforeRead(10L, 1L));

    assertEquals(PolicyDecision.BLOCK_PENDING, failure.decision());
    assertTrue(failure.pending());
  }

  @Test
  void entryGroupAndMemberPoliciesChooseTheStricterEnforcedDecision() {
    arrange(EnforcementMode.AUDIT, PolicyAction.ALLOW, complete(PolicyDecision.BLOCK_VULNERABILITY));
    when(repositories.findById(2L)).thenReturn(Optional.of(repository(2L, RepositoryType.GROUP)));
    when(scans.findRepositoryConfig(2L)).thenReturn(Optional.of(
        config(2L, EnforcementMode.ENFORCE, PolicyAction.ALLOW)));
    when(scans.findAssetPolicyState(10L, 1L, 2L)).thenReturn(Optional.of(
        policyState(2L, PolicyDecision.BLOCK_VULNERABILITY)));

    ArtifactPolicyException failure =
        assertThrows(ArtifactPolicyException.class, () -> policy.beforeRead(10L, 2L));

    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, failure.decision());
  }

  @Test
  void protocolMetadataIsNotApplicableAndAlwaysAllowed() {
    properties.setEnabled(true);
    AssetRecord metadata = new AssetRecord(
        10L, 1L, null, 100L, RepositoryFormat.MAVEN2,
        "com/acme/maven-metadata.xml",
        PersistenceHashes.pathHash("com/acme/maven-metadata.xml"),
        "maven-metadata.xml", "metadata", "application/xml", 42L, null,
        Instant.EPOCH, Map.of());
    when(assets.findAssetWithBlobById(10L))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(metadata, blob())));
    when(repositories.findById(1L))
        .thenReturn(Optional.of(repository(1L, RepositoryType.HOSTED)));
    when(scans.findRepositoryConfig(1L)).thenReturn(Optional.of(
        config(1L, EnforcementMode.ENFORCE, PolicyAction.BLOCK)));
    when(scans.findProfile(1L)).thenReturn(Optional.of(profile()));

    ArtifactDownloadPolicy.Decision decision = policy.beforeRead(10L, 1L);

    assertEquals(PolicyDecision.ALLOW, decision.decision());
    assertTrue(decision.enforced());
  }

  private void arrange(
      EnforcementMode mode, PolicyAction pendingAction, AssetSecurityState state) {
    properties.setEnabled(true);
    when(assets.findAssetWithBlobById(10L))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(asset(), blob())));
    when(repositories.findById(1L))
        .thenReturn(Optional.of(repository(1L, RepositoryType.HOSTED)));
    when(scans.findRepositoryConfig(1L))
        .thenReturn(Optional.of(config(1L, mode, pendingAction)));
    when(scans.findProfile(1L)).thenReturn(Optional.of(profile()));
    when(scans.findCandidate(10L)).thenReturn(Optional.of(
        new ScanCandidate(10L, 100L, 1L, 1L, Instant.EPOCH, Instant.EPOCH)));
    when(scans.findAssetState(10L, 1L)).thenReturn(Optional.of(state));
    when(scans.findAssetPolicyState(10L, 1L, 1L)).thenReturn(Optional.of(
        policyState(1L, state.policyDecision())));
  }

  private static RepositoryScanConfig config(
      long repositoryId, EnforcementMode mode, PolicyAction pendingAction) {
    return new RepositoryScanConfig(
        repositoryId, true, 1L, true, true, mode, pendingAction,
        PolicyAction.BLOCK, PolicyAction.BLOCK, 86400L, null, 1L,
        Instant.EPOCH, Instant.EPOCH);
  }

  private static AssetSecurityState complete(PolicyDecision decision) {
    return new AssetSecurityState(
        10L, 1L, 1L, new byte[32], 20L, ScanState.COMPLETE,
        ScanCompleteness.COMPLETE, true, Severity.CRITICAL,
        Map.of("critical", 1), null, null, decision, decision.name(),
        Instant.MAX, Instant.EPOCH, 1L);
  }

  private static AssetPolicyState policyState(
      long repositoryId, PolicyDecision decision) {
    return new AssetPolicyState(
        10L, 1L, repositoryId, 1L, 20L, null, null, 1L,
        decision, decision.name(), 0, Instant.MAX, null, Instant.EPOCH, 1L);
  }

  private static ScanProfile profile() {
    return new ScanProfile(
        1L, "default", true, "syft", "grype", List.of("vuln"), Map.of(),
        1024L, 100, 4096L, 1024L, 2, 30,
        OciPlatformPolicy.REQUIRED_SET, List.of("linux/amd64"),
        "a".repeat(64), 1L, Instant.EPOCH, Instant.EPOCH);
  }

  private static AssetRecord asset() {
    return new AssetRecord(
        10L, 1L, null, 100L, RepositoryFormat.MAVEN2, "com/acme/demo/1/demo-1.jar",
        PersistenceHashes.pathHash("com/acme/demo/1/demo-1.jar"), "demo-1.jar",
        "artifact", "application/java-archive", 42L, null, Instant.EPOCH, Map.of());
  }

  private static AssetBlobRecord blob() {
    return new AssetBlobRecord(
        100L, 1L, "blob://test/object",
        PersistenceHashes.blobRefHash("blob://test/object"), "object",
        PersistenceHashes.objectKeyHash("object"), "1".repeat(40),
        "a".repeat(64), "2".repeat(32), 42L, "application/java-archive",
        "test", "127.0.0.1", Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private static RepositoryRecord repository(long id, RepositoryType type) {
    return new RepositoryRecord(
        id, "repo-" + id, RepositoryFormat.MAVEN2, type, "maven2-" + type.name().toLowerCase(),
        true, 1L, null, null, null, null, "ALLOW", true, Map.of());
  }
}
