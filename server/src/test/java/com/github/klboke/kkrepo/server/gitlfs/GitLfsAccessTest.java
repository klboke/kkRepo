package com.github.klboke.kkrepo.server.gitlfs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.*;
import com.github.klboke.kkrepo.core.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsProtocol;
import com.github.klboke.kkrepo.server.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class GitLfsAccessTest {
  private final SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
  private final AccessDecisionService permissions = mock(AccessDecisionService.class);
  private final com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao repositories = mock(com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao.class);
  private final GitLfsAccess access = new GitLfsAccess(authentication, permissions, repositories, new ObjectMapper(), 1024);
  private final RepositoryRecord repository = new RepositoryRecord(1L, "lfs", RepositoryFormat.GITLFS,
      RepositoryType.HOSTED, "gitlfs-hosted", true, 1L, null, null, null, null, "ALLOW", false, Map.of());
  @org.junit.jupiter.api.BeforeEach void currentRepository() {
    when(repositories.findById(1L)).thenReturn(Optional.of(repository));
  }
  @Test void cachedRepositoryCannotAuthorizeAnActionAfterDeletion() throws Exception {
    when(repositories.findById(1L)).thenReturn(Optional.empty());
    when(authentication.hasPresentedCredentials(any())).thenReturn(true);
    when(authentication.authenticateFresh(any())).thenReturn(Optional.of(subject()));
    var response = new MockHttpServletResponse();
    assertFalse(access.authorize(batch("download"), response, repository, GitLfsProtocol.BATCH_PATH));
    assertEquals(404, response.getStatus());
  }
  static AuthenticatedSubject subject() {
    return new AuthenticatedSubject("default", "alice", "default", 7L,
        new PermissionSubject("default", "alice", Set.of(), "7"));
  }
  private MockHttpServletRequest batch(String operation) {
    var request = new MockHttpServletRequest("POST", "/repository/lfs/" + GitLfsProtocol.BATCH_PATH);
    request.setContentType(GitLfsProtocol.MEDIA_TYPE + "; charset=utf-8");
    request.setContent(("{\"operation\":\"" + operation + "\",\"objects\":[]}").getBytes(StandardCharsets.UTF_8));
    return request;
  }
  @Test void downloadBatchCanUseAnonymousButUploadCannotUseCookieOnly() throws Exception {
    when(authentication.authenticateFresh(any())).thenReturn(Optional.empty());
    when(authentication.authenticateAnonymous()).thenReturn(Optional.of(subject()));
    var download = batch("download");
    assertTrue(access.authorize(download, new MockHttpServletResponse(), repository, GitLfsProtocol.BATCH_PATH));
    assertInstanceOf(GitLfsProtocol.Batch.class, download.getAttribute(GitLfsAccess.BATCH_ATTRIBUTE));
    var upload = batch("upload");
    upload.getSession().setAttribute(AuthenticatedSubject.SESSION_ATTRIBUTE, subject());
    var response = new MockHttpServletResponse();
    assertFalse(access.authorize(upload, response, repository, GitLfsProtocol.BATCH_PATH));
    assertEquals(401, response.getStatus());
    assertEquals(GitLfsProtocol.MEDIA_TYPE, response.getContentType());
    assertNotNull(response.getHeader("LFS-Authenticate"));
    verifyNoInteractions(permissions); // Batch paths are never substituted for OID selectors.
  }
  @Test void invalidExplicitCredentialsCannotBecomeAnonymousOrUseSession() throws Exception {
    var request = batch("download");
    request.addHeader("Authorization", "Basic invalid");
    request.getSession().setAttribute(AuthenticatedSubject.SESSION_ATTRIBUTE, subject());
    when(authentication.hasPresentedCredentials(any())).thenReturn(true);
    when(authentication.authenticateFresh(any())).thenAnswer(call -> {
      jakarta.servlet.http.HttpServletRequest presented = call.getArgument(0);
      assertNull(presented.getSession(false));
      assertNull(presented.getSession());
      return Optional.empty();
    });
    var response = new MockHttpServletResponse();
    assertFalse(access.authorize(request, response, repository, GitLfsProtocol.BATCH_PATH));
    assertEquals(401, response.getStatus());
    verify(authentication, never()).authenticateAnonymous();
  }
  @Test void putAuthorizesCanonicalOidWithoutReadingBinaryBody() throws Exception {
    String oid = "a".repeat(64);
    var request = new MockHttpServletRequest("PUT", "/repository/lfs/" + oid) {
      @Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("Binary body was read by auth filter"); }
    };
    when(authentication.hasPresentedCredentials(any())).thenReturn(true);
    when(authentication.authenticateFresh(any())).thenReturn(Optional.of(subject()));
    var permission = new RepositoryPermission("lfs", RepositoryFormat.GITLFS, oid, PermissionAction.ADD);
    when(permissions.decideAllFresh(any(), any())).thenReturn(Map.of(permission, AccessDecision.allow()));
    assertTrue(access.authorize(request, new MockHttpServletResponse(), repository, oid));
    verify(permissions).decideAllFresh(subject().permissionSubject(), List.of(permission));
  }
  @Test void uploadReauthorizationRejectsExpiredAndChangedIdentities() {
    var request = batch("upload");
    assertEquals(401, assertThrows(com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException.class,
        () -> access.requireUpload(request, "lfs", "a".repeat(64), "old-owner")).status());
    when(authentication.authenticateFresh(any())).thenReturn(Optional.of(subject()));
    assertEquals(403, assertThrows(com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException.class,
        () -> access.requireUpload(request, "lfs", "a".repeat(64), "old-owner")).status());
    assertEquals(401, assertThrows(com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException.class,
        () -> GitLfsAccess.subject(request)).status());
    verifyNoInteractions(permissions);
  }
  @Test void invalidLimitsAndMalformedContentTypesFailBeforeBodyProcessing() {
    assertThrows(IllegalArgumentException.class, () -> new GitLfsAccess(authentication, permissions,
        repositories, new ObjectMapper(), VerifiedBlobReader.MAX_BYTES + 1));
    for (String type : new String[] {null, "broken;", "text/plain"}) {
      assertEquals(415, assertThrows(com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException.class,
          () -> GitLfsAccess.requireJson(type)).status());
    }
  }
}
