package com.github.klboke.kkrepo.auth;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public interface AccessDecisionService {
  AccessDecision decide(PermissionSubject subject, RepositoryPermission permission);

  /** Evaluate a bounded set of paths against current durable grants, bypassing authorization caches. */
  default Map<RepositoryPermission, AccessDecision> decideAllFresh(
      PermissionSubject subject, Collection<RepositoryPermission> permissions) {
    Map<RepositoryPermission, AccessDecision> result = new LinkedHashMap<>();
    for (var permission : permissions) result.put(permission, decide(subject, permission));
    return Map.copyOf(result);
  }
}
