package com.github.klboke.kkrepo.migration.nexus.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.klboke.kkrepo.persistence.jdbc.api.MigrationCheckpointDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationCheckpointRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NexusSecurityMigrationCheckpointWriter {
  private static final String SOURCE_DATABASE = "security";

  private final CheckpointSink checkpointSink;
  private final ObjectMapper checksumMapper;

  public NexusSecurityMigrationCheckpointWriter(
      MigrationCheckpointDao checkpointDao,
      ObjectMapper objectMapper) {
    this(checkpointDao::upsert, objectMapper);
  }

  NexusSecurityMigrationCheckpointWriter(
      CheckpointSink checkpointSink,
      ObjectMapper objectMapper) {
    this.checkpointSink = checkpointSink;
    this.checksumMapper = objectMapper.copy()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  public int write(long jobId, NexusSecurityExport export) {
    NexusSecurityExport source = export == null ? NexusSecurityExport.empty() : export;
    int count = 0;
    count += writeDocuments(jobId, "user", "security_user", source.users(), this::userTargetId);
    count += writeDocuments(jobId, "role", "security_role", source.roles(), this::idTargetId);
    count += writeDocuments(jobId, "privilege", "security_privilege", source.privileges(), this::idTargetId);
    count += writeDocuments(
        jobId,
        "user_role_mapping",
        "security_user_role",
        source.userRoleMappings(),
        this::userRoleMappingTargetId);
    count += writeDocuments(jobId, "api_key", "api_key", source.apiKeys(), this::apiKeyTargetId);
    count += writeDocuments(
        jobId,
        "selector",
        "security_repository_target",
        source.contentSelectors(),
        this::contentSelectorTargetId);
    if (!source.realmOrder().isEmpty()) {
      Map<String, Object> realmConfig = Map.of("realmOrder", source.realmOrder());
      writeCheckpoint(
          jobId,
          "realm_configuration",
          "realm_names",
          "security_realm_config",
          "1",
          realmConfig);
      count++;
    }
    if (!source.anonymous().isEmpty()) {
      writeCheckpoint(
          jobId,
          "anonymous",
          sourceRid(source.anonymous(), "anonymous"),
          "security_anonymous_config",
          "1",
          source.anonymous());
      count++;
    }
    return count;
  }

  private int writeDocuments(
      long jobId,
      String sourceClass,
      String targetTable,
      List<Map<String, Object>> documents,
      TargetIdResolver targetIdResolver) {
    int count = 0;
    for (Map<String, Object> document : documents) {
      String targetId = targetIdResolver.resolve(document);
      writeCheckpoint(
          jobId,
          sourceClass,
          sourceRid(document, targetId),
          targetTable,
          targetId,
          document);
      count++;
    }
    return count;
  }

  private void writeCheckpoint(
      long jobId,
      String sourceClass,
      String sourceRid,
      String targetTable,
      String targetId,
      Map<String, Object> sourceDocument) {
    checkpointSink.upsert(new MigrationCheckpointRecord(
        jobId,
        SOURCE_DATABASE,
        sourceClass,
        sourceRid,
        targetTable,
        targetId,
        checksum(sourceDocument),
        null));
  }

  private String userTargetId(Map<String, Object> document) {
    return NexusSecuritySources.normalizeSource(string(document, "source"))
        + "/"
        + requireText(string(document, "id"), "user.id");
  }

  private String idTargetId(Map<String, Object> document) {
    return requireText(string(document, "id"), "id");
  }

  private String userRoleMappingTargetId(Map<String, Object> document) {
    return NexusSecuritySources.normalizeSource(string(document, "source"))
        + "/"
        + requireText(string(document, "userId"), "userRoleMapping.userId");
  }

  private String apiKeyTargetId(Map<String, Object> document) {
    Map<String, Object> principals = objectMap(document.get("principals"));
    String ownerSource = NexusSecuritySources.normalizeSource(
        defaultString(firstString(document, "ownerSource", "source"), sourceFromPrincipals(principals)));
    String ownerUserId = requireText(
        defaultString(
            firstString(document, "ownerUserId", "primary_principal", "primaryPrincipal"),
            principalUserId(principals)),
        "apiKey.ownerUserId");
    return requireText(string(document, "domain"), "apiKey.domain") + "/" + ownerSource + "/" + ownerUserId;
  }

  private String contentSelectorTargetId(Map<String, Object> document) {
    return requireText(string(document, "name"), "contentSelector.name");
  }

  private String sourceRid(Map<String, Object> document, String fallback) {
    return defaultString(firstString(document, "@rid", "rid"), fallback);
  }

  private String checksum(Map<String, Object> sourceDocument) {
    try {
      return sha256(checksumMapper.writeValueAsString(stableMap(sourceDocument)));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to checksum Nexus security source document", e);
    }
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

  private static Map<String, Object> stableMap(Map<String, Object> source) {
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    source.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> copy.put(entry.getKey(), stableValue(entry.getValue())));
    return copy;
  }

  @SuppressWarnings("unchecked")
  private static Object stableValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
      map.entrySet().stream()
          .filter(entry -> entry.getKey() != null)
          .sorted((left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey())))
          .forEach(entry -> copy.put(String.valueOf(entry.getKey()), stableValue(entry.getValue())));
      return copy;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(NexusSecurityMigrationCheckpointWriter::stableValue).toList();
    }
    return value;
  }

  private static String firstString(Map<String, Object> document, String... keys) {
    for (String key : keys) {
      String value = string(document, key);
      if (value != null) {
        return value;
      }
    }
    return null;
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

  private static String string(Map<String, Object> document, String key) {
    Object value = document.get(key);
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static Map<String, Object> objectMap(Object value) {
    if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = entry.getKey() == null ? null : String.valueOf(entry.getKey()).trim();
      if (key != null && !key.isEmpty() && entry.getValue() != null) {
        copy.put(key, entry.getValue());
      }
    }
    return Map.copyOf(copy);
  }

  private static List<String> stringList(Object value) {
    if (value == null) {
      return List.of();
    }
    if (value instanceof Iterable<?> iterable) {
      java.util.ArrayList<String> values = new java.util.ArrayList<>();
      for (Object item : iterable) {
        String text = item == null ? null : String.valueOf(item).trim();
        if (text != null && !text.isEmpty()) {
          values.add(text);
        }
      }
      return List.copyOf(values);
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? List.of() : List.of(text);
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

  private static String requireText(String value, String name) {
    String normalized = defaultString(value, null);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " is required for security migration checkpoint");
    }
    return normalized;
  }

  @FunctionalInterface
  interface CheckpointSink {
    void upsert(MigrationCheckpointRecord record);
  }

  @FunctionalInterface
  private interface TargetIdResolver {
    String resolve(Map<String, Object> document);
  }
}
