package com.github.klboke.kkrepo.protocol.composer;

public final class ComposerPaths {
  public static final String PACKAGES = "packages.json";

  private ComposerPaths() {
  }

  public static String metadata(String packageName, boolean dev) {
    return "p2/" + ComposerPackageName.require(packageName) + (dev ? "~dev" : "") + ".json";
  }

  public static String provider(String packageName) {
    return "providers/" + ComposerPackageName.require(packageName) + ".json";
  }

  public static String componentDist(String packageName, String version, String type) {
    String name = ComposerPackageName.require(packageName);
    String safeVersion = safeSegment(version, "dist");
    String extension = safeSegment(type, "zip").toLowerCase(java.util.Locale.ROOT);
    String fileName = name.replace('/', '-') + "-" + safeVersion + "." + extension;
    return name + "/" + safeVersion + "/" + fileName;
  }

  private static String safeSegment(String value, String fallback) {
    if (value == null || value.isBlank()) return fallback;
    String safe = value.replaceAll("[^A-Za-z0-9._-]", "-");
    return safe.isBlank() || safe.equals(".") || safe.equals("..") ? fallback : safe;
  }
}
