package com.github.klboke.kkrepo.protocol.composer;

import java.util.Locale;

/** Composer 2 package identity ({@code vendor/package}) validation. */
public final class ComposerPackageName {
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
    if (value == null || value.length() > MAX_LENGTH) return false;
    int slash = value.indexOf('/');
    if (slash <= 0 || slash != value.lastIndexOf('/') || slash == value.length() - 1) return false;
    return isValidSegment(value, 0, slash, false)
        && isValidSegment(value, slash + 1, value.length(), true);
  }

  private static boolean isValidSegment(String value, int start, int end, boolean allowDoubleHyphen) {
    int index = start;
    if (index >= end || !isLowerAlphanumeric(value.charAt(index++))) return false;
    while (index < end) {
      char current = value.charAt(index);
      if (isLowerAlphanumeric(current)) {
        index++;
        continue;
      }
      if (current != '.' && current != '_' && current != '-') return false;
      index++;
      if (current == '-' && allowDoubleHyphen && index < end && value.charAt(index) == '-') {
        index++;
      }
      if (index >= end || !isLowerAlphanumeric(value.charAt(index))) return false;
    }
    return true;
  }

  private static boolean isLowerAlphanumeric(char value) {
    return (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9');
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
