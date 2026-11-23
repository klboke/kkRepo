package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ExternalRoleMapping;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Role mapping configuration lives on the provider record shared by every replica. */
final class ExternalRoleMappings {
  static final List<String> REALMS = List.of("ldap", "oidc");
  static final String MAPPINGS = "externalRoleMappings";
  static final String LEGACY_MATCHING = "legacyRoleMatching";
  static final String USER_REALM = "externalRoleRealm";

  private ExternalRoleMappings() {
  }

  static boolean legacyMatching(SecurityRealmRecord realm) {
    return !Boolean.FALSE.equals(attributes(realm).get(LEGACY_MATCHING));
  }

  static Set<String> resolve(SecurityRealmRecord realm, Collection<String> names) {
    if (!realm.enabled() || names.isEmpty()) {
      return Set.of();
    }
    Set<String> roles = new LinkedHashSet<>();
    if (legacyMatching(realm)) {
      roles.addAll(names);
    }
    byRole(realm).forEach((roleId, externalNames) -> {
      if (externalNames.stream().anyMatch(names::contains)) {
        roles.add(roleId);
      }
    });
    return roles;
  }

  static Map<String, List<String>> byRole(SecurityRealmRecord realm) {
    Map<String, List<String>> mappings = new LinkedHashMap<>();
    if (attributes(realm).get(MAPPINGS) instanceof Map<?, ?> entries) {
      entries.forEach((roleId, values) -> {
        if (roleId instanceof String id) {
          List<String> names = names(values);
          if (!names.isEmpty()) {
            mappings.put(id, names);
          }
        }
      });
    }
    return mappings;
  }

  static Map<String, List<ExternalRoleMapping>> list(Collection<SecurityRealmRecord> realms) {
    Map<String, List<ExternalRoleMapping>> result = new LinkedHashMap<>();
    for (SecurityRealmRecord realm : realms) {
      if (REALMS.contains(realm.realmId())) {
        byRole(realm).forEach((role, names) -> names.forEach(name ->
            result.computeIfAbsent(role, ignored -> new ArrayList<>())
                .add(new ExternalRoleMapping(realm.realmId(), name))));
      }
    }
    return result;
  }

  static List<String> names(Object value) {
    if (value instanceof Collection<?> values) {
      return values.stream().filter(String.class::isInstance).map(String.class::cast)
          .map(String::trim).filter(name -> !name.isEmpty()).distinct().toList();
    }
    return value instanceof String name && !name.isBlank() ? List.of(name.trim()) : List.of();
  }

  static Map<String, Object> attributes(SecurityRealmRecord realm) {
    return realm.attributes() == null ? Map.of() : realm.attributes();
  }
}
