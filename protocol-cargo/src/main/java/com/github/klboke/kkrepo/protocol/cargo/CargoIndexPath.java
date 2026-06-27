package com.github.klboke.kkrepo.protocol.cargo;

public final class CargoIndexPath {
  public static final String CONFIG = "config.json";

  private CargoIndexPath() {
  }

  public static String forCrate(String crateName) {
    String lower = CargoCrateName.parse(crateName).lower();
    int len = lower.length();
    if (len == 1) {
      return "1/" + lower;
    }
    if (len == 2) {
      return "2/" + lower;
    }
    if (len == 3) {
      return "3/" + lower.charAt(0) + "/" + lower;
    }
    return lower.substring(0, 2) + "/" + lower.substring(2, 4) + "/" + lower;
  }

  public static String prefixForCrate(String crateName, boolean lowerCase) {
    String value = CargoCrateName.parse(crateName).value();
    if (lowerCase) {
      value = value.toLowerCase(java.util.Locale.ROOT);
    }
    int len = value.length();
    if (len == 1) {
      return "1";
    }
    if (len == 2) {
      return "2";
    }
    if (len == 3) {
      return "3/" + value.charAt(0);
    }
    return value.substring(0, 2) + "/" + value.substring(2, 4);
  }

  public static boolean matchesCrate(String path, String crateName) {
    return forCrate(crateName).equals(normalize(path));
  }

  static String normalize(String path) {
    String normalized = path == null ? "" : path.trim().replaceAll("/+", "/");
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }
}
