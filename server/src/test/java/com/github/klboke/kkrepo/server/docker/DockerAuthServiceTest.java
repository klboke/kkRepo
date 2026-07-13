package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

class DockerAuthServiceTest {
  @Test
  void pushScopeCanBeGrantedFromEditPermissionForManifestAndTagUpdates() {
    DockerAuthTokenDao tokenDao = mock(DockerAuthTokenDao.class);
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    AccessDecisionService access = mock(AccessDecisionService.class);
    PermissionSubject permissionSubject = new PermissionSubject("Local", "alice", Set.of("docker-edit"), null);
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "Local", "alice", "local", null, permissionSubject);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/service/rest/v1/docker/token");
    when(authentication.authenticate(request)).thenReturn(Optional.of(subject));
    when(access.decide(eq(permissionSubject), any(RepositoryPermission.class))).thenAnswer(invocation -> {
      RepositoryPermission permission = invocation.getArgument(1);
      return permission.action() == PermissionAction.EDIT
          ? AccessDecision.allow()
          : AccessDecision.deny("missing " + permission.action());
    });
    DockerAuthService service = new DockerAuthService(tokenDao, authentication, access, 900);

    service.grant(request, "127.0.0.1:18090",
        List.of("repository:docker-hosted/team/app:pull,push"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Map<String, Object>>> scopes =
        ArgumentCaptor.forClass((Class<List<Map<String, Object>>>) (Class<?>) List.class);
    verify(tokenDao).insert(any(), eq("Local"), eq("alice"), eq("local"), eq(null), scopes.capture(), any(Instant.class));
    assertEquals(List.of(Map.of(
        "repository", "docker-hosted",
        "imageName", "team/app",
        "actions", List.of("push"))), scopes.getValue());
  }

  @Test
  void registryCatalogScopeCanBeGrantedAndStored() {
    DockerAuthTokenDao tokenDao = mock(DockerAuthTokenDao.class);
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    AccessDecisionService access = mock(AccessDecisionService.class);
    PermissionSubject permissionSubject = new PermissionSubject("Local", "alice", Set.of("nx-admin"), null);
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "Local", "alice", "local", null, permissionSubject);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/service/rest/v1/docker/token");
    when(authentication.authenticate(request)).thenReturn(Optional.of(subject));
    when(access.decide(eq(permissionSubject), any(RepositoryPermission.class))).thenReturn(AccessDecision.allow());
    DockerAuthService service = new DockerAuthService(tokenDao, authentication, access, 900);

    service.grant(request, "127.0.0.1:18090", List.of("registry:catalog:*"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Map<String, Object>>> scopes =
        ArgumentCaptor.forClass((Class<List<Map<String, Object>>>) (Class<?>) List.class);
    verify(tokenDao).insert(any(), eq("Local"), eq("alice"), eq("local"), eq(null), scopes.capture(), any(Instant.class));
    assertEquals(List.of(Map.of(
        "repository", "",
        "imageName", "",
        "actions", List.of("catalog"))), scopes.getValue());
  }

  @Test
  void connectorPortScopeIsGrantedAgainstMappedRepository() {
    DockerAuthTokenDao tokenDao = mock(DockerAuthTokenDao.class);
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    AccessDecisionService access = mock(AccessDecisionService.class);
    PermissionSubject permissionSubject = new PermissionSubject("Local", "alice", Set.of("docker-write"), null);
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "Local", "alice", "local", null, permissionSubject);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/service/rest/v1/docker/token");
    request.setAttribute(DockerConnectorConfiguration.CONNECTOR_REPOSITORY_ATTRIBUTE, "docker-hosted");
    when(authentication.authenticate(request)).thenReturn(Optional.of(subject));
    when(access.decide(eq(permissionSubject), any(RepositoryPermission.class))).thenReturn(AccessDecision.allow());
    DockerAuthService service = new DockerAuthService(tokenDao, authentication, access, 900);

    service.grant(request, "127.0.0.1:18180",
        List.of("repository:codex/alpine:pull,push"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Map<String, Object>>> scopes =
        ArgumentCaptor.forClass((Class<List<Map<String, Object>>>) (Class<?>) List.class);
    verify(tokenDao).insert(any(), eq("Local"), eq("alice"), eq("local"), eq(null), scopes.capture(), any(Instant.class));
    assertEquals(List.of(Map.of(
        "repository", "docker-hosted",
        "imageName", "codex/alpine",
        "actions", List.of("pull", "push"))), scopes.getValue());
  }
}
