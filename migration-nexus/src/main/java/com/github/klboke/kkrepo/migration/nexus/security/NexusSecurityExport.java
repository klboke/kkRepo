package com.github.klboke.kkrepo.migration.nexus.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record NexusSecurityExport(
    List<Map<String, Object>> users,
    List<Map<String, Object>> roles,
    List<Map<String, Object>> privileges,
    List<Map<String, Object>> userRoleMappings,
    List<Map<String, Object>> apiKeys,
    List<Map<String, Object>> contentSelectors,
    List<Map<String, Object>> repositoryTargets,
    List<String> realmOrder,
    Map<String, Object> anonymous,
    // Pre-3.71 Nexus Pro user token table: row count and owner user names only, never secrets; empty when absent.
    Map<String, Object> legacyUserTokens) {

  @JsonCreator
  public NexusSecurityExport {
    users = safeDocuments(users);
    roles = safeDocuments(roles);
    privileges = safeDocuments(privileges);
    userRoleMappings = safeDocuments(userRoleMappings);
    apiKeys = safeDocuments(apiKeys);
    contentSelectors = safeDocuments(contentSelectors);
    repositoryTargets = safeDocuments(repositoryTargets);
    realmOrder = realmOrder == null ? List.of() : List.copyOf(realmOrder);
    anonymous = safeDocument(anonymous);
    legacyUserTokens = safeDocument(legacyUserTokens);
  }

  public NexusSecurityExport(
      List<Map<String, Object>> users,
      List<Map<String, Object>> roles,
      List<Map<String, Object>> privileges,
      List<Map<String, Object>> userRoleMappings,
      List<Map<String, Object>> apiKeys,
      List<Map<String, Object>> contentSelectors,
      List<Map<String, Object>> repositoryTargets,
      List<String> realmOrder,
      Map<String, Object> anonymous) {
    this(users, roles, privileges, userRoleMappings, apiKeys, contentSelectors, repositoryTargets, realmOrder,
        anonymous, null);
  }

  // Documents carry raw API keys and password hashes: never print them.
  @Override
  public String toString() {
    return "NexusSecurityExport[users=" + users.size() + ", roles=" + roles.size() + ", privileges="
        + privileges.size() + ", apiKeys=" + apiKeys.size() + ", <documents redacted>]";
  }

  public static NexusSecurityExport empty() {
    return new NexusSecurityExport(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of());
  }

  private static List<Map<String, Object>> safeDocuments(List<Map<String, Object>> documents) {
    return documents == null
        ? List.of()
        : documents.stream()
            .filter(Objects::nonNull)
            .map(NexusSecurityExport::safeDocument)
            .toList();
  }

  private static Map<String, Object> safeDocument(Map<String, Object> document) {
    return document == null || document.isEmpty()
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(document));
  }
}
