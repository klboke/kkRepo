package com.github.klboke.kkrepo.protocol.pub;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PubVersions {
  private static final Pattern VERSION = Pattern.compile(
      "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
          + "(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$");

  public static final Comparator<String> COMPARATOR = PubVersions::compare;

  private PubVersions() {
  }

  public static String require(String value) {
    String normalized = normalize(value);
    if (normalized == null || !VERSION.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Invalid Pub version: " + value);
    }
    return normalized;
  }

  public static boolean isValid(String value) {
    try {
      require(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isBlank() ? null : normalized;
  }

  public static int compare(String left, String right) {
    Parsed a = Parsed.parse(left);
    Parsed b = Parsed.parse(right);
    int major = a.major.compareTo(b.major);
    if (major != 0) return major;
    int minor = a.minor.compareTo(b.minor);
    if (minor != 0) return minor;
    int patch = a.patch.compareTo(b.patch);
    if (patch != 0) return patch;
    return comparePrerelease(a.prerelease, b.prerelease);
  }

  private static int comparePrerelease(String left, String right) {
    if (left == null && right == null) return 0;
    if (left == null) return 1;
    if (right == null) return -1;
    String[] a = left.split("\\.");
    String[] b = right.split("\\.");
    int max = Math.max(a.length, b.length);
    for (int i = 0; i < max; i++) {
      if (i >= a.length) return -1;
      if (i >= b.length) return 1;
      int compared = comparePrereleasePart(a[i], b[i]);
      if (compared != 0) return compared;
    }
    return 0;
  }

  private static int comparePrereleasePart(String left, String right) {
    boolean leftNumeric = left.chars().allMatch(Character::isDigit);
    boolean rightNumeric = right.chars().allMatch(Character::isDigit);
    if (leftNumeric && rightNumeric) {
      return new BigInteger(left).compareTo(new BigInteger(right));
    }
    if (leftNumeric) return -1;
    if (rightNumeric) return 1;
    return left.compareTo(right);
  }

  private record Parsed(BigInteger major, BigInteger minor, BigInteger patch, String prerelease) {
    static Parsed parse(String value) {
      Matcher matcher = VERSION.matcher(require(value));
      if (!matcher.matches()) {
        throw new IllegalArgumentException("Invalid Pub version: " + value);
      }
      return new Parsed(
          new BigInteger(matcher.group(1)),
          new BigInteger(matcher.group(2)),
          new BigInteger(matcher.group(3)),
          matcher.group(4));
    }
  }
}
