package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanWaiver;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TaskStatus;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.ConfigCommand;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.CursorPage;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.PolicyCommand;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.SbomDownload;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.WaiverCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SecurityScanManagementControllerTest {
  @Test
  void delegatesEveryManagementEndpointAndBuildsMutationResponses() {
    SecurityScanManagementService service = mock(SecurityScanManagementService.class);
    SecurityScanAuditService audit = mock(SecurityScanAuditService.class);
    SecurityScanManagementController controller =
        new SecurityScanManagementController(service, audit);
    HttpServletRequest request = mock(HttpServletRequest.class);
    AuthenticatedSubject actor = mock(AuthenticatedSubject.class);
    when(request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE)).thenReturn(actor);

    when(service.repositoryPage(actor, "repo", 1L, 2))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(service.taskPage(actor, 3L, TaskStatus.PENDING, "task", 4L, 5))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(service.runPage(actor, 6L, "run", 7L, 8))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(service.findingPage(actor, 9L, 10L, Severity.HIGH, "finding", 11L, 12))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(service.policyPage(actor, "policy", 13L, 14))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(service.waiverPage(actor, 15L, "waiver", 16L, 17))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(service.rescan(actor, 18L)).thenReturn(180L);

    RepositoryScanConfig config = mock(RepositoryScanConfig.class);
    when(config.enabled()).thenReturn(true);
    when(config.profileId()).thenReturn(1L);
    when(config.enforcementMode()).thenReturn(EnforcementMode.AUDIT);
    when(config.configRevision()).thenReturn(2L);
    ConfigCommand configCommand = mock(ConfigCommand.class);
    when(service.updateRepositoryConfig(actor, 19L, configCommand)).thenReturn(config);

    ScanPolicy policy = mock(ScanPolicy.class);
    when(policy.id()).thenReturn(20L);
    when(policy.revision()).thenReturn(3L);
    PolicyCommand policyCommand = mock(PolicyCommand.class);
    when(service.createPolicy(actor, policyCommand)).thenReturn(policy);
    when(service.revisePolicy(actor, 21L, policyCommand)).thenReturn(policy);

    ScanWaiver waiver = mock(ScanWaiver.class);
    when(waiver.id()).thenReturn(22L);
    when(waiver.scopeType()).thenReturn("FINDING");
    WaiverCommand waiverCommand = mock(WaiverCommand.class);
    when(service.createWaiver(actor, waiverCommand)).thenReturn(waiver);
    when(service.deleteWaiver(actor, 22L)).thenReturn(waiver);

    SbomDownload download = mock(SbomDownload.class);
    when(download.input()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
    when(service.sbom(actor, 23L)).thenReturn(download);

    controller.summary(request);
    assertEquals(List.of(), controller.repositories("repo", 1L, 2, request).items());
    assertEquals(
        List.of(),
        controller.tasks(3L, TaskStatus.PENDING, "task", 4L, 5, request).items());
    assertEquals(List.of(), controller.runs(6L, "run", 7L, 8, request).items());
    assertEquals(
        List.of(),
        controller.findings(9L, 10L, Severity.HIGH, "finding", 11L, 12, request).items());
    controller.findingWaiverContext(24L, request);
    controller.findingWaivers(24L, request);
    controller.asset(25L, request);
    assertEquals(180L, controller.rescan(18L, request).get("taskId"));
    assertEquals("PENDING", controller.retry(26L, request).get("status"));
    assertEquals("CANCELLED", controller.cancel(27L, request).get("status"));
    controller.repositoryConfig(19L, request);
    assertEquals(config, controller.updateRepositoryConfig(19L, configCommand, request));
    assertEquals(List.of(), controller.policies("policy", 13L, 14, request).items());
    assertEquals(policy, controller.createPolicy(policyCommand, request));
    assertEquals(policy, controller.revisePolicy(21L, policyCommand, request));
    assertEquals(List.of(), controller.waivers(15L, "waiver", 16L, 17, request).items());
    assertEquals(waiver, controller.createWaiver(waiverCommand, request));
    assertEquals(HttpStatus.NO_CONTENT, controller.deleteWaiver(22L, request).getStatusCode());
    assertEquals(
        "application/vnd.cyclonedx+json",
        controller.sbom(23L, request).getHeaders().getContentType().toString());

    verify(service).retry(actor, 26L);
    verify(service).cancel(actor, 27L);
    verify(audit, org.mockito.Mockito.atLeast(7))
        .record(eq(request), eq(actor), any(), any(), any());
  }

  @Test
  void rejectsRequestsWithoutAnAuthenticatedSubject() {
    SecurityScanManagementController controller = new SecurityScanManagementController(
        mock(SecurityScanManagementService.class), mock(SecurityScanAuditService.class));
    HttpServletRequest request = mock(HttpServletRequest.class);

    ResponseStatusException failure =
        assertThrows(ResponseStatusException.class, () -> controller.summary(request));

    assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatusCode());
  }
}
