package com.github.klboke.kkrepo.server.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import java.io.Serializable;
import java.util.Set;

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
 *
 * @param apiKeyGeneration fingerprint of the API key secret this subject authenticated with (never
 *     the stored hash itself); bearers issued from the subject persist it, so a key reset on any
 *     replica invalidates them even when another replica still caches the old credential.
 */
public record AuthenticatedSubject(
    String source,
    String userId,
    String realmId,
    Long apiKeyId,
    PermissionSubject permissionSubject,
    Set<String> externalRoleNames,
    String apiKeyGeneration,
    Boolean stagingToken,
    String stagingSession) implements Serializable {

  public AuthenticatedSubject(String source, String userId, String realmId, Long apiKeyId,
      PermissionSubject permissionSubject) {
    this(source, userId, realmId, apiKeyId, permissionSubject, null, null, null, null);
  }

  /** Full constructor without the staging classification, used by callers outside this package. */
  public AuthenticatedSubject(
      String source,
      String userId,
      String realmId,
      Long apiKeyId,
      PermissionSubject permissionSubject,
      Set<String> externalRoleNames,
      String apiKeyGeneration) {
    this(source, userId, realmId, apiKeyId, permissionSubject, externalRoleNames,
        apiKeyGeneration, null, null);
  }

  /** Staging-aware constructor retained for the staging feature and its focused tests. */
  public AuthenticatedSubject(
      String source,
      String userId,
      String realmId,
      Long apiKeyId,
      PermissionSubject permissionSubject,
      Boolean stagingToken,
      String stagingSession,
      Set<String> externalRoleNames) {
    this(source, userId, realmId, apiKeyId, permissionSubject, externalRoleNames, null,
        stagingToken, stagingSession);
  }

  AuthenticatedSubject withExternalRoleNames(Set<String> names) {
    return new AuthenticatedSubject(source, userId, realmId, apiKeyId, permissionSubject, names,
        apiKeyGeneration, stagingToken, stagingSession);
  }

  AuthenticatedSubject withApiKeyGeneration(String generation) {
    return new AuthenticatedSubject(
        source, userId, realmId, apiKeyId, permissionSubject, externalRoleNames, generation,
        stagingToken, stagingSession);
  }

  public static final String REQUEST_ATTRIBUTE = AuthenticatedSubject.class.getName();
  public static final String SESSION_ATTRIBUTE = AuthenticatedSubject.class.getName() + ".SESSION";

  /**
   * True only when this subject is proven to come from a staging session credential.
   *
   * <p>Ignored by Jackson: the derived boolean would otherwise shadow the nullable {@code
   * stagingToken} record component during serialization and turn "unknown" into {@code false}.
   */
  @JsonIgnore
  public boolean isStagingToken() {
    return Boolean.TRUE.equals(stagingToken);
  }

  /**
   * True when the shared row already classified this credential (staging or ordinary). Ignored by
   * Jackson for the same reason as {@link #isStagingToken()}.
   */
  @JsonIgnore
  public boolean hasStagingClassification() {
    return stagingToken != null;
  }

  /** Returns a copy bound to {@code session}, keeping an existing staging classification. */
  public AuthenticatedSubject withStagingSession(String session) {
    return new AuthenticatedSubject(
        source, userId, realmId, apiKeyId, permissionSubject, externalRoleNames, apiKeyGeneration,
        Boolean.TRUE, session);
  }

  /** Returns a copy proven to be an ordinary (non-staging) API key. */
  public AuthenticatedSubject asOrdinaryApiKey() {
    return new AuthenticatedSubject(
        source, userId, realmId, apiKeyId, permissionSubject, externalRoleNames, apiKeyGeneration,
        Boolean.FALSE, null);
  }
}
