package com.github.klboke.kkrepo.protocol.composer;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;

/** Strict parser for Composer v2 repository paths. */
public final class ComposerPathParser {
  public ComposerPath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    try {
      return parseCanonical(raw, canonicalize(raw));
    } catch (IllegalArgumentException e) {
      return path(ComposerPath.Kind.UNKNOWN, raw);
    }
  }

  /** Parses a path that has already been percent-decoded and normalized exactly once. */
  public ComposerPath parseCanonical(String canonicalPath) {
    String path = canonicalPath == null ? "" : canonicalPath;
    return parseCanonical(path, path);
  }

  public String canonicalize(String rawPath) {
    return normalize(UriPathDecoder.decodePath(rawPath == null ? "" : rawPath));
  }

  private static ComposerPath parseCanonical(String raw, String path) {
    if (path.isEmpty()) return path(ComposerPath.Kind.ROOT, raw);
    if (path.equals("packages.json")) return path(ComposerPath.Kind.PACKAGES, raw);
    if (path.equals("packages/list.json")) return path(ComposerPath.Kind.PACKAGE_LIST, raw);
    if (path.startsWith("p2/")) return parsePackage(raw, path.substring(3));
    if (path.startsWith("providers/")) return parseProvider(raw, path.substring("providers/".length()));
    ComposerPath nexusDist = parseNexusDist(raw, path);
    if (nexusDist.kind() == ComposerPath.Kind.DIST) return nexusDist;
    return path(ComposerPath.Kind.UNKNOWN, raw);
  }

  private static ComposerPath parsePackage(String raw, String suffix) {
    if (!suffix.endsWith(".json")) return path(ComposerPath.Kind.UNKNOWN, raw);
    String value = suffix.substring(0, suffix.length() - 5);
    boolean dev = value.endsWith("~dev");
    if (dev) value = value.substring(0, value.length() - 4);
    String name = ComposerPackageName.normalize(value);
    if (!ComposerPackageName.isValid(name) || !name.equals(value)) {
      return path(ComposerPath.Kind.UNKNOWN, raw);
    }
    return new ComposerPath(ComposerPath.Kind.PACKAGE_METADATA, raw, name, dev, null, null);
  }

  private static ComposerPath parseProvider(String raw, String suffix) {
    if (!suffix.endsWith(".json")) return path(ComposerPath.Kind.UNKNOWN, raw);
    String value = suffix.substring(0, suffix.length() - 5);
    String name = ComposerPackageName.normalize(value);
    if (!ComposerPackageName.isValid(name) || !name.equals(value)) {
      return path(ComposerPath.Kind.UNKNOWN, raw);
    }
    return new ComposerPath(ComposerPath.Kind.PROVIDERS, raw, name, false, null, null);
  }

  private static ComposerPath parseNexusDist(String raw, String path) {
    String[] parts = path.split("/", -1);
    if (parts.length != 4 || !validFile(parts[3]) || !validPathSegment(parts[2])) {
      return path(ComposerPath.Kind.UNKNOWN, raw);
    }
    String packageName = parts[0] + "/" + parts[1];
    if (!ComposerPackageName.isValid(packageName) || !packageName.equals(ComposerPackageName.normalize(packageName))) {
      return path(ComposerPath.Kind.UNKNOWN, raw);
    }
    return new ComposerPath(ComposerPath.Kind.DIST, raw, packageName, false, parts[2], parts[3]);
  }

  private static boolean validPathSegment(String value) {
    return value != null && !value.isBlank() && value.length() <= 255
        && !value.equals(".") && !value.equals("..")
        && value.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-');
  }

  private static boolean validFile(String value) {
    return value != null && !value.isBlank() && value.length() <= 255
        && !value.equals(".") && !value.equals("..")
        && value.chars().allMatch(ch -> ch >= 0x20 && ch != '/' && ch != '\\');
  }

  private static ComposerPath path(ComposerPath.Kind kind, String raw) {
    return new ComposerPath(kind, raw, null, false, null, null);
  }

  private static String normalize(String value) {
    String path = value == null ? "" : value.trim();
    while (path.startsWith("/")) path = path.substring(1);
    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    return path;
  }

}
