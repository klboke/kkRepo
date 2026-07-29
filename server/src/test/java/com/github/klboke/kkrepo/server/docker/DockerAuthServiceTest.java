package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao.TokenKind;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
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
    verify(tokenDao).insert(
        any(),
        eq("Local"),
        eq("alice"),
        eq("local"),
        eq(null),
        eq(TokenKind.USER),
        scopes.capture(),
        any(Instant.class));
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
    verify(tokenDao).insert(
        any(),
        eq("Local"),
        eq("alice"),
        eq("local"),
        eq(null),
        eq(TokenKind.USER),
        scopes.capture(),
        any(Instant.class));
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
    verify(tokenDao).insert(
        any(),
        eq("Local"),
        eq("alice"),
        eq("local"),
        eq(null),
        eq(TokenKind.USER),
        scopes.capture(),
        any(Instant.class));
    assertEquals(List.of(Map.of(
        "repository", "docker-hosted",
        "imageName", "codex/alpine",
        "actions", List.of("pull", "push"))), scopes.getValue());
  }

  @Test
  void scannerTokenIsShortLivedAndRestrictedToOneExactImage() {
    DockerAuthTokenDao tokenDao = mock(DockerAuthTokenDao.class);
    DockerAuthService service = new DockerAuthService(
        tokenDao,
        mock(SecurityAuthenticationService.class),
        mock(AccessDecisionService.class),
        900);

    String token = service.grantScannerPull("docker-hosted", "team/app", 120);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Map<String, Object>>> scopes =
        ArgumentCaptor.forClass((Class<List<Map<String, Object>>>) (Class<?>) List.class);
    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
    verify(tokenDao).insert(
        hash.capture(),
        eq(DockerAuthService.SCANNER_SUBJECT_SOURCE),
        eq("scanner"),
        eq(null),
        eq(null),
        eq(TokenKind.SECURITY_SCANNER),
        scopes.capture(),
        expiry.capture());
    assertEquals(List.of(Map.of(
        "repository", "docker-hosted",
        "imageName", "team/app",
        "actions", List.of("pull"))), scopes.getValue());
    assertTrue(expiry.getValue().isAfter(Instant.now().plusSeconds(100)));

    DockerAuthTokenDao.TokenRecord stored = new DockerAuthTokenDao.TokenRecord(
        hash.getValue(),
        DockerAuthService.SCANNER_SUBJECT_SOURCE,
        "scanner",
        null,
        null,
        TokenKind.SECURITY_SCANNER,
        Map.of("scopes", scopes.getValue()),
        expiry.getValue());
    when(tokenDao.findValid(any(String.class), any(Instant.class)))
        .thenReturn(Optional.of(stored));
    DockerAuthService.BearerAuthentication scannerAuthentication =
        service.authenticateBearer(token, "docker-hosted", "team/app", "pull")
            .orElseThrow();
    assertEquals(
        DockerAuthService.SCANNER_SUBJECT_SOURCE,
        scannerAuthentication.subject().source());
    assertTrue(scannerAuthentication.internalScanner());
    assertThrows(
        DockerProtocolException.class,
        () -> service.authenticateBearer(token, "docker-hosted", "team/application", "pull"));
    assertThrows(
        DockerProtocolException.class,
        () -> service.authenticateBearer(token, "docker-hosted", "team/app", "push"));
    assertEquals(60, DockerAuthService.scannerPullTokenTtlSeconds(0));
    assertEquals(
        DockerAuthService.MAX_SCANNER_PULL_TOKEN_TTL_SECONDS,
        DockerAuthService.scannerPullTokenTtlSeconds(Long.MAX_VALUE));
  }

  @Test
  void userTokenFromScannerNamedRealmDoesNotBecomeInternalScanner() {
    DockerAuthTokenDao tokenDao = mock(DockerAuthTokenDao.class);
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    DockerAuthService service = new DockerAuthService(
        tokenDao,
        authentication,
        mock(AccessDecisionService.class),
        900);
    PermissionSubject permissions =
        new PermissionSubject(DockerAuthService.SCANNER_SUBJECT_SOURCE, "alice", Set.of(), null);
    AuthenticatedSubject ordinary = new AuthenticatedSubject(
        DockerAuthService.SCANNER_SUBJECT_SOURCE,
        "alice",
        "realm-1",
        null,
        permissions);
    DockerAuthTokenDao.TokenRecord stored = new DockerAuthTokenDao.TokenRecord(
        "a".repeat(64),
        DockerAuthService.SCANNER_SUBJECT_SOURCE,
        "alice",
        "realm-1",
        null,
        TokenKind.USER,
        Map.of("scopes", List.of(Map.of(
            "repository", "docker-hosted",
            "imageName", "team/app",
            "actions", List.of("pull")))),
        Instant.now().plusSeconds(120));
    when(tokenDao.findValid(any(String.class), any(Instant.class)))
        .thenReturn(Optional.of(stored));
    when(authentication.authenticateStoredSubject(
        DockerAuthService.SCANNER_SUBJECT_SOURCE, "alice", "realm-1", null))
        .thenReturn(Optional.of(ordinary));

    DockerAuthService.BearerAuthentication restored =
        service.authenticateBearer("token", "docker-hosted", "team/app", "pull")
            .orElseThrow();

    assertEquals(DockerAuthService.SCANNER_SUBJECT_SOURCE, restored.subject().source());
    assertFalse(restored.internalScanner());
  }
}
