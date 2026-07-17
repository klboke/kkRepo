package com.github.klboke.kkrepo.protocol.swift;

import java.util.Locale;
import java.util.regex.Pattern;

/** Validation and case-insensitive identity handling for Swift registry package names. */
public final class SwiftPackageName {
  private static final Pattern VALID = Pattern.compile(
      "^[A-Za-z0-9](?:[A-Za-z0-9]|[-_](?=[A-Za-z0-9])){0,99}$");

  private SwiftPackageName() {
  }

  public static String require(String value) {
    if (!isValid(value)) {
      throw new IllegalArgumentException("Invalid Swift package name: " + value);
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
