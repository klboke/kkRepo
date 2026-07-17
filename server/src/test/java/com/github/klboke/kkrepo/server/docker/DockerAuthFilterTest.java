package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DockerAuthFilterTest {
  private AuthenticatedSubject subject;
  private PermissionSubject permissionSubject;
  private DockerAuthService authService;
  private RepositoryRuntimeRegistry registry;
  private AccessDecisionService accessDecisionService;
  private DockerRegistryDao dockerRegistryDao;

  @BeforeEach
  void setUp() {
    permissionSubject =
        new PermissionSubject("Local", "admin", Set.of("nx-admin", "nx-anonymous"), "docker-token");
    subject = new AuthenticatedSubject(
        "Local",
        "admin",
        "local",
        null,
        permissionSubject);
    authService = mock(DockerAuthService.class);
    registry = mock(RepositoryRuntimeRegistry.class);
    accessDecisionService = mock(AccessDecisionService.class);
    dockerRegistryDao = mock(DockerRegistryDao.class);
  }

  @Test
  void baseProbeChallengesWithBearerRealmAndNoRepositoryScope() throws Exception {
    when(authService.challenge(
        "http://127.0.0.1:18090/service/rest/v1/docker/token",
        "127.0.0.1:18090"))
        .thenReturn("Bearer realm=\"http://127.0.0.1:18090/service/rest/v1/docker/token\","
            + "service=\"127.0.0.1:18090\"");
    SecurityAuthenticationService authenticationService = mock(SecurityAuthenticationService.class);
    DockerAuthFilter filter = new DockerAuthFilter(
        registry,
        authService,
        authenticationService,
        accessDecisionService,
        dockerRegistryDao);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v2/");
    request.setScheme("http");
    request.setServerName("127.0.0.1");
    request.setServerPort(18090);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(401, response.getStatus());
    assertEquals(DockerConstants.API_VERSION, response.getHeader(DockerConstants.API_VERSION_HEADER));
    assertEquals(
        "Bearer realm=\"http://127.0.0.1:18090/service/rest/v1/docker/token\","
            + "service=\"127.0.0.1:18090\"",
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
    verify(authenticationService).authenticate(request);
  }

  @Test
  void baseProbeAcceptsValidBearerTokenWithoutRepositoryScope() throws Exception {
    when(authService.authenticateBearer("base-token")).thenReturn(Optional.of(subject));
    DockerAuthFilter filter = new DockerAuthFilter(
        registry,
        authService,
        mock(SecurityAuthenticationService.class),
        accessDecisionService,
        dockerRegistryDao);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v2/");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer base-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(200, response.getStatus());
    assertEquals(subject, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
    verify(accessDecisionService, never()).decide(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void bearerTokenScopeFailureReturnsDockerChallengeInsteadOfEscapingAsServerError() throws Exception {
    RepositoryRuntime runtime = dockerProxy("docker-live-proxy");
    when(registry.resolve("docker-live-proxy")).thenReturn(Optional.of(runtime));
    when(authService.authenticateBearer(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new DockerProtocolException(DockerErrorCode.DENIED, "token scope does not allow pull", 403));
    when(authService.challenge(
        "http://127.0.0.1:18090/service/rest/v1/docker/token",
        "127.0.0.1:18090",
        "docker-live-proxy",
        "library/alpine",
        "pull"))
        .thenReturn("Bearer realm=\"http://127.0.0.1:18090/service/rest/v1/docker/token\","
            + "service=\"127.0.0.1:18090\",scope=\"repository:docker-live-proxy/library/alpine:pull\"");
    SecurityAuthenticationService authenticationService = mock(SecurityAuthenticationService.class);
    DockerAuthFilter filter = new DockerAuthFilter(
        registry, authService, authenticationService, accessDecisionService, dockerRegistryDao);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/v2/docker-live-proxy/library/alpine/manifests/latest");
    request.setScheme("http");
    request.setServerName("127.0.0.1");
    request.setServerPort(18090);
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer expired-or-wrong-scope");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(401, response.getStatus());
    assertEquals(DockerConstants.API_VERSION, response.getHeader(DockerConstants.API_VERSION_HEADER));
    assertEquals(
        "Bearer realm=\"http://127.0.0.1:18090/service/rest/v1/docker/token\","
            + "service=\"127.0.0.1:18090\",scope=\"repository:docker-live-proxy/library/alpine:pull\"",
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
    verify(authenticationService, never()).authenticate(request);
  }

  @Test
  void connectorRouteChallengeUsesMappedRepositoryInTokenScope() throws Exception {
    RepositoryRuntime runtime = dockerProxy("docker-hosted");
    when(registry.resolve("docker-hosted")).thenReturn(Optional.of(runtime));
    when(authService.challenge(
        "http://127.0.0.1:18180/service/rest/v1/docker/token",
        "127.0.0.1:18180",
        "codex/alpine",
        null,
        "pull"))
        .thenReturn("Bearer realm=\"http://127.0.0.1:18180/service/rest/v1/docker/token\","
            + "service=\"127.0.0.1:18180\",scope=\"repository:codex/alpine:pull\"");
    DockerAuthFilter filter = new DockerAuthFilter(
        registry, authService, mock(SecurityAuthenticationService.class), accessDecisionService, dockerRegistryDao);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/v2/codex/alpine/manifests/latest");
    request.setScheme("http");
    request.setServerName("127.0.0.1");
    request.setServerPort(18180);
    request.setAttribute(DockerConnectorConfiguration.CONNECTOR_REPOSITORY_ATTRIBUTE, "docker-hosted");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(401, response.getStatus());
    assertEquals(
        "Bearer realm=\"http://127.0.0.1:18180/service/rest/v1/docker/token\","
            + "service=\"127.0.0.1:18180\",scope=\"repository:codex/alpine:pull\"",
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
  }

  @Test
  void validBearerTokenContinuesToPermissionCheckAndFilterChain() throws Exception {
    RepositoryRuntime runtime = dockerProxy("docker-live-proxy");
    when(registry.resolve("docker-live-proxy")).thenReturn(Optional.of(runtime));
    when(authService.authenticateBearer(
        "valid-token", "docker-live-proxy", "library/alpine", "pull"))
        .thenReturn(Optional.of(subject));
    when(accessDecisionService.decide(org.mockito.ArgumentMatchers.eq(subject.permissionSubject()), org.mockito.ArgumentMatchers.any()))
        .thenReturn(AccessDecision.allow());
    DockerAuthFilter filter = new DockerAuthFilter(
        registry, authService, mock(SecurityAuthenticationService.class), accessDecisionService, dockerRegistryDao);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/v2/docker-live-proxy/library/alpine/manifests/latest");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(subject, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
    assertEquals(200, response.getStatus());
  }

  @Test
  void bearerSubjectRolesAreUsedForRepositoryPermissionCheck() throws Exception {
    RepositoryRuntime runtime = dockerProxy("docker-live-proxy");
    when(registry.resolve("docker-live-proxy")).thenReturn(Optional.of(runtime));
    when(authService.authenticateBearer(
        "valid-token", "docker-live-proxy", "library/alpine", "push"))
        .thenReturn(Optional.of(subject));
    when(accessDecisionService.decide(
        org.mockito.ArgumentMatchers.eq(permissionSubject),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(AccessDecision.allow());
    DockerAuthFilter filter = new DockerAuthFilter(
        registry, authService, mock(SecurityAuthenticationService.class), accessDecisionService, dockerRegistryDao);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/v2/docker-live-proxy/library/alpine/blobs/uploads/");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(subject, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
    assertEquals(200, response.getStatus());
    verify(accessDecisionService).decide(
        org.mockito.ArgumentMatchers.eq(permissionSubject),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void blobUploadStartRequiresAddPermission() throws Exception {
    RepositoryRuntime runtime = dockerProxy("docker-live-proxy");
    when(registry.resolve("docker-live-proxy")).thenReturn(Optional.of(runtime));
    when(authService.authenticateBearer(
        "valid-token", "docker-live-proxy", "library/alpine", "push"))
        .thenReturn(Optional.of(subject));
    when(accessDecisionService.decide(org.mockito.ArgumentMatchers.eq(permissionSubject), org.mockito.ArgumentMatchers.any()))
        .thenReturn(AccessDecision.allow());
    DockerAuthFilter filter = new DockerAuthFilter(
        registry, authService, mock(SecurityAuthenticationService.class), accessDecisionService, dockerRegistryDao);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/v2/docker-live-proxy/library/alpine/blobs/uploads/");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    RepositoryPermission permission = capturedPermission();
    assertEquals(PermissionAction.ADD, permission.action());
    assertEquals("library/alpine", permission.pathPattern());
  }

  @Test
  void newManifestReferenceRequiresAddPermission() throws Exception {
    RepositoryRuntime runtime = dockerProxy("docker-live-proxy");
    when(registry.resolve("docker-live-proxy")).thenReturn(Optional.of(runtime));
    when(authService.authenticateBearer(
        "valid-token", "docker-live-proxy", "library/alpine", "push"))
        .thenReturn(Optional.of(subject));
    when(accessDecisionService.decide(org.mockito.ArgumentMatchers.eq(permissionSubject), org.mockito.ArgumentMatchers.any()))
        .thenReturn(AccessDecision.allow());
    DockerAuthFilter filter = new DockerAuthFilter(
        registry, authService, mock(SecurityAuthenticationService.class), accessDecisionService, dockerRegistryDao);
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/v2/docker-live-proxy/library/alpine/manifests/latest");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    RepositoryPermission permission = capturedPermission();
    assertEquals(PermissionAction.ADD, permission.action());
    assertEquals("library/alpine", permission.pathPattern());
  }

  @Test
  void existingManifestReferenceRequiresEditPermission() throws Exception {
    RepositoryRuntime runtime = dockerProxy("docker-live-proxy");
    when(registry.resolve("docker-live-proxy")).thenReturn(Optional.of(runtime));
    when(dockerRegistryDao.findManifestByReference(65L, "library/alpine", "latest"))
        .thenReturn(Optional.of(mock(DockerManifestRecord.class)));
    when(authService.authenticateBearer(
        "valid-token", "docker-live-proxy", "library/alpine", "push"))
        .thenReturn(Optional.of(subject));
    when(accessDecisionService.decide(
        org.mockito.ArgumentMatchers.eq(permissionSubject), org.mockito.ArgumentMatchers.any()))
        .thenReturn(AccessDecision.allow());
    DockerAuthFilter filter = new DockerAuthFilter(
        registry,
        authService,
        mock(SecurityAuthenticationService.class),
        accessDecisionService,
        dockerRegistryDao);
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/v2/docker-live-proxy/library/alpine/manifests/latest");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    RepositoryPermission permission = capturedPermission();
    assertEquals(PermissionAction.EDIT, permission.action());
    assertEquals("library/alpine", permission.pathPattern());
  }

  @Test
  void newQueryTagOnExistingManifestRequiresAddPermission() throws Exception {
    RepositoryRuntime runtime = dockerProxy("docker-live-proxy");
    when(registry.resolve("docker-live-proxy")).thenReturn(Optional.of(runtime));
    when(dockerRegistryDao.findManifestByReference(65L, "library/alpine", "latest"))
        .thenReturn(Optional.of(mock(DockerManifestRecord.class)));
    when(authService.authenticateBearer(
        "valid-token", "docker-live-proxy", "library/alpine", "push"))
        .thenReturn(Optional.of(subject));
    when(accessDecisionService.decide(
        org.mockito.ArgumentMatchers.eq(permissionSubject), org.mockito.ArgumentMatchers.any()))
        .thenReturn(AccessDecision.allow());
    DockerAuthFilter filter = new DockerAuthFilter(
        registry,
        authService,
        mock(SecurityAuthenticationService.class),
        accessDecisionService,
        dockerRegistryDao);
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/v2/docker-live-proxy/library/alpine/manifests/latest");
    request.addParameter("tag", "stable");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    RepositoryPermission permission = capturedPermission();
    assertEquals(PermissionAction.ADD, permission.action());
    assertEquals("library/alpine", permission.pathPattern());
  }

  @Test
  void existingQueryTagsOnExistingManifestRequireEditPermission() throws Exception {
    RepositoryRuntime runtime = dockerProxy("docker-live-proxy");
    when(registry.resolve("docker-live-proxy")).thenReturn(Optional.of(runtime));
    DockerManifestRecord manifest = mock(DockerManifestRecord.class);
    when(dockerRegistryDao.findManifestByReference(65L, "library/alpine", "latest"))
        .thenReturn(Optional.of(manifest));
    when(dockerRegistryDao.findManifestByReference(65L, "library/alpine", "stable"))
        .thenReturn(Optional.of(manifest));
    when(dockerRegistryDao.findManifestByReference(65L, "library/alpine", "1.0"))
        .thenReturn(Optional.of(manifest));
    when(authService.authenticateBearer(
        "valid-token", "docker-live-proxy", "library/alpine", "push"))
        .thenReturn(Optional.of(subject));
    when(accessDecisionService.decide(
        org.mockito.ArgumentMatchers.eq(permissionSubject), org.mockito.ArgumentMatchers.any()))
        .thenReturn(AccessDecision.allow());
    DockerAuthFilter filter = new DockerAuthFilter(
        registry,
        authService,
        mock(SecurityAuthenticationService.class),
        accessDecisionService,
        dockerRegistryDao);
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/v2/docker-live-proxy/library/alpine/manifests/latest");
    request.addParameter("tag", "stable,1.0");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    RepositoryPermission permission = capturedPermission();
    assertEquals(PermissionAction.EDIT, permission.action());
    assertEquals("library/alpine", permission.pathPattern());
  }

  @Test
  void catalogRequiresRepositoryAdminPermission() throws Exception {
    RepositoryRuntime runtime = dockerProxy("docker-live-proxy");
    when(registry.resolve("docker-live-proxy")).thenReturn(Optional.of(runtime));
    when(authService.authenticateBearer(
        "valid-token", "docker-live-proxy", null, "catalog"))
        .thenReturn(Optional.of(subject));
    when(accessDecisionService.decide(org.mockito.ArgumentMatchers.eq(permissionSubject), org.mockito.ArgumentMatchers.any()))
        .thenReturn(AccessDecision.allow());
    DockerAuthFilter filter = new DockerAuthFilter(
        registry, authService, mock(SecurityAuthenticationService.class), accessDecisionService, dockerRegistryDao);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/v2/docker-live-proxy/_catalog");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    RepositoryPermission permission = capturedPermission();
    assertEquals(PermissionAction.ADMIN, permission.action());
    assertEquals(null, permission.pathPattern());
  }

  @Test
  void catalogChallengeUsesRegistryScope() throws Exception {
    RepositoryRuntime runtime = dockerProxy("docker-live-proxy");
    when(registry.resolve("docker-live-proxy")).thenReturn(Optional.of(runtime));
    when(authService.registryCatalogChallenge(
        "http://127.0.0.1:18090/service/rest/v1/docker/token",
        "127.0.0.1:18090"))
        .thenReturn("Bearer realm=\"http://127.0.0.1:18090/service/rest/v1/docker/token\","
            + "service=\"127.0.0.1:18090\",scope=\"registry:catalog:*\"");
    DockerAuthFilter filter = new DockerAuthFilter(
        registry, authService, mock(SecurityAuthenticationService.class), accessDecisionService, dockerRegistryDao);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/v2/docker-live-proxy/_catalog");
    request.setScheme("http");
    request.setServerName("127.0.0.1");
    request.setServerPort(18090);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(401, response.getStatus());
    assertEquals(DockerConstants.API_VERSION, response.getHeader(DockerConstants.API_VERSION_HEADER));
    assertEquals(
        "Bearer realm=\"http://127.0.0.1:18090/service/rest/v1/docker/token\","
            + "service=\"127.0.0.1:18090\",scope=\"registry:catalog:*\"",
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
  }

  @Test
  void dockerTokenScopeRequiresExactImageNameMatch() {
    Map<String, Object> stored = Map.of(
        "scopes",
        List.of(new DockerAuthService.Scope(
            "docker-hosted",
            "team/app",
            List.of("pull")).toMap()));

    assertEquals(true, DockerAuthService.scopeAllows(stored, "docker-hosted", "team/app", "pull"));
    assertEquals(false, DockerAuthService.scopeAllows(stored, "docker-hosted", "team/application", "pull"));
    assertEquals(false, DockerAuthService.scopeAllows(stored, "docker-hosted", "team/app/sub", "pull"));
  }

  @Test
  void dockerTokenScopeAllowsRegistryCatalogScope() {
    Map<String, Object> stored = Map.of(
        "scopes",
        List.of(new DockerAuthService.Scope(
            "",
            "",
            List.of("catalog")).toMap()));

    assertEquals(true, DockerAuthService.scopeAllows(stored, "docker-hosted", null, "catalog"));
    assertEquals(true, DockerAuthService.scopeAllows(stored, "docker-proxy", null, "catalog"));
    assertEquals(false, DockerAuthService.scopeAllows(stored, "docker-hosted", "team/app", "pull"));
  }


  private RepositoryPermission capturedPermission() {
    ArgumentCaptor<RepositoryPermission> captor = ArgumentCaptor.forClass(RepositoryPermission.class);
    verify(accessDecisionService).decide(org.mockito.ArgumentMatchers.eq(permissionSubject), captor.capture());
    return captor.getValue();
  }

  private static RepositoryRuntime dockerProxy(String name) {
    return new RepositoryRuntime(
        65L,
        name,
        RepositoryFormat.DOCKER,
        RepositoryType.PROXY,
        "docker-proxy",
        true,
        1L,
        "ALLOW",
        null,
        null,
        true,
        "https://registry-1.docker.io/",
        1440,
        1440,
        true,
        null,
        List.of());
  }
}
