package com.github.klboke.kkrepo.server.pypi;

import java.util.ArrayList;
import java.util.List;

/** Normalizes the Nexus-compatible PyPI proxy Remote Index Path setting. */
public final class PypiRemoteIndexPath {
  public static final String DEFAULT = "/simple";

  private PypiRemoteIndexPath() {
  }

  /**
   * Returns a canonical absolute-looking path, or an empty string for a root index.
   *
   * <p>The value is still resolved below the configured proxy remote URL. It is not a URL and
   * cannot change the upstream origin.
   */
  public static String normalize(String configured) {
    if (configured == null) return DEFAULT;
    String value = configured.trim();
    if (value.isEmpty() || "/".equals(value)) return "";
    if (value.indexOf('?') >= 0 || value.indexOf('#') >= 0 || value.indexOf('\\') >= 0) {
      throw invalid();
    }

    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == '/') start++;
    while (end > start && value.charAt(end - 1) == '/') end--;
    if (start == end) return "";

    List<String> segments = new ArrayList<>();
    for (String segment : value.substring(start, end).split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw invalid();
      }
      for (int i = 0; i < segment.length(); i++) {
        if (Character.isISOControl(segment.charAt(i))) throw invalid();
      }
      segments.add(segment);
    }
    return "/" + String.join("/", segments);
  }

  /** Builds the relative upstream index path without changing the client-facing /simple path. */
  static String upstreamPath(String configured, String normalizedProjectName) {
    String indexPath = normalize(configured);
    String relative = indexPath.isEmpty() ? "" : indexPath.substring(1);
    if (normalizedProjectName != null && !normalizedProjectName.isBlank()) {
      if (!relative.isEmpty()) relative += "/";
      relative += normalizedProjectName;
    }
    return relative.isEmpty() ? "" : relative + "/";
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "pypi.indexPath must be an empty value or a path such as /simple");
  }
}
