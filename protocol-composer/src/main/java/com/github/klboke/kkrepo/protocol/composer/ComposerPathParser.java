package com.github.klboke.kkrepo.protocol.composer;

import java.nio.charset.StandardCharsets;

/** Strict parser for Composer v2 repository paths. */
public final class ComposerPathParser {
  public ComposerPath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    String path = normalize(percentDecode(raw));
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

  private static String percentDecode(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    byte[] decoded = new byte[bytes.length];
    int out = 0;
    for (int i = 0; i < bytes.length; i++) {
      byte b = bytes[i];
      if (b == '%' && i + 2 < bytes.length) {
        int hi = hex(bytes[i + 1]);
        int lo = hex(bytes[i + 2]);
        if (hi >= 0 && lo >= 0) {
          decoded[out++] = (byte) ((hi << 4) + lo);
          i += 2;
          continue;
        }
      }
      decoded[out++] = b;
    }
    return new String(decoded, 0, out, StandardCharsets.UTF_8);
  }

  private static int hex(byte value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
  }
}
