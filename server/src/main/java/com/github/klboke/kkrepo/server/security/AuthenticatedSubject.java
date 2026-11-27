package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import java.io.Serializable;
import java.util.Set;

/**
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
    String apiKeyGeneration) implements Serializable {
  public AuthenticatedSubject(String source, String userId, String realmId, Long apiKeyId,
      PermissionSubject permissionSubject) {
    this(source, userId, realmId, apiKeyId, permissionSubject, null, null);
  }

  AuthenticatedSubject withExternalRoleNames(Set<String> names) {
    return new AuthenticatedSubject(
        source, userId, realmId, apiKeyId, permissionSubject, names, apiKeyGeneration);
  }

  AuthenticatedSubject withApiKeyGeneration(String generation) {
    return new AuthenticatedSubject(
        source, userId, realmId, apiKeyId, permissionSubject, externalRoleNames, generation);
  }

  public static final String REQUEST_ATTRIBUTE = AuthenticatedSubject.class.getName();
  public static final String SESSION_ATTRIBUTE = AuthenticatedSubject.class.getName() + ".SESSION";
}
