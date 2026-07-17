package com.github.klboke.kkrepo.protocol.swift;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SemVer 2.0 validation and precedence ordering used by the registry API. */
public final class SwiftVersions {
  private static final String NUMERIC_IDENTIFIER = "0|[1-9]\\d*";
  private static final String PRERELEASE_IDENTIFIER =
      "(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)";
  private static final Pattern SEMVER = Pattern.compile(
      "^(" + NUMERIC_IDENTIFIER + ")\\.(" + NUMERIC_IDENTIFIER + ")\\.("
          + NUMERIC_IDENTIFIER + ")"
          + "(?:-(" + PRERELEASE_IDENTIFIER + "(?:\\." + PRERELEASE_IDENTIFIER + ")*))?"
          + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

  public static final Comparator<String> COMPARATOR = SwiftVersions::compare;
  public static final Comparator<String> DESCENDING_COMPARATOR = (left, right) -> {
    int precedence = compare(right, left);
    return precedence != 0 ? precedence : left.compareTo(right);
  };

  private SwiftVersions() {
  }

  public static String require(String value) {
    if (value == null || !SEMVER.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid Swift package version: " + value);
    }
    return value;
  }

  public static boolean isValid(String value) {
    try {
      require(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** Removes exactly one Nexus-compatible v/V tag prefix, then requires a valid SemVer. */
  public static String normalizeGitTag(String tag) {
    if (tag == null || tag.isEmpty()) {
      throw new IllegalArgumentException("Invalid Swift Git tag: " + tag);
    }
    String candidate = tag.charAt(0) == 'v' || tag.charAt(0) == 'V'
        ? tag.substring(1)
        : tag;
    try {
      return require(candidate);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid Swift Git tag: " + tag, e);
    }
  }

  public static int compare(String left, String right) {
    Parsed a = Parsed.parse(left);
    Parsed b = Parsed.parse(right);
    int core = a.compareCore(b);
    return core != 0 ? core : comparePrerelease(a.prerelease(), b.prerelease());
  }

  public static List<String> sortDescending(Collection<String> versions) {
    if (versions == null) {
      throw new IllegalArgumentException("Swift versions must not be null");
    }
    return versions.stream().map(SwiftVersions::require).sorted(DESCENDING_COMPARATOR).toList();
  }

  public static boolean isPrerelease(String version) {
    return Parsed.parse(version).prerelease() != null;
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
      int compared = comparePrereleaseIdentifier(a[i], b[i]);
      if (compared != 0) return compared;
    }
    return 0;
  }

  private static int comparePrereleaseIdentifier(String left, String right) {
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
    private static Parsed parse(String value) {
      Matcher matcher = SEMVER.matcher(require(value));
      if (!matcher.matches()) {
        throw new IllegalArgumentException("Invalid Swift package version: " + value);
      }
      return new Parsed(
          new BigInteger(matcher.group(1)),
          new BigInteger(matcher.group(2)),
          new BigInteger(matcher.group(3)),
          matcher.group(4));
    }

    private int compareCore(Parsed other) {
      int majorResult = major.compareTo(other.major);
      if (majorResult != 0) return majorResult;
      int minorResult = minor.compareTo(other.minor);
      if (minorResult != 0) return minorResult;
      return patch.compareTo(other.patch);
    }
  }
}
