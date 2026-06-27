package com.github.klboke.kkrepo.protocol.cargo;

public final class CargoVersions {
  private CargoVersions() {
  }

  public static String uniquenessKey(String version) {
    if (version == null) {
      return null;
    }
    String trimmed = version.trim();
    int build = trimmed.indexOf('+');
    return build < 0 ? trimmed : trimmed.substring(0, build);
  }

  public static String requireVersion(String version) {
    String value = version == null ? "" : version.trim();
    if (value.isBlank() || value.contains("/") || value.contains("\\") || value.contains("..")) {
      throw new IllegalArgumentException("Invalid Cargo crate version: " + version);
    }
    return value;
  }
}
