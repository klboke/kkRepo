package com.github.klboke.kkrepo.protocol.composer;

import java.util.Locale;
import java.util.regex.Pattern;

/** Composer 2 package identity ({@code vendor/package}) validation. */
public final class ComposerPackageName {
  private static final Pattern VALID = Pattern.compile(
      "^[a-z0-9]([_.-]?[a-z0-9]+)*/[a-z0-9](([_.]|-{1,2})?[a-z0-9]+)*$");
  private static final int MAX_LENGTH = 255;

  private ComposerPackageName() {
  }

  public static String require(String value) {
    String normalized = normalize(value);
    if (!isValid(normalized)) {
      throw new IllegalArgumentException("Invalid Composer package name: " + value);
    }
    return normalized;
  }

  public static boolean isValid(String value) {
    return value != null && value.length() <= MAX_LENGTH && VALID.matcher(value).matches();
  }

  public static String normalize(String value) {
    if (value == null) return null;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.isEmpty() ? null : normalized;
  }

  public static String vendor(String packageName) {
    String value = require(packageName);
    return value.substring(0, value.indexOf('/'));
  }

  public static String project(String packageName) {
    String value = require(packageName);
    return value.substring(value.indexOf('/') + 1);
  }
}
