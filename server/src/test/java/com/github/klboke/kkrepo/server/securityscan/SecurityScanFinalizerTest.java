package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetPolicyState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanFinding;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanTask;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.OciPlatformPolicy;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SecurityScanFinalizerTest {
  @Test
  void materializesIndependentMemberAndGroupPolicyContexts() {
    SecurityScanDao scans = mock(SecurityScanDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    SecurityScanAuditService audit = mock(SecurityScanAuditService.class);
    SecurityScanFinalizer finalizer = new SecurityScanFinalizer(scans, repositories, audit);
    Instant now = Instant.now();
    ScanTask task = mock(ScanTask.class);
    when(task.id()).thenReturn(5L);
    when(task.repositoryId()).thenReturn(1L);
    when(task.assetId()).thenReturn(10L);
    when(task.contentGeneration()).thenReturn(1L);
    when(task.leaseToken()).thenReturn("lease");
    ScanProfile profile = profile(now);
    RepositoryScanConfig memberConfig = config(1L, 101L);
    RepositoryScanConfig groupConfig = config(2L, 102L);
    ScanPolicy memberPolicy = policy(101L, Severity.HIGH, now);
    ScanPolicy groupPolicy = policy(102L, Severity.CRITICAL, now);
    ScanRun run = run(now);
    ScanFinding finding = finding(now);

    when(scans.insertRunOrFindExisting(run)).thenReturn(run);
    when(scans.listFindings(eq(null), eq(30L), eq(null), eq(0L), eq(1000)))
        .thenReturn(List.of(finding));
    when(scans.listActiveWaivers(anyLong(), eq(10L), any(Instant.class), eq(1000)))
        .thenReturn(List.of());
    when(scans.findPolicy(101L)).thenReturn(Optional.of(memberPolicy));
    when(scans.findPolicy(102L)).thenReturn(Optional.of(groupPolicy));
    when(scans.findRepositoryConfig(1L)).thenReturn(Optional.of(memberConfig));
    when(scans.findRepositoryConfig(2L)).thenReturn(Optional.of(groupConfig));
    when(scans.upsertAssetStateIfCurrent(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scans.upsertAssetPolicyStateIfCurrent(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(scans.completeTask(eq(5L), eq("lease"), any(Instant.class))).thenReturn(true);
    RepositoryRecord group = new RepositoryRecord(
        2L, "public", RepositoryFormat.MAVEN2, RepositoryType.GROUP,
        "maven2-group", true, null, null, null, null, null, null, true, Map.of());
    when(repositories.listGroupsContaining(1L)).thenReturn(List.of(group));
    when(repositories.listGroupsContaining(2L)).thenReturn(List.of());

    finalizer.finalizeRun(
        task, profile, memberConfig, "sha256:" + "a".repeat(64), run, List.of(finding));

    ArgumentCaptor<AssetPolicyState> states = ArgumentCaptor.forClass(AssetPolicyState.class);
    verify(scans, org.mockito.Mockito.times(2))
        .upsertAssetPolicyStateIfCurrent(states.capture());
    Map<Long, PolicyDecision> decisions = states.getAllValues().stream()
        .collect(java.util.stream.Collectors.toMap(
            AssetPolicyState::repositoryId, AssetPolicyState::policyDecision));
    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, decisions.get(1L));
    assertEquals(PolicyDecision.ALLOW, decisions.get(2L));
  }

  private static ScanProfile profile(Instant now) {
    return new ScanProfile(
        1L, "default", true, "syft", "grype", List.of("vuln"), Map.of(),
        1024 * 1024, 1000, 4 * 1024 * 1024, 1024 * 1024, 2, 60,
        OciPlatformPolicy.REQUIRED_SET, List.of("linux/amd64"),
        "a".repeat(64), 1L, now, now);
  }

  private static RepositoryScanConfig config(long repositoryId, long policyId) {
    return new RepositoryScanConfig(
        repositoryId, true, 1L, true, true, EnforcementMode.AUDIT,
        PolicyAction.BLOCK, PolicyAction.BLOCK, PolicyAction.BLOCK,
        3600L, policyId, 1L, Instant.EPOCH, Instant.EPOCH);
  }

  private static ScanPolicy policy(long id, Severity severity, Instant now) {
    return new ScanPolicy(
        id, "policy-" + id, true, severity, false, false, true,
        3600L, List.of("linux/amd64"), 1L, "test", now, now);
  }

  private static ScanRun run(Instant now) {
    return new ScanRun(
        30L, 5L, 20L, 40L, "b".repeat(64), "c".repeat(64),
        ScanState.COMPLETE, ScanCompleteness.COMPLETE, 50L, "d".repeat(64),
        1, 1, 0, 1, 0, 0, 0, Severity.HIGH, now, now, now);
  }

  private static ScanFinding finding(Instant now) {
    return new ScanFinding(
        60L, 30L, "finding", null, "CVE-2026-0001", List.of(),
        "fixture", "pkg:maven/acme/demo@1", "demo", "1", List.of("2"),
        Severity.HIGH, "fixture", null, null, "title", "description",
        "https://example.invalid/CVE-2026-0001", List.of("demo.jar"), "active", now);
  }
}
