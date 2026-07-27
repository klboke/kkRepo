package com.github.klboke.kkrepo.security.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScannerContractAndEnumsTest {
  @Test
  void artifactTypesRoundTripOnlyThroughTheClosedEnum() {
    for (ScannerArtifactType type : ScannerArtifactType.values()) {
      assertEquals(type, ScannerArtifactType.fromWireValue(type.wireValue().toLowerCase()));
      assertFalse(type.safeFilename().contains(".."));
      if (type != ScannerArtifactType.UNKNOWN) {
        assertEquals(type, ScannerArtifactType.fromPath("directory\\PACKAGE" + type.suffix()));
      }
    }
    assertEquals(ScannerArtifactType.UNKNOWN, ScannerArtifactType.fromPath(null));
    assertEquals(ScannerArtifactType.UNKNOWN, ScannerArtifactType.fromPath("README"));
    assertEquals(ScannerArtifactType.UNKNOWN, ScannerArtifactType.fromWireValue(" "));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScannerArtifactType.fromWireValue("../../shell"));
  }

  @Test
  void normalizesSeverityAndEnforcesExplicitTaskTransitions() {
    assertTrue(ScanEnums.SubjectKind.values().length > 0);
    assertTrue(ScanEnums.TargetClassification.values().length > 0);
    assertTrue(ScanEnums.CandidateDisposition.values().length > 0);
    assertTrue(ScanEnums.ScanStage.values().length > 0);
    assertTrue(ScanEnums.RequestReason.values().length > 0);
    assertTrue(ScanEnums.TaskStatus.values().length > 0);
    assertTrue(ScanEnums.ScanState.values().length > 0);
    assertTrue(ScanEnums.ScanCompleteness.values().length > 0);
    assertTrue(ScanEnums.EnforcementMode.values().length > 0);
    assertTrue(ScanEnums.PolicyAction.values().length > 0);
    assertTrue(ScanEnums.OciPlatformPolicy.values().length > 0);
    assertTrue(ScanEnums.BackfillStatus.values().length > 0);
    assertEquals(Severity.UNKNOWN, Severity.normalize(null));
    assertEquals(Severity.CRITICAL, Severity.normalize(" critical "));
    assertEquals(Severity.HIGH, Severity.normalize("important"));
    assertEquals(Severity.MEDIUM, Severity.normalize("moderate"));
    assertEquals(Severity.LOW, Severity.normalize("low"));
    assertEquals(Severity.NEGLIGIBLE, Severity.normalize("informational"));
    assertEquals(Severity.UNKNOWN, Severity.normalize("invented"));
    assertTrue(Severity.CRITICAL.atLeast(Severity.HIGH));
    assertEquals(5, Severity.CRITICAL.rank());
    assertTrue(PolicyDecision.BLOCK_PENDING.blocked());
    assertFalse(PolicyDecision.ALLOW.blocked());

    assertTrue(ScanStateMachine.canTransition(TaskStatus.PENDING, TaskStatus.RUNNING));
    assertTrue(ScanStateMachine.canTransition(TaskStatus.RUNNING, TaskStatus.RETRY_WAIT));
    assertTrue(ScanStateMachine.canTransition(TaskStatus.FAILED, TaskStatus.PENDING));
    assertTrue(ScanStateMachine.canTransition(TaskStatus.CANCELLED, TaskStatus.PENDING));
    assertTrue(ScanStateMachine.canTransition(TaskStatus.SUCCEEDED, TaskStatus.SUCCEEDED));
    assertFalse(ScanStateMachine.canTransition(TaskStatus.SUCCEEDED, TaskStatus.RUNNING));
    ScanStateMachine.requireTransition(TaskStatus.RETRY_WAIT, TaskStatus.RUNNING);
    assertThrows(
        IllegalStateException.class,
        () -> ScanStateMachine.requireTransition(TaskStatus.SUCCEEDED, TaskStatus.PENDING));
  }

  @Test
  void contractRecordsDefensivelyNormalizeCollectionsAndBytes() {
    var capabilities = new ScannerContract.Capabilities(
        "v1", "adapter", "1", null, null, 1, 2, "digest");
    var readiness = new ScannerContract.Readiness(
        true, "READY", "grype", "1", "db", Instant.EPOCH, Instant.EPOCH, null);
    var limits = new ScannerContract.ResourceLimits(1, 1, 1, 1, 0, 1);
    byte[] sbom = "{}".getBytes();
    var catalog = new ScannerContract.CatalogResponse(
        "adapter", "1", "syft", "1", "cap", "a".repeat(64),
        ScanCompleteness.COMPLETE, "CycloneDX", "1.5", 0, 0, sbom, null, null);
    var match = new ScannerContract.MatchResponse(
        "adapter", "1", "grype", "1", "db", Instant.EPOCH, "cap",
        ScanCompleteness.COMPLETE, null, null, null);
    var component = new ScannerContract.Component(
        "ref", null, "library", null, "name", "1", null, null, null, null);
    var finding = new ScannerContract.Finding(
        null, "CVE-1", null, null, null, "pkg", "1", null,
        null, null, null, null, null, null, null, null, null);
    var ociRequest = new ScannerContract.OciScanRequest(
        "v1", "run", "key", "https://registry", "repo/image", "sha256:" + "a".repeat(64),
        null, "token", "config", limits);
    var ociResponse = new ScannerContract.OciScanResponse(catalog, match, null, null);
    ScanSubject requestSubject = new ScanSubject(
        SubjectKind.ASSET_BLOB, 1, 2L, 3L, "id", "a".repeat(64), 1,
        "RAW", "file", null, TargetClassification.RAW_FILE, null, null);
    var catalogRequest = new ScannerContract.CatalogRequest(
        "v1", "run", "key", requestSubject, "config", limits);
    var matchRequest = new ScannerContract.MatchRequest(
        "v1", "run", "key", "a".repeat(64), "config", limits);

    sbom[0] = 'x';
    assertEquals(List.of(), capabilities.operations());
    assertEquals(Map.of(), readiness.details());
    assertEquals('{', catalog.cyclonedxJson()[0]);
    assertEquals(0, match.reportJson().length);
    assertEquals(Map.of(), component.properties());
    assertEquals(Severity.UNKNOWN, finding.severity());
    assertFalse(finding.fixable());
    assertEquals(List.of(), ociRequest.requiredPlatforms());
    assertEquals(List.of(), ociResponse.missingPlatforms());
    assertEquals("run", catalogRequest.runId());
    assertEquals("a".repeat(64), matchRequest.sbomSha256());

    var fixable = new ScannerContract.Finding(
        null, "CVE-2", null, null, null, "pkg", "1", List.of("2"),
        Severity.HIGH, null, null, null, null, null, null, null, null);
    assertTrue(fixable.fixable());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ScannerContract.ResourceLimits(0, 1, 1, 1, 0, 1));
  }

  @Test
  void scanSubjectValidatesIdentityAndNormalizesItsImmutableView() {
    ScanSubject subject = new ScanSubject(
        SubjectKind.ASSET_BLOB, 1, 2L, 3L, " sha256:identity ",
        "A".repeat(64), 0, "MAVEN2", "artifact", null,
        TargetClassification.PACKAGE, null, null);
    assertEquals("a".repeat(64), subject.sha256());
    assertEquals(List.of(), subject.platforms());
    assertEquals(Map.of(), subject.attributes());

    assertThrows(
        NullPointerException.class,
        () -> new ScanSubject(
            null, 1, null, null, "id", "a".repeat(64), 0,
            null, null, null, TargetClassification.PACKAGE, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ScanSubject(
            SubjectKind.ASSET_BLOB, 0, null, null, "id", "a".repeat(64), 0,
            null, null, null, TargetClassification.PACKAGE, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ScanSubject(
            SubjectKind.ASSET_BLOB, 1, null, null, "id", "bad", 0,
            null, null, null, TargetClassification.PACKAGE, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ScanSubject(
            SubjectKind.ASSET_BLOB, 1, null, null, "id", "a".repeat(64), -1,
            null, null, null, TargetClassification.PACKAGE, null, null));
  }
}
