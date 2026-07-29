package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementFilter;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class NexusRepositoryManagementAuthorizerTest {
  @Test
  void anonymousRepositoryPermissionSetsAuditAndRepositoryContext() {
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    SecurityManagementService security = mock(SecurityManagementService.class);
    NexusRepositoryManagementAuthorizer authorizer =
        new NexusRepositoryManagementAuthorizer(authentication, security);
    AuthenticatedSubject anonymous = subject("anonymous");
    RepositoryRecord repository = repository();
    MockHttpServletRequest request = new MockHttpServletRequest();
    when(authentication.authenticate(request)).thenReturn(Optional.empty());
    when(authentication.authenticateAnonymous()).thenReturn(Optional.of(anonymous));
    when(security.decide(any(PermissionSubject.class), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());

    authorizer.requireRepositoryAction(
        request, repository, "file.zip", PermissionAction.READ);

    assertSame(anonymous, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
    assertSame(repository, request.getAttribute(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE));
    assertEquals(
        "nexus:repository-view:raw:windows-artifacts:read",
        request.getAttribute(SecurityManagementFilter.REQUESTED_PERMISSION_ATTRIBUTE));
    verify(security).decide(
        anonymous.permissionSubject(),
        new RepositoryPermission(
            "windows-artifacts", RepositoryFormat.RAW, "file.zip", PermissionAction.READ));
  }

  @Test
  void invalidExplicitCredentialsNeverDowngradeToAnonymous() {
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    SecurityManagementService security = mock(SecurityManagementService.class);
    NexusRepositoryManagementAuthorizer authorizer =
        new NexusRepositoryManagementAuthorizer(authentication, security);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Basic invalid");
    when(authentication.authenticate(request)).thenReturn(Optional.empty());
    when(authentication.hasPresentedCredentials(request)).thenReturn(true);

    ResponseStatusException failure = assertThrows(ResponseStatusException.class,
        () -> authorizer.requireRepositoryAction(
            request, repository(), "file.zip", PermissionAction.READ));

    assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatusCode());
  }

  @Test
  void repositoryAdminDenialIsForbiddenAndPermissionIsAuditable() {
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    SecurityManagementService security = mock(SecurityManagementService.class);
    NexusRepositoryManagementAuthorizer authorizer =
        new NexusRepositoryManagementAuthorizer(authentication, security);
    MockHttpServletRequest request = new MockHttpServletRequest();
    AuthenticatedSubject subject = subject("moon");
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    when(security.decide(subject.permissionSubject(),
        "nexus:repository-admin:maven2:maven-public:read"))
        .thenReturn(AccessDecision.deny("denied"));

    ResponseStatusException failure = assertThrows(ResponseStatusException.class,
        () -> authorizer.requireRepositoryAdmin(
            request, RepositoryFormat.MAVEN2, "maven-public", "read"));

    assertEquals(HttpStatus.FORBIDDEN, failure.getStatusCode());
    assertEquals(
        "nexus:repository-admin:maven2:maven-public:read",
        request.getAttribute(SecurityManagementFilter.REQUESTED_PERMISSION_ATTRIBUTE));
  }

  private static AuthenticatedSubject subject(String userId) {
    return new AuthenticatedSubject(
        "local", userId, "local", null,
        new PermissionSubject("local", userId, Set.of(), null));
  }

  private static RepositoryRecord repository() {
    return new RepositoryRecord(
        7L, "windows-artifacts", RepositoryFormat.RAW, RepositoryType.HOSTED,
        "raw-hosted", true, 1L, null, null, null, null, "ALLOW", true, Map.of());
  }
}
