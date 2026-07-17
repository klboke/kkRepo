package com.github.klboke.kkrepo.protocol.swift;

import java.util.Locale;
import java.util.regex.Pattern;

/** Validation and case-insensitive identity handling for Swift registry scopes. */
public final class SwiftScope {
  private static final Pattern VALID = Pattern.compile(
      "^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$");

  private SwiftScope() {
  }

  public static String require(String value) {
    if (!isValid(value)) {
      throw new IllegalArgumentException("Invalid Swift package scope: " + value);
    }
    return value;
  }

  public static boolean isValid(String value) {
    return value != null && VALID.matcher(value).matches();
  }

  public static String key(String value) {
    return require(value).toLowerCase(Locale.ROOT);
  }
}
