package com.github.klboke.kkrepo.protocol.ansible;

import java.util.Locale;
import java.util.regex.Pattern;

/** Official collection namespace/name validation used by galaxy.yml and MANIFEST.json. */
public final class AnsibleGalaxyNames {
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

  private AnsibleGalaxyNames() {
  }

  public static String requireNamespace(String value) {
    return require(value, "namespace");
  }

  public static String requireCollection(String value) {
    return require(value, "collection name");
  }

  public static boolean isValidNamespace(String value) {
    return isValid(value);
  }

  public static boolean isValidCollection(String value) {
    return isValid(value);
  }

  public static String key(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }

  private static String require(String value, String label) {
    if (!isValid(value)) {
      throw new IllegalArgumentException("Invalid Ansible collection " + label + ": " + value);
    }
    return value;
  }

  private static boolean isValid(String value) {
    return value != null && NAME.matcher(value).matches() && !value.contains("__");
  }
}
