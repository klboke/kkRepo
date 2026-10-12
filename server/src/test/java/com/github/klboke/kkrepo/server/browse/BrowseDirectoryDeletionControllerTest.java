package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.server.security.*;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class BrowseDirectoryDeletionControllerTest {
  @Test void httpBindingAndConflictMessagesWorkWithoutCompilerParameterNames() throws Exception {
    var service = mock(BrowseDirectoryDeletionService.class);
    var authentication = mock(SecurityAuthenticationService.class);
    var security = mock(SecurityManagementService.class);
    var subject = new AuthenticatedSubject("test", "admin", "Local", null, mock(PermissionSubject.class));
    when(authentication.authenticate(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(subject));
    when(security.decide(subject.permissionSubject(), "nexus:*")).thenReturn(AccessDecision.allow());
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
        new BrowseDirectoryDeletionController(service, authentication, security)).build();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .get("/internal/browse/repo/directory-deletion").param("path", "folder"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    verify(service).preview("repo", "folder");
    var command = new BrowseDirectoryDeletionService.Command("folder", "folder", "token");
    when(service.delete("repo", command, "admin")).thenThrow(new ResponseStatusException(
        org.springframework.http.HttpStatus.CONFLICT, "Review a new preview"));
    var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .post("/internal/browse/repo/directory-deletion").contentType("application/json")
        .content("{\"path\":\"folder\",\"confirmationPath\":\"folder\",\"selectionToken\":\"token\"}"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
        .andReturn().getResponse();
    assertEquals("{\"message\":\"Review a new preview\"}", response.getContentAsString());
  }

  @Test void bothEndpointsRequireAdministratorEvenForAuthenticatedRepositoryOwners() {
    var service = mock(BrowseDirectoryDeletionService.class);
    var authentication = mock(SecurityAuthenticationService.class);
    var security = mock(SecurityManagementService.class);
    var controller = new BrowseDirectoryDeletionController(service, authentication, security);
    var request = new MockHttpServletRequest();
    var command = new BrowseDirectoryDeletionService.Command("folder", "folder", "token");
    when(authentication.authenticate(request)).thenReturn(Optional.empty());
    assertEquals(401, assertThrows(ResponseStatusException.class,
        () -> controller.preview("repo", "folder", request)).getStatusCode().value());
    assertEquals(401, assertThrows(ResponseStatusException.class,
        () -> controller.delete("repo", command, request)).getStatusCode().value());
    var subject = new AuthenticatedSubject("test", "reader", "Local", null, mock(PermissionSubject.class));
    when(authentication.authenticate(request)).thenReturn(Optional.of(subject));
    when(security.decide(subject.permissionSubject(), "nexus:*")).thenReturn(AccessDecision.deny("denied"));
    assertEquals(403, assertThrows(ResponseStatusException.class,
        () -> controller.preview("repo", "folder", request)).getStatusCode().value());
    assertEquals(403, assertThrows(ResponseStatusException.class,
        () -> controller.delete("repo", command, request)).getStatusCode().value());
    verifyNoInteractions(service);
  }

  @Test void successfulDeletionRecordsTheConfirmedScopeAndCountForAudit() {
    var service = mock(BrowseDirectoryDeletionService.class);
    var authentication = mock(SecurityAuthenticationService.class);
    var security = mock(SecurityManagementService.class);
    var controller = new BrowseDirectoryDeletionController(service, authentication, security);
    var request = new MockHttpServletRequest();
    var subject = new AuthenticatedSubject("test", "admin", "Local", null, mock(PermissionSubject.class));
    when(authentication.authenticate(request)).thenReturn(Optional.of(subject));
    when(security.decide(subject.permissionSubject(), "nexus:*")).thenReturn(AccessDecision.allow());
    var command = new BrowseDirectoryDeletionService.Command("folder", "folder", "token");
    when(service.delete("repo", command, "admin"))
        .thenReturn(new BrowseDirectoryDeletionService.Result("repo", "folder", 4));
    assertEquals(4, controller.delete("repo", command, request).deletedAssets());
    assertEquals(Map.of("operation", "browse.directory.delete", "repository", "repo", "path", "folder", "deletedAssets", 4),
        request.getAttribute(ManagementAuditFilter.OPERATION_DETAILS_ATTRIBUTE));
    assertSame(subject, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
  }
}
