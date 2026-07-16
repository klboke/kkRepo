package com.github.klboke.kkrepo.protocol.swift;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versioned Package.swift filename and swift-tools-version helpers. */
public final class SwiftToolsVersions {
  private static final Pattern VERSION = Pattern.compile("^\\d+(?:\\.\\d+){0,2}$");
  private static final Pattern MANIFEST_FILENAME = Pattern.compile(
      "^Package@swift-(\\d+(?:\\.\\d+){0,2})\\.swift$");
  private static final Pattern TOOLS_VERSION_LINE = Pattern.compile(
      "^\\uFEFF?//\\s*swift-tools-version\\s*:\\s*(\\d+(?:\\.\\d+){1,2})\\s*$");

  private SwiftToolsVersions() {
  }

  public static String require(String value) {
    if (value == null || value.length() > 32 || !VERSION.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid Swift tools version: " + value);
    }
    return value;
  }

  public static boolean isValid(String value) {
    try {
      require(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public static String manifestFilename(String toolsVersion) {
    return "Package@swift-" + require(toolsVersion) + ".swift";
  }

  public static Optional<String> fromManifestFilename(String filename) {
    if (filename == null) return Optional.empty();
    Matcher matcher = MANIFEST_FILENAME.matcher(filename);
    return matcher.matches() ? Optional.of(require(matcher.group(1))) : Optional.empty();
  }

  /** Reads only the first line and never evaluates manifest source. */
  public static Optional<String> fromManifest(String manifest) {
    if (manifest == null || manifest.isEmpty()) return Optional.empty();
    int lineEnd = manifest.indexOf('\n');
    String firstLine = lineEnd < 0 ? manifest : manifest.substring(0, lineEnd);
    if (firstLine.endsWith("\r")) {
      firstLine = firstLine.substring(0, firstLine.length() - 1);
    }
    Matcher matcher = TOOLS_VERSION_LINE.matcher(firstLine);
    return matcher.matches() ? Optional.of(require(matcher.group(1))) : Optional.empty();
  }
}
