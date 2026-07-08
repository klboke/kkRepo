package com.github.klboke.kkrepo.protocol.pub;

import java.util.Locale;
import java.util.regex.Pattern;

public final class PubPackageName {
  private static final Pattern VALID = Pattern.compile("^[_a-z][_a-z0-9]*$");
  private static final int MAX_LENGTH = 64;

  private PubPackageName() {
  }

  public static String require(String value) {
    String normalized = normalize(value);
    if (normalized == null || !isValid(normalized)) {
      throw new IllegalArgumentException("Invalid Pub package name: " + value);
    }
    return normalized;
  }

  public static boolean isValid(String value) {
    return value != null
        && value.length() <= MAX_LENGTH
        && VALID.matcher(value).matches()
        && !value.contains("__");
  }

  public static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.isBlank() ? null : normalized;
  }
}
