package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import java.io.Serializable;

/**
 * Authenticated identity attached to one request.
 *
 * <p>{@code stagingToken} records whether the credential that produced the subject is a staging
 * session credential, and {@code stagingSession} carries the session id it was scoped to (or
 * {@code "invalid"} when the scope cannot be interpreted). The pair is resolved from the shared
 * {@code api_key} row before the subject is cached by {@link ApiKeyAuthCache}: a cache hit on
 * another replica must keep the staging restriction even when the row has been removed by a
 * concurrent cancel or rotation. {@code null} means "unknown" (an entry written by an older
 * replica, or a subject built by a caller that does not authenticate an API key); such a subject is
 * re-resolved against the shared row before it can authorize anything.
 */
public record AuthenticatedSubject(
    String source,
    String userId,
    String realmId,
    Long apiKeyId,
    PermissionSubject permissionSubject,
    Boolean stagingToken,
    String stagingSession) implements Serializable {
  public static final String REQUEST_ATTRIBUTE = AuthenticatedSubject.class.getName();
  public static final String SESSION_ATTRIBUTE = AuthenticatedSubject.class.getName() + ".SESSION";

  public AuthenticatedSubject(
      String source,
      String userId,
      String realmId,
      Long apiKeyId,
      PermissionSubject permissionSubject) {
    this(source, userId, realmId, apiKeyId, permissionSubject, null, null);
  }

  /** True only when this subject is proven to come from a staging session credential. */
  public boolean isStagingToken() {
    return Boolean.TRUE.equals(stagingToken);
  }

  /** True when the shared row already classified this credential (staging or ordinary). */
  public boolean hasStagingClassification() {
    return stagingToken != null;
  }

  /** Returns a copy bound to {@code session}, keeping an existing staging classification. */
  public AuthenticatedSubject withStagingSession(String session) {
    return new AuthenticatedSubject(
        source, userId, realmId, apiKeyId, permissionSubject, Boolean.TRUE, session);
  }

  /** Returns a copy proven to be an ordinary (non-staging) API key. */
  public AuthenticatedSubject asOrdinaryApiKey() {
    return new AuthenticatedSubject(
        source, userId, realmId, apiKeyId, permissionSubject, Boolean.FALSE, null);
  }
}
