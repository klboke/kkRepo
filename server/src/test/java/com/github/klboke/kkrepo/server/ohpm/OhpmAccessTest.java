package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OhpmAccessTest {
  private final SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
  private final AccessDecisionService permissions = mock(AccessDecisionService.class);
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final OhpmAccess access = new OhpmAccess(authentication, permissions, repositories, new ObjectMapper());
  private final RepositoryRecord repository = new RepositoryRecord(1L, "ohpm", RepositoryFormat.OHPM,
      RepositoryType.HOSTED, "ohpm-hosted", true, 1L, null, null, null, null, "ALLOW_ONCE", false, Map.of());
  private final AuthenticatedSubject subject = new AuthenticatedSubject("default", "alice", "default", 7L,
      new PermissionSubject("default", "alice", Set.of(), "7"));

  @BeforeEach
  void repositoryExists() {
    when(repositories.findById(1L)).thenReturn(Optional.of(repository));
  }

  @Test
  void pingIsPublicAndUnknownStreamPathReturnsNotFound() throws Exception {
    assertTrue(access.authorize(request("GET"), new MockHttpServletResponse(), repository, "-/ping"));
    var response = new MockHttpServletResponse();
    assertFalse(access.authorize(request("POST"), response, repository, "stream/@scope%2fpkg/extra"));
    assertEquals(404, response.getStatus());
    assertEquals(404, new ObjectMapper().readTree(response.getContentAsByteArray()).path("code").asInt());
    verifyNoInteractions(authentication, permissions);
  }

  @ParameterizedTest
  @CsvSource({
      "GET,@scope%2fpkg,@scope/pkg,READ",
      "GET,@scope%2fpkg/-/@scope/pkg-1.0.0.har,@scope/pkg/-/@scope/pkg-1.0.0.har,READ",
      "PUT,@scope%2fpkg,@scope/pkg,ADD",
      "POST,stream/@scope%2fpkg,@scope/pkg,ADD",
      "DELETE,@scope%2fpkg@1.0.0,@scope/pkg,DELETE",
      "POST,-/package/%40scope%2Fpkg/dist-tags/beta,@scope/pkg,EDIT",
      "PUT,-/package/@scope/pkg/dist-tags/beta,@scope/pkg,EDIT",
      "DELETE,-/package/@scope/pkg/dist-tags/beta,@scope/pkg,EDIT"
  })
  void authorizesCanonicalPackageAndOperationWithoutReadingTheBody(
      String method, String path, String permissionPath, PermissionAction action) throws Exception {
    when(authentication.hasPresentedCredentials(any())).thenReturn(true);
    when(authentication.authenticateOhpmFresh(any())).thenReturn(Optional.of(subject));
    var permission = new RepositoryPermission("ohpm", RepositoryFormat.OHPM, permissionPath, action);
    when(permissions.decideAllFresh(any(), any())).thenReturn(Map.of(permission, AccessDecision.allow()));
    var request = new MockHttpServletRequest(method, "/repository/ohpm/" + path) {
      @Override public jakarta.servlet.ServletInputStream getInputStream() {
        throw new AssertionError("Authorization must precede parsing the publish body");
      }
    };
    assertTrue(access.authorize(request, new MockHttpServletResponse(), repository, path));
    verify(permissions).decideAllFresh(subject.permissionSubject(), List.of(permission));
  }

  @Test
  void invalidTokenCannotFallBackToCookieOrAnonymous() throws Exception {
    var request = request("GET");
    request.addHeader("Authorization", "GenericToken.invalid");
    request.getSession().setAttribute(AuthenticatedSubject.SESSION_ATTRIBUTE, subject);
    when(authentication.hasPresentedCredentials(any())).thenReturn(true);
    when(authentication.authenticateOhpmFresh(any())).thenAnswer(call -> {
      HttpServletRequest credentials = call.getArgument(0);
      assertNull(credentials.getSession(false));
      assertNull(credentials.getSession());
      return Optional.empty();
    });
    var response = new MockHttpServletResponse();
    assertFalse(access.authorize(request, response, repository, "pkg"));
    assertEquals(401, response.getStatus());
    verify(authentication, never()).authenticateAnonymousFresh();
    verifyNoInteractions(permissions);
  }

  @Test
  void cookieAloneCannotPublish() throws Exception {
    var request = request("PUT");
    request.getSession().setAttribute(AuthenticatedSubject.SESSION_ATTRIBUTE, subject);
    var response = new MockHttpServletResponse();
    assertFalse(access.authorize(request, response, repository, "pkg"));
    assertEquals(401, response.getStatus());
    verify(authentication, never()).authenticateOhpmFresh(any());
  }

  @Test
  void anonymousReadStillRequiresReadPermission() throws Exception {
    when(authentication.authenticateAnonymousFresh()).thenReturn(Optional.of(subject));
    var response = new MockHttpServletResponse();
    assertFalse(access.authorize(request("GET"), response, repository, "pkg"));
    assertEquals(401, response.getStatus());
    verify(permissions).decideAllFresh(subject.permissionSubject(), List.of(
        new RepositoryPermission("ohpm", RepositoryFormat.OHPM, "pkg", PermissionAction.READ)));
  }

  @Test
  void headRejectionHasNoBodyAndRepositoryDeletionIsFresh() throws Exception {
    when(repositories.findById(1L)).thenReturn(Optional.empty());
    var response = new MockHttpServletResponse();
    assertFalse(access.authorize(request("HEAD"), response, repository, "pkg"));
    assertEquals(404, response.getStatus());
    assertEquals(0, response.getContentAsByteArray().length);
    verifyNoInteractions(authentication);
  }

  @Test
  void publicationRechecksAddAndRequiresEditForExistingMetadata() {
    when(authentication.authenticateOhpmFresh(any())).thenReturn(Optional.of(subject));
    var add = new RepositoryPermission("ohpm", RepositoryFormat.OHPM, "pkg", PermissionAction.ADD);
    var edit = new RepositoryPermission("ohpm", RepositoryFormat.OHPM, "pkg", PermissionAction.EDIT);
    when(permissions.decideAllFresh(any(), any())).thenReturn(Map.of(add, AccessDecision.allow()));
    access.requirePublish(request("PUT"), "ohpm", "pkg", false);
    assertEquals(403, assertThrows(OhpmException.class,
        () -> access.requirePublish(request("PUT"), "ohpm", "pkg", true)).status());
    verify(permissions).decideAllFresh(subject.permissionSubject(), List.of(edit));
    when(authentication.authenticateOhpmFresh(any())).thenReturn(Optional.empty());
    assertEquals(401, assertThrows(OhpmException.class,
        () -> access.requirePublish(request("PUT"), "ohpm", "pkg", false)).status());
  }

  private static MockHttpServletRequest request(String method) {
    return new MockHttpServletRequest(method, "/repository/ohpm/pkg");
  }
}
