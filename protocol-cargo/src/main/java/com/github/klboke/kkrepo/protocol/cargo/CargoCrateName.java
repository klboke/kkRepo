package com.github.klboke.kkrepo.protocol.cargo;

import java.util.Locale;
import java.util.regex.Pattern;

public record CargoCrateName(String value) {
  private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

  public CargoCrateName {
    value = normalize(value);
    if (value == null || !ALLOWED.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid Cargo crate name: " + value);
    }
  }

  public String lower() {
    return value.toLowerCase(Locale.ROOT);
  }

  public String lowerDashUnderscoreKey() {
    return lower().replace('-', '_');
  }

  public static CargoCrateName parse(String value) {
    return new CargoCrateName(value);
  }

  public static boolean isValid(String value) {
    try {
      parse(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
