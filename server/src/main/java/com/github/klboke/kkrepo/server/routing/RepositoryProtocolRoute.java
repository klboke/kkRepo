package com.github.klboke.kkrepo.server.routing;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.Objects;
import org.springframework.http.HttpMethod;

/**
 * Declarative route registration for one repository protocol handler.
 *
 * <p>Routes are matched by repository format, repository type, HTTP method, and normalized
 * repository-relative path. The path pattern is either {@value #ANY_PATH}, an exact path, or a
 * prefix ending in {@code /**}. Lower order values win after path specificity is considered.
 */
public record RepositoryProtocolRoute(
    RepositoryFormat format,
    RepositoryType type,
    HttpMethod method,
    String pathPattern,
    int order) {
  public static final String ANY_PATH = "**";

  public RepositoryProtocolRoute {
    Objects.requireNonNull(format, "format");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(pathPattern, "pathPattern");
    pathPattern = normalizePattern(pathPattern);
  }

  public static RepositoryProtocolRoute anyPath(
      RepositoryFormat format, RepositoryType type, HttpMethod method, int order) {
    return new RepositoryProtocolRoute(format, type, method, ANY_PATH, order);
  }

  public static RepositoryProtocolRoute exactPath(
      RepositoryFormat format,
      RepositoryType type,
      HttpMethod method,
      String path,
      int order) {
    return new RepositoryProtocolRoute(format, type, method, path, order);
  }

  boolean matches(RepositoryRuntime runtime, HttpMethod requestMethod, String path) {
    return format == runtime.format()
        && type == runtime.type()
        && method == requestMethod
        && matchesPath(normalizePath(path));
  }

  int pathSpecificity() {
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
    String normalized = normalizePath(pattern);
    int wildcard = normalized.indexOf('*');
    boolean trailingPrefix = normalized.endsWith("/**")
        && wildcard == normalized.length() - 2;
    if (wildcard >= 0 && !ANY_PATH.equals(normalized) && !trailingPrefix) {
      throw new IllegalArgumentException(
          "Repository route wildcard is only supported as ** or a trailing /**");
    }
    return normalized;
  }

  static String normalizePath(String path) {
    if (path == null) return "";
    String normalized = path.trim();
    while (normalized.startsWith("/")) normalized = normalized.substring(1);
    return normalized;
  }
}
