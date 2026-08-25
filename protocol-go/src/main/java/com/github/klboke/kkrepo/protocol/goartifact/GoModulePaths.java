package com.github.klboke.kkrepo.protocol.goartifact;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Validation and proxy-path escaping from the Go module path specification. */
public final class GoModulePaths {
  private static final int MAX_PATH_LENGTH = 1024;
  private static final String ALLOWED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~/";
  private static final Set<String> WINDOWS_RESERVED = Set.of(
      "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7",
      "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
  private static final Pattern WINDOWS_SHORT_NAME = Pattern.compile(".*~[0-9]+$");

  private GoModulePaths() {
  }

  public static String require(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_PATH_LENGTH
        || value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
      throw new IllegalArgumentException("Invalid Go module path: " + value);
    }
    for (int i = 0; i < value.length(); i++) {
      if (ALLOWED.indexOf(value.charAt(i)) < 0) {
        throw new IllegalArgumentException("Invalid Go module path: " + value);
      }
    }
    for (String segment : value.split("/", -1)) {
      requireSegment(segment, value);
    }
    requireFirstSegment(value);
    requirePathMajorShape(value);
    return value;
  }

  public static String escape(String module) {
    String value = require(module);
    StringBuilder escaped = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch >= 'A' && ch <= 'Z') {
        escaped.append('!').append(Character.toLowerCase(ch));
      } else {
        escaped.append(ch);
      }
    }
    return escaped.toString();
  }

  public static String unescape(String escapedModule) {
    if (escapedModule == null || escapedModule.isBlank()) {
      throw new IllegalArgumentException("Invalid escaped Go module path: " + escapedModule);
    }
    StringBuilder decoded = new StringBuilder(escapedModule.length());
    for (int i = 0; i < escapedModule.length(); i++) {
      char ch = escapedModule.charAt(i);
      if (ch == '!') {
        if (++i >= escapedModule.length()) {
          throw new IllegalArgumentException("Invalid escaped Go module path: " + escapedModule);
        }
        char lower = escapedModule.charAt(i);
        if (lower < 'a' || lower > 'z') {
          throw new IllegalArgumentException("Invalid escaped Go module path: " + escapedModule);
        }
        decoded.append(Character.toUpperCase(lower));
      } else {
        if (ch >= 'A' && ch <= 'Z') {
          throw new IllegalArgumentException("Invalid escaped Go module path: " + escapedModule);
        }
        decoded.append(ch);
      }
    }
    String module = require(decoded.toString());
    if (!escape(module).equals(escapedModule)) {
      throw new IllegalArgumentException("Non-canonical escaped Go module path: " + escapedModule);
    }
    return module;
  }

  public static void requireVersionSuffix(String module, String version) {
    String path = require(module);
    String canonicalVersion = GoVersions.requireCanonical(version);
    int major = GoVersions.major(canonicalVersion);
    Integer suffixMajor = suffixMajor(path);
    if (path.startsWith("gopkg.in/")) {
      boolean legacyV1Pseudo = suffixMajor != null
          && suffixMajor == 1
          && major == 0
          && canonicalVersion.startsWith("v0.0.0-")
          && GoVersions.isPseudoVersion(canonicalVersion);
      if (suffixMajor == null || suffixMajor != major && !legacyV1Pseudo) {
        throw new IllegalArgumentException(
            "Go module path major suffix does not match version: " + path + " " + version);
      }
      return;
    }
    if (major >= 2 && !canonicalVersion.endsWith("+incompatible")) {
      if (suffixMajor == null || suffixMajor != major) {
        throw new IllegalArgumentException(
            "Go module path " + path + " must end in /v" + major + " for version " + version);
      }
    } else if (suffixMajor != null && suffixMajor >= 2 && suffixMajor != major) {
      throw new IllegalArgumentException(
          "Go module path major suffix does not match version: " + path + " " + version);
    }
  }

  private static Integer suffixMajor(String module) {
    String leaf = module.substring(module.lastIndexOf('/') + 1);
    if (module.startsWith("gopkg.in/") && leaf.endsWith("-unstable")) {
      leaf = leaf.substring(0, leaf.length() - "-unstable".length());
    }
    String candidate = leaf.startsWith("v") ? leaf.substring(1) : null;
    if (candidate == null && module.startsWith("gopkg.in/") && leaf.contains(".v")) {
      candidate = leaf.substring(leaf.lastIndexOf(".v") + 2);
    }
    if (candidate == null || candidate.isEmpty() || !candidate.chars().allMatch(Character::isDigit)) {
      return null;
    }
    try {
      return Integer.parseInt(candidate);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static void requireSegment(String segment, String fullPath) {
    if (segment.isEmpty() || segment.chars().allMatch(ch -> ch == '.')
        || segment.startsWith(".") || segment.endsWith(".")) {
      throw new IllegalArgumentException("Invalid Go module path: " + fullPath);
    }
    String base = segment;
    int dot = base.indexOf('.');
    if (dot >= 0) {
      base = base.substring(0, dot);
    }
    if (WINDOWS_RESERVED.contains(base.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Invalid Go module path: " + fullPath);
    }
    if (WINDOWS_SHORT_NAME.matcher(base).matches()) {
      throw new IllegalArgumentException("Invalid Go module path: " + fullPath);
    }
  }

  private static void requireFirstSegment(String value) {
    String first = value.substring(0, value.indexOf('/') < 0 ? value.length() : value.indexOf('/'));
    if (!first.contains(".") || first.startsWith("-")) {
      throw new IllegalArgumentException("Invalid Go module path: " + value);
    }
    for (int i = 0; i < first.length(); i++) {
      char ch = first.charAt(i);
      if (!(ch == '-' || ch == '.' || ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'z')) {
        throw new IllegalArgumentException("Invalid Go module path: " + value);
      }
    }
  }

  private static void requirePathMajorShape(String value) {
    String leaf = value.substring(value.lastIndexOf('/') + 1);
    if (value.startsWith("gopkg.in/")) {
      String stableLeaf = leaf.endsWith("-unstable")
          ? leaf.substring(0, leaf.length() - "-unstable".length())
          : leaf;
      int marker = stableLeaf.lastIndexOf(".v");
      if (marker < 1 || marker + 2 >= stableLeaf.length()) {
        throw new IllegalArgumentException("Invalid Go module path: " + value);
      }
      String major = stableLeaf.substring(marker + 2);
      if (!major.chars().allMatch(Character::isDigit)
          || major.length() > 1 && major.startsWith("0")) {
        throw new IllegalArgumentException("Invalid Go module path: " + value);
      }
      return;
    }
    if (!leaf.startsWith("v") || leaf.length() == 1) return;
    String candidate = leaf.substring(1);
    if (!candidate.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.')) return;
    if (!candidate.chars().allMatch(Character::isDigit)
        || candidate.startsWith("0")
        || "1".equals(candidate)) {
      throw new IllegalArgumentException("Invalid Go module path: " + value);
    }
  }
}
