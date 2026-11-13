package com.github.klboke.kkrepo.plugin.api;

import java.util.Locale;
import java.util.Objects;

/** Declarative route registered by a plugin repository handler. */
public record RepositoryProtocolRoute(
    FormatId format,
    RepositoryKind type,
    String method,
    String pathPattern,
    int order) {
  public static final String ANY_PATH = "**";

  public RepositoryProtocolRoute {
    Objects.requireNonNull(format, "format");
    Objects.requireNonNull(type, "type");
    if (method == null || method.isBlank()) {
      throw new IllegalArgumentException("method is required");
    }
    method = method.trim().toUpperCase(Locale.ROOT);
    Objects.requireNonNull(pathPattern, "pathPattern");
    pathPattern = normalizePattern(pathPattern);
  }

  public static RepositoryProtocolRoute anyPath(
      FormatId format, RepositoryKind type, String method, int order) {
    return new RepositoryProtocolRoute(format, type, method, ANY_PATH, order);
  }

  public static RepositoryProtocolRoute exactPath(
      FormatId format, RepositoryKind type, String method, String path, int order) {
    return new RepositoryProtocolRoute(format, type, method, path, order);
  }

  public boolean matches(
      FormatId requestFormat, RepositoryKind requestType, String requestMethod, String requestPath) {
    return format.equals(requestFormat)
        && type == requestType
        && method.equalsIgnoreCase(requestMethod)
        && matchesPath(RepositoryRequest.normalizePath(requestPath));
  }

  public int pathSpecificity() {
    if (ANY_PATH.equals(pathPattern)) return 0;
    if (pathPattern.endsWith("/**")) return 1_000 + pathPattern.length();
    return 1_000_000 + pathPattern.length();
  }

  private boolean matchesPath(String path) {
    if (ANY_PATH.equals(pathPattern)) return true;
    if (pathPattern.endsWith("/**")) {
      String prefix = pathPattern.substring(0, pathPattern.length() - 3);
      return path.equals(prefix) || path.startsWith(prefix + "/");
    }
    return pathPattern.equals(path);
  }

  private static String normalizePattern(String pattern) {
    String normalized = RepositoryRequest.normalizePath(pattern);
    int wildcard = normalized.indexOf('*');
    boolean trailingPrefix = normalized.endsWith("/**")
        && wildcard == normalized.length() - 2;
    if (wildcard >= 0 && !ANY_PATH.equals(normalized) && !trailingPrefix) {
      throw new IllegalArgumentException(
          "Repository route wildcard is only supported as ** or a trailing /**");
    }
    return normalized;
  }
}
