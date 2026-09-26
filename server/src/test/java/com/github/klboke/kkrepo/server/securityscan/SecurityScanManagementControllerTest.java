package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.FindingArtifactPage;
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
  void completionEndpointsReturn400ForOutOfRangeCursorTimestamps() throws Exception {
    var service = mock(SecurityScanManagementService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    var controller = new SecurityScanManagementController(service, mock(SecurityScanMutationService.class));
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
    for (String endpoint : List.of("tasks", "runs")) {
      String field = endpoint.equals("tasks") ? "finished_at" : "completed_at";
      String cursor = java.util.Base64.getUrlEncoder().encodeToString(
          ("1|" + field + "|desc|+1000000000-12-31T23:59:59.999999999Z|1")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
              .get("/internal/security/scanning/" + endpoint)
              .param("sort", field).param("cursor", cursor)
              .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, mock(AuthenticatedSubject.class)))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
  }

  @Test
  void completionEndpointsReturn400ForConnectionTimezoneOverflow() throws Exception {
    var service = mock(SecurityScanManagementService.class);
    var actor = mock(AuthenticatedSubject.class);
    var error = new com.github.klboke.kkrepo.persistence.jdbc.api.InvalidScanCompletionCursorException(null);
    when(service.taskPage(actor, null, null, null, 0, 25, "finished_at", null, "cursor"))
        .thenThrow(error);
    when(service.runPage(actor, null, null, 0, 25, "completed_at", null, "cursor"))
        .thenThrow(error);
    var controller = new SecurityScanManagementController(service, mock(SecurityScanMutationService.class));
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
    for (String endpoint : List.of("tasks", "runs")) {
      String field = endpoint.equals("tasks") ? "finished_at" : "completed_at";
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
              .get("/internal/security/scanning/" + endpoint)
              .param("sort", field).param("cursor", "cursor")
              .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, actor))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
  }

  @Test
  void forwardsCompletionSortAndCursorParameters() {
    var service = mock(SecurityScanManagementService.class);
    var controller = new SecurityScanManagementController(service, mock(SecurityScanMutationService.class));
    var request = mock(HttpServletRequest.class);
    var actor = mock(AuthenticatedSubject.class);
    when(request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE)).thenReturn(actor);
    controller.tasks(3L, TaskStatus.FAILED, "error", 0, 25, "finished_at", "desc", "task-cursor", request);
    verify(service).taskPage(actor, 3L, TaskStatus.FAILED, "error", 0, 25,
        "finished_at", "desc", "task-cursor");
    controller.runs(3L, "complete", 0, 25, "completed_at", "asc", "run-cursor", request);
    verify(service).runPage(actor, 3L, "complete", 0, 25, "completed_at", "asc", "run-cursor");
  }

  @Test
  void delegatesEveryManagementEndpointAndBuildsMutationResponses() {
    SecurityScanManagementService service = mock(SecurityScanManagementService.class);
    SecurityScanMutationService mutations = mock(SecurityScanMutationService.class);
    SecurityScanManagementController controller =
        new SecurityScanManagementController(service, mutations);
    HttpServletRequest request = mock(HttpServletRequest.class);
    AuthenticatedSubject actor = mock(AuthenticatedSubject.class);
    when(request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE)).thenReturn(actor);

    when(service.repositoryPage(actor, "repo", 1L, 2))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(service.taskPage(actor, 3L, TaskStatus.PENDING, "task", 4L, 5, null, null, null))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(service.runPage(actor, 6L, "run", 7L, 8, null, null, null))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(service.findingPage(actor, 9L, 10L, Severity.HIGH, "finding", 11L, 12))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(service.findingArtifacts(actor, 24L, 25L, 26L, 27))
        .thenReturn(new FindingArtifactPage(List.of(), null, null));
    when(service.policyPage(actor, "policy", 13L, 14))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(service.waiverPage(actor, 15L, "waiver", 16L, 17))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(mutations.rescan(request, actor, 18L)).thenReturn(180L);

    RepositoryScanConfig config = mock(RepositoryScanConfig.class);
    when(config.enabled()).thenReturn(true);
    when(config.profileId()).thenReturn(1L);
    when(config.enforcementMode()).thenReturn(EnforcementMode.AUDIT);
    when(config.configRevision()).thenReturn(2L);
    ConfigCommand configCommand = mock(ConfigCommand.class);
    when(mutations.updateRepositoryConfig(request, actor, 19L, configCommand))
        .thenReturn(config);

    ScanPolicy policy = mock(ScanPolicy.class);
    when(policy.id()).thenReturn(20L);
    when(policy.revision()).thenReturn(3L);
    PolicyCommand policyCommand = mock(PolicyCommand.class);
    when(mutations.createPolicy(request, actor, policyCommand)).thenReturn(policy);
    when(mutations.revisePolicy(request, actor, 21L, policyCommand)).thenReturn(policy);

    ScanWaiver waiver = mock(ScanWaiver.class);
    when(waiver.id()).thenReturn(22L);
    when(waiver.scopeType()).thenReturn("FINDING");
    WaiverCommand waiverCommand = mock(WaiverCommand.class);
    when(mutations.createWaiver(request, actor, waiverCommand)).thenReturn(waiver);
    when(mutations.deleteWaiver(request, actor, 22L)).thenReturn(waiver);

    SbomDownload download = mock(SbomDownload.class);
    when(download.input()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
    when(service.sbom(actor, 23L)).thenReturn(download);

    controller.summary(request);
    assertEquals(List.of(), controller.repositories("repo", 1L, 2, request).items());
    assertEquals(
        List.of(),
        controller.tasks(3L, TaskStatus.PENDING, "task", 4L, 5, null, null, null, request).items());
    assertEquals(List.of(), controller.runs(6L, "run", 7L, 8, null, null, null, request).items());
    assertEquals(
        List.of(),
        controller.findings(9L, 10L, Severity.HIGH, "finding", 11L, 12, request).items());
    assertEquals(
        List.of(), controller.findingArtifacts(24L, 25L, 26L, 27, request).items());
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
    assertEquals(HttpStatus.NO_CONTENT, controller.deletePolicy(21L, request).getStatusCode());
    verify(mutations).deletePolicy(request, actor, 21L);
    assertEquals(List.of(), controller.waivers(15L, "waiver", 16L, 17, request).items());
    assertEquals(waiver, controller.createWaiver(waiverCommand, request));
    assertEquals(HttpStatus.NO_CONTENT, controller.deleteWaiver(22L, request).getStatusCode());
    assertEquals(
        "application/vnd.cyclonedx+json",
        controller.sbom(23L, request).getHeaders().getContentType().toString());

    verify(mutations).retry(request, actor, 26L);
    verify(mutations).cancel(request, actor, 27L);
  }

  @Test
  void repositoryAndWaiverAssignmentsReturn409WhenPolicyDeletionWins() throws Exception {
    var mutations = mock(SecurityScanMutationService.class);
    var conflict = new com.github.klboke.kkrepo.persistence.jdbc.api.ScanPolicyReferenceConflictException(42);
    when(mutations.updateRepositoryConfig(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(3L),
        org.mockito.ArgumentMatchers.any())).thenThrow(conflict);
    when(mutations.createWaiver(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenThrow(conflict);
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
        new SecurityScanManagementController(mock(SecurityScanManagementService.class), mutations)).build();
    var actor = mock(AuthenticatedSubject.class);
    for (var request : List.of(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/internal/security/scanning/repositories/3/config").content("""
                {"enabled":true,"profileId":1,"scanHostedContent":true,"scanProxyContent":true,"policyId":42}
                """),
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/internal/security/scanning/waivers").content("{\"policyId\":42}"))) {
      var result = mvc.perform(request.contentType("application/json")
              .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, actor))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
          .andReturn();
      assertEquals("{\"message\":\"Scan policy 42 no longer exists; refresh and select an existing policy\"}",
          result.getResponse().getContentAsString());
    }
  }

  @Test
  void rejectsRequestsWithoutAnAuthenticatedSubject() {
    SecurityScanManagementController controller = new SecurityScanManagementController(
        mock(SecurityScanManagementService.class), mock(SecurityScanMutationService.class));
    HttpServletRequest request = mock(HttpServletRequest.class);

    ResponseStatusException failure =
        assertThrows(ResponseStatusException.class, () -> controller.summary(request));

    assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatusCode());
  }
}
