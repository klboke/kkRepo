package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

class ConanAuthServiceTest {
  private final ConanRegistryDao registry = mock(ConanRegistryDao.class);
  private final SecurityAuthenticationService authentication =
      mock(SecurityAuthenticationService.class);

  @Test
  void issuesOnlyDurableHashedRepositoryScopedTokens() {
    ConanAuthService service = new ConanAuthService(registry, authentication, 10_000);
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "LOCAL", "alice", "realm", 7L, null);

    String token = service.issue(42L, subject);

    assertTrue(token.startsWith("kkrepo_conan_"));
    assertFalse(token.matches(".*[\\r\\n].*"));
    ArgumentCaptor<ConanRegistryDao.AuthToken> row =
        ArgumentCaptor.forClass(ConanRegistryDao.AuthToken.class);
    verify(registry).insertAuthToken(row.capture());
    assertEquals(64, row.getValue().tokenHash().length());
    assertFalse(row.getValue().tokenHash().contains(token));
    assertEquals(42L, row.getValue().repositoryId());
    assertEquals("LOCAL", row.getValue().subjectSource());
    assertEquals("alice", row.getValue().subjectUserId());
    assertEquals("realm", row.getValue().realmId());
    assertEquals(7L, row.getValue().apiKeyId());
    assertTrue(row.getValue().expiresAt().isAfter(Instant.now().plusSeconds(23 * 60 * 60)));
  }

  @Test
  void rejectsMissingSubjectsAndClampsShortTokenTtl() {
    ConanAuthService service = new ConanAuthService(registry, authentication, 0);

    assertThrows(ConanExceptions.Unauthorized.class, () -> service.issue(1L, null));
    assertThrows(
        ConanExceptions.Unauthorized.class,
        () -> service.issue(1L, new AuthenticatedSubject("LOCAL", " ", null, null, null)));

    service.issue(1L, new AuthenticatedSubject("LOCAL", "alice", null, null, null));
    ArgumentCaptor<ConanRegistryDao.AuthToken> row =
        ArgumentCaptor.forClass(ConanRegistryDao.AuthToken.class);
    verify(registry).insertAuthToken(row.capture());
    assertTrue(row.getValue().expiresAt().isAfter(Instant.now().plusSeconds(50)));
  }

  @Test
  void restoresStoredSubjectAndTouchesOnlyValidBearerTokens() {
    ConanAuthService service = new ConanAuthService(registry, authentication, 60);
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "LOCAL", "alice", "realm", 7L, null);
    String token = service.issue(42L, subject);
    ArgumentCaptor<ConanRegistryDao.AuthToken> inserted =
        ArgumentCaptor.forClass(ConanRegistryDao.AuthToken.class);
    verify(registry).insertAuthToken(inserted.capture());
    ConanRegistryDao.AuthToken row = inserted.getValue();
    when(registry.findValidAuthToken(anyString(), eq(42L), any(Instant.class)))
        .thenReturn(Optional.of(row));
    when(authentication.authenticateStoredSubject("LOCAL", "alice", "realm", 7L))
        .thenReturn(Optional.of(subject));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "bEaReR   " + token + "  ");

    assertTrue(service.hasConanBearer(request));
    assertSame(subject, service.authenticate(request, 42L).orElseThrow());
    verify(registry).touchAuthToken(eq(row.tokenHash()), any(Instant.class));
  }

  @Test
  void ignoresMalformedAndUnrestorableBearerTokens() {
    ConanAuthService service = new ConanAuthService(registry, authentication, 60);
    assertFalse(service.hasConanBearer(null));
    assertFalse(service.authenticate(null, 1L).isPresent());

    for (String header : new String[] {
        "Basic abc", "Bearer unrelated", "Bearer kkrepo_conan_bad\nvalue",
        "Bearer kkrepo_conan_" + "x".repeat(300)}) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader("Authorization", header);
      assertFalse(service.hasConanBearer(request));
      assertFalse(service.authenticate(request, 1L).isPresent());
    }
    verify(registry, never()).findValidAuthToken(anyString(), eq(1L), any(Instant.class));

    MockHttpServletRequest validShape = new MockHttpServletRequest();
    validShape.addHeader("Authorization", "Bearer kkrepo_conan_missing");
    when(registry.findValidAuthToken(anyString(), eq(1L), any(Instant.class)))
        .thenReturn(Optional.of(new ConanRegistryDao.AuthToken(
            "a".repeat(64), 1L, "LOCAL", "gone", null, null,
            Instant.now().plusSeconds(60), null, Instant.now())));
    when(authentication.authenticateStoredSubject("LOCAL", "gone", null, null))
        .thenReturn(Optional.empty());

    assertFalse(service.authenticate(validShape, 1L).isPresent());
    verify(registry, never()).touchAuthToken(anyString(), any(Instant.class));
  }
}
