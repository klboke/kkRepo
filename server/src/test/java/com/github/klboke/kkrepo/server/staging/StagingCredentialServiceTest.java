package com.github.klboke.kkrepo.server.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
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
import org.springframework.mock.web.MockHttpServletRequest;

class StagingCredentialServiceTest {
  private final SecurityDao securityDao = mock(SecurityDao.class);
  private final SecurityManagementService security = mock(SecurityManagementService.class);
  private final StagingDao stagingDao = mock(StagingDao.class);
  private final StagingCredentialService service =
      new StagingCredentialService(securityDao, security, stagingDao);

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
  void resolvesAndBindsTheSessionForAStagingToken() {
    PermissionSubject actor = new PermissionSubject("LOCAL", "alice", java.util.Set.of(), null);
    when(securityDao.findApiKey(9L)).thenReturn(Optional.of(apiKey()));
    when(stagingDao.findSessionById(7L)).thenReturn(Optional.of(session()));
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/repository/raw-hosted/a");
    AuthenticatedSubject subject = new AuthenticatedSubject("LOCAL", "alice", "realm", 9L, actor);

    assertTrue(service.resolve(request, subject).isPresent());
    service.bind(request, subject);
    assertEquals("7", request.getAttribute(StagingCredentialService.SESSION_ATTRIBUTE));
  }

  @Test
  void revokesTheStoredTokenOnCloseOrRollback() {
    StagingSessionRecord session = new StagingSessionRecord(
        7L, "release-1", "OPEN", 2, "LOCAL", "alice", Instant.EPOCH, Instant.EPOCH,
        null, null, Instant.now().plusSeconds(3600), null, Map.of("stagingTokenId", 9));

    service.revoke(session);

    verify(security).deleteApiKey(9L);
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
