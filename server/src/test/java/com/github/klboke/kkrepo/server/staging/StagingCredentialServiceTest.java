package com.github.klboke.kkrepo.server.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyView;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.CreatedApiKeyView;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class StagingCredentialServiceTest {
  private final SecurityDao securityDao = mock(SecurityDao.class);
  private final SecurityManagementService security = mock(SecurityManagementService.class);
  private final StagingCredentialService service =
      new StagingCredentialService(securityDao, security);

  @Test
  void issuesAUserOwnedSessionScopedToken() {
    when(security.createApiKeyForOwnerUncached(eq("LOCAL"), eq("alice"), any()))
        .thenReturn(new CreatedApiKeyView(apiKeyView(), "StagingToken.raw"));

    StagingCredentialService.IssuedCredential issued = service.issue(
        session(), new PermissionSubject("LOCAL", "alice", java.util.Set.of(), null));

    assertEquals("StagingToken.raw", issued.token());
    assertEquals(9L, issued.apiKeyId());
    var command = org.mockito.ArgumentCaptor.forClass(
        com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand.class);
    verify(security).createApiKeyForOwnerUncached(eq("LOCAL"), eq("alice"), command.capture());
    assertEquals(StagingCredentialService.DOMAIN, command.getValue().domain());
    assertTrue(command.getValue().scopes().contains("staging:session:7"));
  }

  @Test
  void bindsTheLinkedSessionEvenWhenAnExplicitSelectorIsPresent() {
    when(securityDao.findApiKey(9L)).thenReturn(Optional.of(apiKey()));
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/repository/raw-hosted/a");
    request.addHeader(StagingService.HEADER, "release-1");
    request.addParameter(StagingSelector.QUERY_PARAMETER, "release-1");

    service.bind(request, subject(9L));

    // The credential wins: an explicit selector cannot re-point a staging token at another
    // session, and StagingSelector rejects the mismatch before any publication.
    assertEquals("7", request.getAttribute(StagingCredentialService.SESSION_ATTRIBUTE));
  }

  @Test
  void bindsAFailClosedMarkerWhenTheTokenHasNoValidSessionScope() {
    ApiKeyRecord broken = new ApiKeyRecord(
        9L, StagingCredentialService.DOMAIN, "LOCAL", "alice", "broken", "ACTIVE",
        "hash", "StagingToke", Map.of("values", List.of("staging:name:release-1")),
        "encrypted", LocalDateTime.now(), LocalDateTime.now(), null, null);
    when(securityDao.findApiKey(9L)).thenReturn(Optional.of(broken));
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/repository/raw-hosted/a");

    service.bind(request, subject(9L));

    assertEquals(
        StagingCredentialService.INVALID_SESSION,
        request.getAttribute(StagingCredentialService.SESSION_ATTRIBUTE));
  }

  @Test
  void ignoresOrdinaryApiKeys() {
    ApiKeyRecord generic = new ApiKeyRecord(
        9L, "GenericToken", "LOCAL", "alice", "generic", "ACTIVE",
        "hash", "GenericT", Map.of("values", List.of("staging:session:7")),
        "encrypted", LocalDateTime.now(), LocalDateTime.now(), null, null);
    when(securityDao.findApiKey(9L)).thenReturn(Optional.of(generic));
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/repository/raw-hosted/a");

    service.bind(request, subject(9L));

    assertEquals(null, request.getAttribute(StagingCredentialService.SESSION_ATTRIBUTE));
  }

  @Test
  void revokesTheStoredTokenOnCloseOrRollback() {
    StagingSessionRecord session = new StagingSessionRecord(
        7L, "release-1", "OPEN", 2, "LOCAL", "alice", Instant.EPOCH, Instant.EPOCH,
        null, null, Instant.now().plusSeconds(3600), null, Map.of("stagingTokenId", 9));
    when(securityDao.findApiKey(9L)).thenReturn(Optional.of(apiKey()));

    service.revoke(session);

    verify(security).deleteApiKey(9L);
  }

  @Test
  void refusesToRevokeAKeySelectedThroughUserEditableAttributes() {
    StagingSessionRecord session = new StagingSessionRecord(
        7L, "release-1", "OPEN", 2, "LOCAL", "alice", Instant.EPOCH, Instant.EPOCH,
        null, null, Instant.now().plusSeconds(3600), null, Map.of("stagingTokenId", 999));
    // Key 999 exists and even belongs to another session of another owner: the PATCHable
    // attribute must never turn into an arbitrary API-key revocation.
    when(securityDao.findApiKey(999L)).thenReturn(Optional.of(new ApiKeyRecord(
        999L, StagingCredentialService.DOMAIN, "LOCAL", "bob", "other session", "ACTIVE",
        "hash", "StagingToke", Map.of("values", List.of("staging:session:8")),
        "encrypted", LocalDateTime.now(), LocalDateTime.now(), null, null)));

    service.revoke(session);

    verify(security, never()).deleteApiKey(anyLong());
  }

  @Test
  void refusesToRevokeAnOrdinaryApiKeyNamedByTheAttribute() {
    StagingSessionRecord session = new StagingSessionRecord(
        7L, "release-1", "OPEN", 2, "LOCAL", "alice", Instant.EPOCH, Instant.EPOCH,
        null, null, Instant.now().plusSeconds(3600), null, Map.of("stagingTokenId", 9));
    ApiKeyRecord generic = new ApiKeyRecord(
        9L, "GenericToken", "LOCAL", "alice", "generic", "ACTIVE",
        "hash", "GenericT", Map.of("values", List.of("staging:session:7")),
        "encrypted", LocalDateTime.now(), LocalDateTime.now(), null, null);
    when(securityDao.findApiKey(9L)).thenReturn(Optional.of(generic));

    service.revoke(session);

    verify(security, never()).deleteApiKey(anyLong());
  }

  @Test
  void selectorRejectsCrossSessionSelection() {
    when(securityDao.findApiKey(9L)).thenReturn(Optional.of(apiKey()));
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/repository/raw-hosted/a");
    service.bind(request, subject(9L));
    request.addHeader(StagingService.HEADER, "8");

    ResponseStatusException error = assertThrows(
        ResponseStatusException.class, () -> StagingSelector.resolve(request));

    assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
  }

  @Test
  void selectorRejectsContradictoryHeaderAndQuery() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/repository/raw-hosted/a");
    request.addHeader(StagingService.HEADER, "7");
    request.addParameter(StagingSelector.QUERY_PARAMETER, "8");

    ResponseStatusException error = assertThrows(
        ResponseStatusException.class, () -> StagingSelector.resolve(request));

    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
  }

  @Test
  void selectorUsesTheCredentialBindingWhenNoExplicitSelectorIsPresent() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/repository/raw-hosted/a");
    request.setAttribute(StagingCredentialService.SESSION_ATTRIBUTE, "7");

    assertEquals("7", StagingSelector.resolve(request));
  }

  private static AuthenticatedSubject subject(long apiKeyId) {
    return new AuthenticatedSubject(
        "LOCAL", "alice", "api-key", apiKeyId,
        new PermissionSubject("LOCAL", "alice", java.util.Set.of(), null));
  }

  private static ApiKeyRecord apiKey() {
    return new ApiKeyRecord(
        9L,
        StagingCredentialService.DOMAIN,
        "LOCAL",
        "alice",
        "Staging session release-1",
        "ACTIVE",
        "hash",
        "StagingToke",
        Map.of("values", List.of("staging:session:7", "staging:name:release-1")),
        "encrypted",
        LocalDateTime.now(),
        LocalDateTime.now(),
        LocalDateTime.now().plusDays(1),
        null);
  }

  private static ApiKeyView apiKeyView() {
    return new ApiKeyView(
        9L,
        StagingCredentialService.DOMAIN,
        "LOCAL",
        "alice",
        "Staging session release-1",
        "ACTIVE",
        "StagingToke",
        List.of("staging:session:7"),
        LocalDateTime.now(),
        LocalDateTime.now(),
        LocalDateTime.now().plusDays(1),
        null);
  }

  private static StagingSessionRecord session() {
    return new StagingSessionRecord(
        7L, "release-1", "OPEN", 1, "LOCAL", "alice", Instant.EPOCH, Instant.EPOCH,
        null, null, Instant.now().plusSeconds(3600), null, Map.of());
  }
}
