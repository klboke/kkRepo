package com.github.klboke.kkrepo.migration.nexus.security;

import com.github.klboke.kkrepo.core.security.ApiKeyTokenPayloads;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class NexusSecurityRecordMapper {
  private static final String DEFAULT_ANONYMOUS_USER_ID = "anonymous";
  private static final String STATUS_ACTIVE = "active";

  public SecurityUserRecord mapUser(NexusUser user) {
    return new SecurityUserRecord(
        null,
        NexusSecuritySources.normalizeSource(user.source()),
        requireText(user.id(), "user.id"),
        blankToNull(user.firstName()),
        blankToNull(user.lastName()),
        blankToNull(user.email()),
        blankToNull(user.passwordHash()),
        defaultString(user.status(), STATUS_ACTIVE),
        null,
        copyAttributes(user.attributes()));
  }

  public MappedRole mapRole(NexusRole role) {
    SecurityRoleRecord record = new SecurityRoleRecord(
        requireText(role.id(), "role.id"),
        NexusSecuritySources.DEFAULT_SOURCE,
        defaultString(role.name(), role.id()),
        blankToNull(role.description()),
        role.readOnly(),
        copyAttributes(role.attributes()));
    return new MappedRole(record, normalizeList(role.privileges()), normalizeList(role.roles()));
  }

  public MappedUserRoleMapping mapUserRoleMapping(NexusUserRoleMapping mapping) {
    return new MappedUserRoleMapping(
        NexusSecuritySources.normalizeSource(mapping.source()),
        requireText(mapping.userId(), "userRoleMapping.userId"),
        normalizeList(mapping.roles()));
  }

  public SecurityPrivilegeRecord mapPrivilege(NexusPrivilege privilege) {
    return new SecurityPrivilegeRecord(
        requireText(privilege.id(), "privilege.id"),
        defaultString(privilege.name(), privilege.id()),
        blankToNull(privilege.description()),
        requireText(privilege.type(), "privilege.type"),
        privilege.readOnly(),
        normalizePrivilegeProperties(privilege.type(), privilege.properties()));
  }

  public SecurityRepositoryTargetRecord mapContentSelector(NexusContentSelector selector) {
    String name = requireText(selector.name(), "contentSelector.name");
    String expression = requireText(selector.expression(), "contentSelector.expression");
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("source", "nexus-content-selector");
    attributes.put("type", defaultString(selector.type(), "csel"));
    String description = blankToNull(selector.description());
    if (description != null) {
      attributes.put("description", description);
    }
    if (selector.attributes() != null) {
      attributes.putAll(selector.attributes());
    }
    return new SecurityRepositoryTargetRecord(
        null,
        name,
        name,
        defaultString(selector.format(), "*").toLowerCase(Locale.ROOT),
        expression,
        Map.of("patterns", List.of()),
        Map.copyOf(attributes));
  }

  public ApiKeyRecord mapApiKey(NexusApiKey apiKey) {
    String domain = requireText(apiKey.domain(), "apiKey.domain");
    String rawKey = requireText(apiKey.rawApiKey(), "apiKey.rawApiKey");
    String ownerUserId = requireText(apiKey.ownerUserId(), "apiKey.ownerUserId");
    String token = domain + "." + rawKey;
    return new ApiKeyRecord(
        null,
        domain,
        NexusSecuritySources.normalizeSource(apiKey.ownerSource()),
        ownerUserId,
        blankToNull(apiKey.displayName()),
        defaultString(apiKey.status(), "ACTIVE"),
        sha256(rawKey),
        token.substring(0, Math.min(12, token.length())),
        Map.of("source", "nexus-orient", "values", List.of()),
        ApiKeyTokenPayloads.encryptRawToken(rawKey),
        null,
        null,
        null,
        null);
  }

  public SecurityAnonymousConfigRecord mapAnonymousConfig(NexusAnonymousConfig config) {
    String realmName = defaultString(config.realmName(), NexusSecuritySources.DEFAULT_ANONYMOUS_REALM_NAME);
    return new SecurityAnonymousConfigRecord(
        config.enabled(),
        blankToNull(config.userSource()) == null
            ? NexusSecuritySources.sourceForRealmName(realmName)
            : NexusSecuritySources.normalizeSource(config.userSource()),
        defaultString(config.userId(), DEFAULT_ANONYMOUS_USER_ID),
        realmName);
  }

  public List<SecurityRealmRecord> mapRealmOrder(List<String> nexusRealmOrder) {
    List<SecurityRealmRecord> records = new ArrayList<>();
    Set<String> seenRealmIds = new LinkedHashSet<>();
    int priority = 0;
    for (String nexusRealm : normalizeList(nexusRealmOrder)) {
      RealmMapping mapping = realmMapping(nexusRealm);
      if (mapping == null) {
        continue;
      }
      if (!seenRealmIds.add(mapping.realmId())) {
        continue;
      }
      records.add(new SecurityRealmRecord(
          null,
          mapping.realmId(),
          mapping.type(),
          mapping.name(),
          true,
          priority,
          Map.of("nexusRealm", nexusRealm, "source", mapping.source())));
      priority += 10;
    }
    return records;
  }

  private static RealmMapping realmMapping(String nexusRealm) {
    String normalized = nexusRealm.toLowerCase(Locale.ROOT);
    if (normalized.contains("ldap")) {
      return new RealmMapping("ldap", "LDAP", "LDAP", "LDAP");
    }
    if (normalized.contains("oidc")) {
      return new RealmMapping("oidc", "OIDC", "OIDC", "OIDC");
    }
    if (normalized.contains("nexus") || normalized.contains("local")) {
      return new RealmMapping("local", "LOCAL", "Local", NexusSecuritySources.DEFAULT_SOURCE);
    }
    return null;
  }

  private static Map<String, Object> copyStringProperties(Map<String, String> values) {
    if (values == null || values.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    values.forEach((key, value) -> {
      String cleanKey = blankToNull(key);
      if (cleanKey != null) {
        copy.put(cleanKey, value);
      }
    });
    return Map.copyOf(copy);
  }

  private static Map<String, Object> normalizePrivilegeProperties(String type, Map<String, String> values) {
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>(copyStringProperties(values));
    String normalizedType = defaultString(type, "");
    switch (normalizedType) {
      case "repository-view", "repository-admin" -> {
        copyAlias(copy, "repository", "repositoryId", "repositoryName");
        copyAlias(copy, "format", "repositoryFormat", "formatId");
        copyAlias(copy, "actions", "action");
      }
      case "repository-content-selector" -> {
        copyAlias(copy, "contentSelector", "selector", "selectorName", "contentSelectorName");
        copyAlias(copy, "repository", "repositoryId", "repositoryName");
        copyAlias(copy, "format", "repositoryFormat", "formatId");
        copyAlias(copy, "actions", "action");
      }
      case "application" -> copyAlias(copy, "actions", "action");
      case "wildcard" -> copyAlias(copy, "pattern", "permission", "wildcard");
      default -> {
      }
    }
    return Map.copyOf(copy);
  }

  private static void copyAlias(Map<String, Object> properties, String canonicalKey, String... aliases) {
    if (blankToNull(asString(properties.get(canonicalKey))) != null) {
      return;
    }
    for (String alias : aliases) {
      String value = blankToNull(asString(properties.get(alias)));
      if (value != null) {
        properties.put(canonicalKey, value);
        return;
      }
    }
  }

  private static Map<String, Object> copyAttributes(Map<String, Object> attributes) {
    if (attributes == null || attributes.isEmpty()) {
      return Map.of();
    }
    return Map.copyOf(attributes);
  }

  private static List<String> normalizeList(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream()
        .map(NexusSecurityRecordMapper::blankToNull)
        .filter(value -> value != null)
        .distinct()
        .toList();
  }

  private static String requireText(String value, String name) {
    String normalized = blankToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }

  private static String defaultString(String value, String fallback) {
    String normalized = blankToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  public record NexusUser(
      String id,
      String source,
      String firstName,
      String lastName,
      String email,
      String passwordHash,
      String status,
      Map<String, Object> attributes) {
  }

  public record NexusRole(
      String id,
      String source,
      String name,
      String description,
      boolean readOnly,
      List<String> privileges,
      List<String> roles,
      Map<String, Object> attributes) {
  }

  public record NexusUserRoleMapping(
      String userId,
      String source,
      List<String> roles) {
  }

  public record NexusPrivilege(
      String id,
      String name,
      String description,
      String type,
      boolean readOnly,
      Map<String, String> properties) {
  }

  public record NexusContentSelector(
      String name,
      String type,
      String description,
      String expression,
      String format,
      Map<String, Object> attributes) {
  }

  public record NexusApiKey(
      String domain,
      String ownerSource,
      String ownerUserId,
      String rawApiKey,
      String displayName,
      String status) {
  }

  public record NexusAnonymousConfig(
      boolean enabled,
      String userSource,
      String userId,
      String realmName) {
  }

  public record MappedRole(
      SecurityRoleRecord record,
      List<String> privileges,
      List<String> childRoles) {
  }

  public record MappedUserRoleMapping(
      String source,
      String userId,
      List<String> roles) {
  }

  private record RealmMapping(
      String realmId,
      String type,
      String name,
      String source) {
  }
}
