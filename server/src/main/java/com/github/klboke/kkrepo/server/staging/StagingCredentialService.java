package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
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
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues short-lived credentials scoped to one staging session.
 *
 * <p>The token is a normal API key owned by the session creator, so authorization still uses the
 * creator's roles. Its scopes bind every repository request to the staging session, while
 * expiry/revocation are enforced by the shared API-key table and session lifecycle.
 */
@Service
public class StagingCredentialService {
  public static final String DOMAIN = "StagingToken";
  public static final String SESSION_ATTRIBUTE =
      StagingCredentialService.class.getName() + ".session";
  private static final String SCOPE_SESSION = "staging:session:";

  private final SecurityDao security;
  private final SecurityManagementService securityManagement;
  private final StagingDao staging;

  public StagingCredentialService(
      SecurityDao security,
      SecurityManagementService securityManagement,
      StagingDao staging) {
    this.security = security;
    this.securityManagement = securityManagement;
    this.staging = staging;
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
                SCOPE_SESSION + session.id(),
                "staging:name:" + session.name(),
                "staging:owner:" + actor.source() + ":" + actor.userId()),
            expiry(session),
            null,
            null));
    return new IssuedCredential(created.token(), created.apiKey().id());
  }

  public Optional<StagingSessionRecord> resolve(
      HttpServletRequest request, AuthenticatedSubject subject) {
    String explicit = request.getHeader(StagingService.HEADER);
    if (explicit != null && !explicit.isBlank()) {
      return Optional.empty();
    }
    if (request.getParameter("stagingSession") != null
        && !request.getParameter("stagingSession").isBlank()) {
      return Optional.empty();
    }
    if (subject == null || subject.apiKeyId() == null) {
      return Optional.empty();
    }
    Optional<ApiKeyRecord> key = security.findApiKey(subject.apiKeyId());
    if (key.isEmpty() || !DOMAIN.equals(key.get().domain())) {
      return Optional.empty();
    }
    Optional<Long> sessionId = sessionId(key.get());
    if (sessionId.isEmpty()) return Optional.empty();
    Optional<StagingSessionRecord> session = staging.findSessionById(sessionId.get());
    if (session.isEmpty()) return Optional.empty();
    StagingSessionRecord found = session.get();
    if (!java.util.Objects.equals(found.ownerSource(), subject.source())
        || !java.util.Objects.equals(found.ownerUserId(), subject.userId())) {
      return Optional.empty();
    }
    return Optional.of(found);
  }

  public void bind(HttpServletRequest request, AuthenticatedSubject subject) {
    resolve(request, subject).ifPresent(session ->
        request.setAttribute(SESSION_ATTRIBUTE, String.valueOf(session.id())));
  }

  public void revoke(StagingSessionRecord session) {
    Object value = session.attributes() == null
        ? null : session.attributes().get("stagingTokenId");
    if (!(value instanceof Number number)) return;
    long id = number.longValue();
    if (id <= 0) return;
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
    attributes.put("stagingTokenId", credential.apiKeyId());
    attributes.put("stagingTokenIssuedAt", java.time.Instant.now().toString());
    return attributes;
  }

  private static LocalDateTime expiry(StagingSessionRecord session) {
    if (session.expiresAt() == null) return null;
    try {
      return LocalDateTime.ofInstant(session.expiresAt(), ZoneOffset.UTC);
    } catch (java.time.DateTimeException overflow) {
      return null;
    }
  }

  private static Optional<Long> sessionId(ApiKeyRecord key) {
    Object scopes = key.scopes() == null ? null : key.scopes().get("values");
    if (!(scopes instanceof Iterable<?> values)) return Optional.empty();
    for (Object value : values) {
      if (value == null) continue;
      String text = value.toString();
      if (!text.startsWith(SCOPE_SESSION)) continue;
      try {
        long id = Long.parseLong(text.substring(SCOPE_SESSION.length()));
        if (id > 0) return Optional.of(id);
      } catch (NumberFormatException ignored) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  public record IssuedCredential(String token, Long apiKeyId) {
  }
}
