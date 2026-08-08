package com.github.klboke.kkrepo.server.conda;

import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.protocol.conda.CondaPathParser;
import java.util.Arrays;
import java.util.Optional;

/** Maps Nexus-style logical Conda Browse paths back to client-facing package paths. */
public final class CondaBrowsePaths {
  private static final CondaPathParser PATHS = new CondaPathParser();

  private CondaBrowsePaths() {
  }

  public static Optional<CondaPath> packagePath(String publicOrStoragePath) {
    String normalized = normalize(publicOrStoragePath);
    String[] segments = normalized.isEmpty() ? new String[0] : normalized.split("/", -1);
    Optional<CondaPath> nexus = projectedPackagePath(segments, false);
    if (nexus.isPresent()) return nexus;
    // Accept the pre-alignment build-directory shape so existing links and administrative
    // delete requests remain resolvable while a node is rolling forward.
    Optional<CondaPath> legacy = projectedPackagePath(segments, true);
    if (legacy.isPresent()) return legacy;
    CondaPath direct = PATHS.parse(normalized);
    return direct.packageFile() ? Optional.of(direct) : Optional.empty();
  }

  public static String toStoragePath(String publicOrStoragePath) {
    return packagePath(publicOrStoragePath)
        .map(CondaPath::canonicalPath)
        .orElseGet(() -> normalize(publicOrStoragePath));
  }

  private static Optional<CondaPath> projectedPackagePath(
      String[] segments, boolean includesBuildDirectory) {
    int trailingSegments = includesBuildDirectory ? 5 : 4;
    if (segments.length < trailingSegments) return Optional.empty();
    int subdirIndex = segments.length - trailingSegments;
    String name = segments[subdirIndex + 1];
    String version = segments[subdirIndex + 2];
    String filename = segments[segments.length - 1];
    String suffix = filename.endsWith(".tar.bz2") ? ".tar.bz2"
        : filename.endsWith(".conda") ? ".conda" : null;
    if (suffix == null) return Optional.empty();
    String expectedPrefix = name + "-" + version + "-";
    if (!filename.startsWith(expectedPrefix)
        || filename.length() <= expectedPrefix.length() + suffix.length()) {
      return Optional.empty();
    }
    if (includesBuildDirectory) {
      String build = segments[subdirIndex + 3];
      if (!filename.equals(expectedPrefix + build + suffix)) {
        return Optional.empty();
      }
    }
    String prefix = String.join("/", Arrays.copyOfRange(segments, 0, subdirIndex + 1));
    CondaPath parsed = PATHS.parse(prefix + "/" + filename);
    return parsed.packageFile() ? Optional.of(parsed) : Optional.empty();
  }

  private static String normalize(String path) {
    if (path == null) return "";
    String value = path.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }
}
