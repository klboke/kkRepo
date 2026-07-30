package com.github.klboke.kkrepo.migration.nexus.security;

import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusApiKey;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusAnonymousConfig;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusContentSelector;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusPrivilege;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusRole;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusUser;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusUserRoleMapping;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NexusSecurityExportReader {

  public NexusSecurityMigrationBatch read(NexusSecurityExport export) {
    NexusSecurityExport source = export == null ? NexusSecurityExport.empty() : export;
    return new NexusSecurityMigrationBatch(
        source.contentSelectors().stream().map(this::contentSelector).toList(),
        source.privileges().stream().map(this::privilege).toList(),
        source.roles().stream().map(this::role).toList(),
        source.users().stream().map(this::user).toList(),
        source.userRoleMappings().stream().map(this::userRoleMapping).toList(),
        source.realmOrder(),
        source.apiKeys().stream().map(this::apiKey).toList(),
        source.anonymous().isEmpty() ? null : anonymousConfig(source.anonymous()));
  }

  private NexusUser user(Map<String, Object> document) {
    return new NexusUser(
        firstString(document, "id", "userId"),
        NexusSecuritySources.normalizeSource(string(document, "source")),
        string(document, "firstName"),
        string(document, "lastName"),
        firstString(document, "email", "emailAddress"),
        firstString(document, "password", "passwordHash", "password_hash"),
        string(document, "status"),
        attributes(document, "user"));
  }

  private NexusRole role(Map<String, Object> document) {
    return new NexusRole(
        string(document, "id"),
        NexusSecuritySources.normalizeSource(string(document, "source")),
        string(document, "name"),
        string(document, "description"),
        bool(document, "readOnly"),
        stringList(document.get("privileges")),
        stringList(document.get("roles")),
        attributes(document, "role"));
  }

  private NexusPrivilege privilege(Map<String, Object> document) {
    return new NexusPrivilege(
        firstString(document, "id", "name"),
        defaultString(string(document, "name"), string(document, "id")),
        string(document, "description"),
        string(document, "type"),
        bool(document, "readOnly"),
        privilegeProperties(document));
  }

  private NexusUserRoleMapping userRoleMapping(Map<String, Object> document) {
    return new NexusUserRoleMapping(
        string(document, "userId"),
        NexusSecuritySources.normalizeSource(string(document, "source")),
        stringList(document.get("roles")));
  }

  private NexusApiKey apiKey(Map<String, Object> document) {
    Map<String, Object> principals = objectMap(document.get("principals"));
    return new NexusApiKey(
        string(document, "domain"),
        NexusSecuritySources.normalizeSource(
            defaultString(firstString(document, "ownerSource", "source"), sourceFromPrincipals(principals))),
        defaultString(
            firstString(document, "ownerUserId", "primary_principal", "primaryPrincipal"),
            principalUserId(principals)),
        firstString(document, "api_key", "apiKey"),
        string(document, "displayName"),
        defaultString(string(document, "status"), "ACTIVE"));
  }

  private NexusAnonymousConfig anonymousConfig(Map<String, Object> document) {
    return new NexusAnonymousConfig(
        bool(document, "enabled"),
        firstString(document, "userSource", "user_source", "source"),
        firstString(document, "userId", "user_id"),
        firstString(document, "realmName", "realm_name"));
  }

  private NexusContentSelector contentSelector(Map<String, Object> document) {
    Map<String, Object> attributes = objectMap(document.get("attributes"));
    return new NexusContentSelector(
        string(document, "name"),
        string(document, "type"),
        string(document, "description"),
        defaultString(asString(attributes.get("expression")), string(document, "expression")),
        defaultString(string(document, "format"), "*"),
        attributes);
  }

  private static Map<String, Object> attributes(Map<String, Object> document, String sourceClass) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.putAll(objectMap(document.get("attributes")));
    Object externalRoles = firstPresent(document, "groups", "externalRoles");
    if (externalRoles != null) {
      attributes.put("groups", stringList(externalRoles));
    }
    attributes.put("sourceClass", sourceClass);
    Object rid = firstPresent(document, "@rid", "rid");
    if (rid != null) {
      attributes.put("rid", String.valueOf(rid));
    }
    Object version = firstPresent(document, "@version", "version");
    if (version != null) {
      attributes.put("version", version);
    }
    return Map.copyOf(attributes);
  }

  private static Object firstPresent(Map<String, Object> document, String... keys) {
    for (String key : keys) {
      Object value = document.get(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String firstString(Map<String, Object> document, String... keys) {
    Object value = firstPresent(document, keys);
    return asString(value);
  }

  private static String string(Map<String, Object> document, String key) {
    return asString(document.get(key));
  }

  private static String asString(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Iterable<?> iterable) {
      java.util.ArrayList<String> values = new java.util.ArrayList<>();
      for (Object item : iterable) {
        String text = asString(item);
        if (text != null) {
          values.add(text);
        }
      }
      return values.isEmpty() ? null : String.join(",", values);
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static boolean bool(Map<String, Object> document, String key) {
    Object value = document.get(key);
    if (value instanceof Boolean booleanValue) {
      return booleanValue;
    }
    return value != null && Boolean.parseBoolean(String.valueOf(value));
  }

  private static List<String> stringList(Object value) {
    if (value == null) {
      return List.of();
    }
    if (value instanceof Iterable<?> iterable) {
      java.util.ArrayList<String> values = new java.util.ArrayList<>();
      for (Object item : iterable) {
        String text = asString(item);
        if (text != null) {
          values.add(text);
        }
      }
      return List.copyOf(values);
    }
    String text = asString(value);
    return text == null ? List.of() : List.of(text);
  }

  private static Map<String, String> stringMap(Object value) {
    if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<String, String> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = asString(entry.getKey());
      String mappedValue = asString(entry.getValue());
      if (key != null && mappedValue != null) {
        copy.put(key, mappedValue);
      }
    }
    return Map.copyOf(copy);
  }

  private static Map<String, String> privilegeProperties(Map<String, Object> document) {
    Map<String, String> nested = stringMap(document.get("properties"));
    if (!nested.isEmpty()) {
      return nested;
    }
    LinkedHashMap<String, String> properties = new LinkedHashMap<>();
    copyProperty(document, properties, "pattern");
    copyProperty(document, properties, "domain");
    copyProperty(document, properties, "actions");
    copyProperty(document, properties, "format");
    copyProperty(document, properties, "repository");
    copyProperty(document, properties, "contentSelector");
    copyProperty(document, properties, "contentSelectorName");
    copyProperty(document, properties, "selector");
    return Map.copyOf(properties);
  }

  private static void copyProperty(
      Map<String, Object> document,
      LinkedHashMap<String, String> properties,
      String key) {
    String value = asString(document.get(key));
    if (value != null) {
      properties.put(key, value);
    }
  }

  private static Map<String, Object> objectMap(Object value) {
    if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = asString(entry.getKey());
      if (key != null && entry.getValue() != null) {
        copy.put(key, entry.getValue());
      }
    }
    return Map.copyOf(copy);
  }

  private static String principalUserId(Map<String, Object> principals) {
    if (principals.isEmpty()) {
      return null;
    }
    String value = firstString(principals, "primaryPrincipal", "primary_principal", "userId", "principal");
    if (value != null) {
      return value;
    }
    List<String> values = stringList(firstPresent(principals, "principals", "primaryPrincipals"));
    return values.isEmpty() ? null : values.get(0);
  }

  private static String sourceFromPrincipals(Map<String, Object> principals) {
    if (principals.isEmpty()) {
      return "default";
    }
    String value = firstString(principals, "source", "realm", "realmName");
    if (value == null) {
      List<String> realms = stringList(firstPresent(principals, "realmNames", "realms"));
      value = realms.isEmpty() ? null : realms.get(0);
    }
    return NexusSecuritySources.sourceForRealmName(value);
  }
}
