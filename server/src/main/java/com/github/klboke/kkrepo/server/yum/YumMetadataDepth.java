package com.github.klboke.kkrepo.server.yum;

import java.util.Map;

/** Nexus-compatible metadata roots; configuration is part of the shared repository catalog. */
public final class YumMetadataDepth {
  private YumMetadataDepth() {
  }

  public static int read(Map<String, Object> attributes) {
    Object value = nested(attributes, "yum", "repodataDepth");
    // Older migrations retained the source configuration without activating its Yum settings.
    if (value == null) value = nested(attributes, "sourceRepository", "yum", "repodataDepth");
    if (value == null) value = nested(attributes, "sourceRepository", "attributes", "yum", "repodataDepth");
    int depth = value == null ? 0 : Integer.parseInt(value.toString());
    if (depth < 0) throw new IllegalArgumentException("Yum repodata depth must be non-negative");
    return depth;
  }

  private static Object nested(Object value, String... keys) {
    for (String key : keys) {
      if (!(value instanceof Map<?, ?> map)) return null;
      value = map.get(key);
    }
    return value;
  }

  /** Returns a trailing-slash root, or null for a package above the configured depth. */
  static String root(String path, int depth) {
    int end = -1;
    for (int i = 0; i < depth; i++) {
      end = path.indexOf('/', end + 1);
      if (end < 0) return null;
    }
    return path.substring(0, end + 1);
  }

  static String metadataRoot(String path) {
    if (path.startsWith("repodata/")) return "";
    int marker = path.lastIndexOf("/repodata/");
    return marker < 0 ? null : path.substring(0, marker + 1);
  }
}
