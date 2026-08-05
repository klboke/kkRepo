package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyService.PolicyCommand;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyService.PolicyPage;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyService.ScheduleCommand;
import com.github.klboke.kkrepo.server.cleanup.CleanupProtectionService.ProtectionCommand;
import com.github.klboke.kkrepo.server.cleanup.CleanupRunService.RunCommand;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CleanupManagementControllerTest {
  private CleanupPolicyService policies;
  private CleanupRunService runs;
  private CleanupProtectionService protections;
  private SecurityManagementService security;
  private CleanupManagementController controller;
  private HttpServletRequest request;
  private AuthenticatedSubject actor;

  @BeforeEach
  void setUp() {
    policies = mock(CleanupPolicyService.class);
    runs = mock(CleanupRunService.class);
    protections = mock(CleanupProtectionService.class);
    security = mock(SecurityManagementService.class);
    controller = new CleanupManagementController(policies, runs, protections, security);
    request = mock(HttpServletRequest.class);
    PermissionSubject permissionSubject =
        new PermissionSubject("test", "admin", java.util.Set.of(), null);
    actor = new AuthenticatedSubject("test", "admin", "local", null, permissionSubject);
    when(request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE)).thenReturn(actor);
    when(security.decide(permissionSubject, "nexus:*")).thenReturn(AccessDecision.allow());
  }

  @Test
  void delegatesEveryManagementEndpointForAnAuthorizedAdministrator() {
    PolicyCommand policyCommand = mock(PolicyCommand.class);
    ScheduleCommand scheduleCommand = mock(ScheduleCommand.class);
    RunCommand runCommand = mock(RunCommand.class);
    ProtectionCommand protectionCommand = mock(ProtectionCommand.class);
    Instant updatedAt = Instant.parse("2026-08-01T00:00:00Z");
    when(policies.formatCapabilities()).thenReturn(List.of());
    when(policies.listPage(0, 25)).thenReturn(new PolicyPage(List.of(), null));
    when(runs.listRuns(null, 0, 25)).thenReturn(List.of());
    when(runs.listItems(9, 10, 0, 50)).thenReturn(List.of());
    when(protections.list(0, 50, true)).thenReturn(List.of());

    assertEquals(List.of(), controller.capabilities(request));
    assertEquals(new PolicyPage(List.of(), null), controller.policies(0, 25, request));
    assertNull(controller.policy(7, request));
    assertNull(controller.previewSchedule(scheduleCommand, request));
    assertEquals(HttpStatus.CREATED, controller.createPolicy(policyCommand, request).getStatusCode());
    assertNull(controller.updatePolicy(7, policyCommand, request));
    assertEquals(HttpStatus.NO_CONTENT,
        controller.deletePolicy(7, 3, request).getStatusCode());
    assertEquals(HttpStatus.ACCEPTED,
        controller.startRun(7, runCommand, request).getStatusCode());
    assertEquals(List.of(), controller.listRuns(null, 0, 25, request));
    assertNull(controller.run(9, request));
    assertNull(controller.runSummary(9, request));
    assertNull(controller.runDetails(9, 50, request));
    assertEquals(HttpStatus.ACCEPTED, controller.cancelRun(9, request).getStatusCode());
    assertEquals(List.of(), controller.runItems(9, 10, 0, 50, request));
    assertEquals(List.of(), controller.protections(0, 50, true, request));
    assertNull(controller.protection(11, request));
    assertEquals(HttpStatus.CREATED,
        controller.createProtection(protectionCommand, request).getStatusCode());
    assertNull(controller.updateProtection(11, protectionCommand, request));
    assertEquals(HttpStatus.NO_CONTENT,
        controller.deleteProtection(11, updatedAt, request).getStatusCode());

    verify(policies).get(7);
    verify(policies).previewSchedule(scheduleCommand);
    verify(policies).create(policyCommand);
    verify(policies).update(7, policyCommand);
    verify(policies).delete(7, 3);
    verify(runs).startManual(7, runCommand, "admin");
    verify(runs).getRun(9);
    verify(runs).getRunSummary(9);
    verify(runs).getRunDetails(9, 50);
    verify(runs).cancel(9);
    verify(protections).get(11);
    verify(protections).create(protectionCommand, "admin");
    verify(protections).update(11, protectionCommand, "admin");
    verify(protections).delete(11, updatedAt);
  }

  @Test
  void mapsDomainFailuresToStableApiResponses() {
    var validation = controller.validation(new CleanupValidationException("bad criteria"));
    assertEquals(HttpStatus.BAD_REQUEST, validation.getStatusCode());
    assertEquals("CLEANUP_VALIDATION_FAILED", validation.getBody().get("code"));

    var notFound = controller.notFound(new CleanupNotFoundException("cleanup run", 9));
    assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
    assertEquals("cleanup run not found: 9", notFound.getBody().get("message"));

    var revision = controller.revisionConflict(new CleanupRevisionConflictException(7, 4));
    assertEquals(HttpStatus.CONFLICT, revision.getStatusCode());
    assertEquals(7L, revision.getBody().get("policyId"));
    assertEquals(4L, revision.getBody().get("currentRevision"));

    Instant currentUpdatedAt = Instant.parse("2026-08-01T01:00:00Z");
    var protection = controller.protectionConflict(
        new CleanupProtectionConflictException(11, currentUpdatedAt));
    assertEquals(HttpStatus.CONFLICT, protection.getStatusCode());
    assertEquals(11L, protection.getBody().get("protectionId"));
    assertSame(currentUpdatedAt, protection.getBody().get("currentUpdatedAt"));

    var duplicate = controller.duplicate(new DuplicateKeyException("duplicate"));
    assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
    assertEquals("CLEANUP_POLICY_CONFLICT", duplicate.getBody().get("code"));
  }

  @Test
  void rejectsMissingAndUnauthorizedSubjects() {
    HttpServletRequest anonymous = mock(HttpServletRequest.class);
    ResponseStatusException unauthenticated = assertThrows(
        ResponseStatusException.class, () -> controller.policies(0, 25, anonymous));
    assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.getStatusCode());

    when(security.decide(actor.permissionSubject(), "nexus:*")).thenReturn(AccessDecision.deny("no"));
    ResponseStatusException forbidden = assertThrows(
        ResponseStatusException.class, () -> controller.policies(0, 25, request));
    assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
  }
}
