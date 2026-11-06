package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Serializes the future live component of a staged asset into the staging item attributes.
 *
 * <p>Format adapters create the component projection while the payload is inspected. Promotion
 * then rebuilds it without knowing the format, which keeps promotion atomic and format-neutral.
 */
public final class StagingComponents {
  public static final String ATTRIBUTE = "stagingComponent";
  public static final String UNINDEXED_ATTRIBUTE = "stagingUnindexed";

  private StagingComponents() {
  }

  public static Map<String, Object> encode(ComponentRecord component) {
    Map<String, Object> encoded = new LinkedHashMap<>();
    encoded.put("namespace", component.namespace());
    encoded.put("name", component.name());
    encoded.put("version", component.version());
    encoded.put("kind", component.kind());
    encoded.put("attributes", component.attributes() == null ? Map.of() : component.attributes());
    return encoded;
  }

  @SuppressWarnings("unchecked")
  public static Optional<ComponentRecord> decode(
      long repositoryId,
      RepositoryFormat format,
      Map<String, Object> itemAttributes,
      Instant timestamp) {
    if (itemAttributes == null) {
      return Optional.empty();
    }
    Object value = itemAttributes.get(ATTRIBUTE);
    if (!(value instanceof Map<?, ?> raw)) {
      return Optional.empty();
    }
    Map<String, Object> component = (Map<String, Object>) raw;
    String name = string(component.get("name"));
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    String namespace = string(component.get("namespace"));
    String version = string(component.get("version"));
    String kind = string(component.get("kind"));
    Map<String, Object> attributes = map(component.get("attributes"));
    return Optional.of(new ComponentRecord(
        null,
        repositoryId,
        format,
        namespace,
        name,
        version,
        kind,
        PersistenceHashes.componentCoordinateHash(namespace, name, version),
        attributes,
        timestamp));
  }

  public static boolean unindexed(Map<String, Object> itemAttributes) {
    return itemAttributes != null
        && Boolean.TRUE.equals(itemAttributes.get(UNINDEXED_ATTRIBUTE));
  }

  private static String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
  }
}
