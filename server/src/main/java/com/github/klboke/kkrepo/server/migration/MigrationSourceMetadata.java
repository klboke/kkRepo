package com.github.klboke.kkrepo.server.migration;

import java.util.Locale;
import java.util.Map;

/** Reads Nexus migration metadata without changing protocol-specific checksum requirements. */
public final class MigrationSourceMetadata {
  private MigrationSourceMetadata() { }

  /** Aliases are tried in order; a present but invalid value does not fall through to another alias. */
  public static String checksum(Object metadata, int hexLength, String... aliases) {
    Object found = null;
    for (String alias : aliases) {
      found = findKey(metadata, alias);
      if (found != null) break;
    }
    String text = found == null ? null : found.toString().trim().toLowerCase(Locale.ROOT);
    return text != null && text.length() == hexLength && text.matches("[0-9a-f]+") ? text : null;
  }

  private static Object findKey(Object value, String wanted) {
    if (value instanceof Map<?, ?> map) {
      String normalizedWanted = normalizedKey(wanted);
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() != null && normalizedKey(entry.getKey()).equals(normalizedWanted)) {
          return entry.getValue();
        }
      }
      for (Object child : map.values()) {
        Object found = findKey(child, wanted);
        if (found != null) return found;
      }
    } else if (value instanceof Iterable<?> iterable) {
      for (Object child : iterable) {
        Object found = findKey(child, wanted);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static String normalizedKey(Object value) {
    return String.valueOf(value).replaceAll("[^A-Za-z0-9]", "")
        .toLowerCase(Locale.ROOT);
  }

}
