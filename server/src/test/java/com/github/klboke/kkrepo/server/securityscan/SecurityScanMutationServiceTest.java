package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanWaiver;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.ConfigCommand;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.PolicyCommand;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.WaiverCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class SecurityScanMutationServiceTest {
  @Test
  void wrapsEveryMutationAndItsAuditRecordInOneTransactionBoundary() throws Exception {
    assertNotNull(
        SecurityScanMutationService.class.getAnnotation(Transactional.class),
        "the facade transaction must include both the mutation and audit insert");
    assertEquals(
        Propagation.MANDATORY,
        SecurityScanAuditService.class
            .getMethod(
                "record",
                HttpServletRequest.class,
                AuthenticatedSubject.class,
                String.class,
                Long.class,
                Map.class)
            .getAnnotation(Transactional.class)
            .propagation(),
        "operation-specific audits must reject calls outside the mutation transaction");

    SecurityScanManagementService management = mock(SecurityScanManagementService.class);
    SecurityScanAuditService audit = mock(SecurityScanAuditService.class);
    SecurityScanMutationService mutations =
        new SecurityScanMutationService(management, audit);
    HttpServletRequest request = mock(HttpServletRequest.class);
    AuthenticatedSubject actor = mock(AuthenticatedSubject.class);
    ConfigCommand configCommand = mock(ConfigCommand.class);
    PolicyCommand policyCommand = mock(PolicyCommand.class);
    WaiverCommand waiverCommand = mock(WaiverCommand.class);

    RepositoryScanConfig config = mock(RepositoryScanConfig.class);
    when(config.enabled()).thenReturn(true);
    when(config.profileId()).thenReturn(3L);
    when(config.enforcementMode()).thenReturn(EnforcementMode.ENFORCE);
    when(config.configRevision()).thenReturn(4L);
    ScanPolicy policy = mock(ScanPolicy.class);
    when(policy.id()).thenReturn(5L);
    when(policy.revision()).thenReturn(6L);
    ScanWaiver waiver = mock(ScanWaiver.class);
    when(waiver.id()).thenReturn(7L);
    when(waiver.repositoryId()).thenReturn(8L);
    when(waiver.scopeType()).thenReturn("FINDING");

    when(management.rescan(actor, 1L)).thenReturn(2L);
    when(management.updateRepositoryConfig(actor, 8L, configCommand)).thenReturn(config);
    when(management.createPolicy(actor, policyCommand)).thenReturn(policy);
    when(management.revisePolicy(actor, 4L, policyCommand)).thenReturn(policy);
    when(management.createWaiver(actor, waiverCommand)).thenReturn(waiver);
    when(management.deleteWaiver(actor, 7L)).thenReturn(waiver);

    assertEquals(2L, mutations.rescan(request, actor, 1L));
    mutations.retry(request, actor, 9L);
    mutations.cancel(request, actor, 10L);
    assertEquals(
        config,
        mutations.updateRepositoryConfig(request, actor, 8L, configCommand));
    assertEquals(policy, mutations.createPolicy(request, actor, policyCommand));
    assertEquals(
        policy,
        mutations.revisePolicy(request, actor, 4L, policyCommand));
    assertEquals(waiver, mutations.createWaiver(request, actor, waiverCommand));
    assertEquals(waiver, mutations.deleteWaiver(request, actor, 7L));

    verify(audit).record(
        request, actor, "RESCAN", null, Map.of("assetId", 1L, "taskId", 2L));
    verify(audit).record(
        request, actor, "RETRY", null, Map.of("taskId", 9L));
    verify(audit).record(
        request, actor, "CANCEL", null, Map.of("taskId", 10L));
    verify(audit).record(
        request,
        actor,
        "REPOSITORY_CONFIG",
        8L,
        Map.of(
            "enabled", true,
            "profileId", 3L,
            "enforcementMode", "ENFORCE",
            "configRevision", 4L));
    verify(audit).record(
        request,
        actor,
        "POLICY_CREATE",
        null,
        Map.of("policyId", 5L, "policyRevision", 6L));
    verify(audit).record(
        request,
        actor,
        "POLICY_REVISE",
        null,
        Map.of("previousPolicyId", 4L, "policyId", 5L, "policyRevision", 6L));
    verify(audit).record(
        request,
        actor,
        "WAIVER_CREATE",
        8L,
        Map.of(
            "waiverId", 7L,
            "scopeType", "FINDING",
            "expiresAt", "none"));
    verify(audit).record(
        request, actor, "WAIVER_DELETE", 8L, Map.of("waiverId", 7L));
  }
}
