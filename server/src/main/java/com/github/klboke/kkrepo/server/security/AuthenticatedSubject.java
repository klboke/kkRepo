package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import java.io.Serializable;
import java.util.Set;

public record AuthenticatedSubject(
    String source,
    String userId,
    String realmId,
    Long apiKeyId,
    PermissionSubject permissionSubject,
    Set<String> externalRoleNames) implements Serializable {
  public AuthenticatedSubject(String source, String userId, String realmId, Long apiKeyId,
      PermissionSubject permissionSubject) {
    this(source, userId, realmId, apiKeyId, permissionSubject, null);
  }

  AuthenticatedSubject withExternalRoleNames(Set<String> names) {
    return new AuthenticatedSubject(source, userId, realmId, apiKeyId, permissionSubject, names);
  }

  public static final String REQUEST_ATTRIBUTE = AuthenticatedSubject.class.getName();
  public static final String SESSION_ATTRIBUTE = AuthenticatedSubject.class.getName() + ".SESSION";
}
