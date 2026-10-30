package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues short-lived credentials scoped to one staging session.
 *
 * <p>The token is a normal API key owned by the session creator, so authorization still uses the
 * creator's roles. Its scopes bind every repository request to the staging session, while
 * expiry/revocation are enforced by the shared API-key table and session lifecycle.
 *
 * <p>The binding is enforced centrally: every authentication boundary calls {@link #bind}, which
 * always records the session carried by the token scopes — even when the session is stale, belongs
 * to another user, or the client also sent an explicit selector. {@link StagingSelector} rejects an
 * explicit selector that contradicts that binding, and a malformed token scope is bound to {@value
 * #INVALID_SESSION} so the request fails at the staging service instead of silently publishing to
 * the live repository.
 */
@Service
public class StagingCredentialService {
  private static final Logger log = LoggerFactory.getLogger(StagingCredentialService.class);
  public static final String DOMAIN = "StagingToken";
  public static final String SESSION_ATTRIBUTE =
      StagingCredentialService.class.getName() + ".session";
  /** Fail-closed marker bound when a staging credential carries no usable session scope. */
  public static final String INVALID_SESSION = "invalid";
  public static final String TOKEN_ID_ATTRIBUTE = "stagingTokenId";
  public static final String TOKEN_ISSUED_AT_ATTRIBUTE = "stagingTokenIssuedAt";

  private final SecurityDao security;
  private final SecurityManagementService securityManagement;

  public StagingCredentialService(
      SecurityDao security,
      SecurityManagementService securityManagement) {
    this.security = security;
    this.securityManagement = securityManagement;
  }

  public IssuedCredential issue(StagingSessionRecord session, PermissionSubject actor) {
    if (actor == null || actor.source() == null || actor.userId() == null) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "A concrete user is required for staging credentials");
    }
    var created = securityManagement.createApiKeyForOwnerUncached(
        actor.source(), actor.userId(),
        new ApiKeyCommand(
            DOMAIN,
            actor.source(),
            actor.userId(),
            "Staging session " + session.name(),
            "ACTIVE",
            List.of(
                StagingScopes.SESSION_PREFIX + session.id(),
                "staging:name:" + session.name(),
                "staging:owner:" + actor.source() + ":" + actor.userId()),
            expiry(session),
            null,
            null));
    return new IssuedCredential(created.token(), created.apiKey().id());
  }

  /**
   * Binds the session carried by a staging credential to the request.
   *
   * <p>This method never throws: a staging token whose scopes cannot be interpreted is bound to
   * {@value #INVALID_SESSION}, which the staging service turns into a 404 instead of falling back to
   * live content. Non-staging API keys are ignored.
   */
  public void bind(HttpServletRequest request, AuthenticatedSubject subject) {
    if (request == null || subject == null || subject.apiKeyId() == null) {
      return;
    }
    Optional<ApiKeyRecord> key = security.findApiKey(subject.apiKeyId());
    if (key.isEmpty() || !StagingScopes.isStagingToken(key.get())) {
      return;
    }
    request.setAttribute(
        SESSION_ATTRIBUTE,
        StagingScopes.sessionId(key.get())
            .map(String::valueOf)
            .orElse(INVALID_SESSION));
  }

  /**
   * Revokes the API key recorded by the server when the session was created.
   *
   * <p>Session attributes are user-editable through {@code PATCH /sessions/{id}}, so the stored id
   * is only trusted after the key is proven to be a staging key owned by this session's owner and
   * scoped to this very session. Anything else is left untouched.
   */
  public void revoke(StagingSessionRecord session) {
    Object value = session.attributes() == null
        ? null : session.attributes().get(TOKEN_ID_ATTRIBUTE);
    if (!(value instanceof Number number)) {
      return;
    }
    long id = number.longValue();
    if (id <= 0) {
      return;
    }
    Optional<ApiKeyRecord> key = security.findApiKey(id);
    if (key.isEmpty() || !ownedBy(key.get(), session) || !scopedTo(key.get(), session.id())) {
      log.debug("Ignoring staging token {} for session {}: not a credential of this session",
          id, session.id());
      return;
    }
    try {
      securityManagement.deleteApiKey(id);
    } catch (RuntimeException ignored) {
      // A concurrent close/cancel may already have revoked the token.
    }
  }

  public Map<String, Object> withToken(
      StagingSessionRecord session, IssuedCredential credential) {
    Map<String, Object> attributes = new LinkedHashMap<>(
        session.attributes() == null ? Map.of() : session.attributes());
    attributes.put(TOKEN_ID_ATTRIBUTE, credential.apiKeyId());
    attributes.put(TOKEN_ISSUED_AT_ATTRIBUTE, java.time.Instant.now().toString());
    return attributes;
  }

  private static boolean ownedBy(ApiKeyRecord key, StagingSessionRecord session) {
    return StagingScopes.isStagingToken(key)
        && Objects.equals(key.ownerSource(), session.ownerSource())
        && Objects.equals(key.ownerUserId(), session.ownerUserId());
  }

  private static boolean scopedTo(ApiKeyRecord key, long sessionId) {
    return StagingScopes.sessionId(key).map(id -> id == sessionId).orElse(false);
  }

  private static LocalDateTime expiry(StagingSessionRecord session) {
    if (session.expiresAt() == null) return null;
    try {
      return LocalDateTime.ofInstant(session.expiresAt(), ZoneOffset.UTC);
    } catch (java.time.DateTimeException overflow) {
      return null;
    }
  }

  public record IssuedCredential(String token, Long apiKeyId) {
  }
}
