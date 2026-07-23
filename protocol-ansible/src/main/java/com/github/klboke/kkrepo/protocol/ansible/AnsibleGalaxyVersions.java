package com.github.klboke.kkrepo.protocol.ansible;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SemVer 2.0 validation and precedence ordering used by Ansible collection versions. */
public final class AnsibleGalaxyVersions {
  public static final int MAX_LENGTH = 128;
  private static final String NUMERIC = "0|[1-9]\\d*";
  private static final String PRE = "(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)";
  private static final Pattern SEMVER = Pattern.compile(
      "^(" + NUMERIC + ")\\.(" + NUMERIC + ")\\.(" + NUMERIC + ")"
          + "(?:-(" + PRE + "(?:\\." + PRE + ")*))?"
          + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

  public static final Comparator<String> COMPARATOR = AnsibleGalaxyVersions::compare;
  public static final Comparator<String> DESCENDING = (left, right) -> {
    int result = compare(right, left);
    return result == 0 ? left.compareTo(right) : result;
  };

  private AnsibleGalaxyVersions() {
  }

  public static String require(String value) {
    requireMatcher(value);
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

  public static int compare(String left, String right) {
    Parsed a = Parsed.parse(left);
    Parsed b = Parsed.parse(right);
    return compareParsed(a, b);
  }

  private static int compareParsed(Parsed a, Parsed b) {
    int core = a.major.compareTo(b.major);
    if (core == 0) core = a.minor.compareTo(b.minor);
    if (core == 0) core = a.patch.compareTo(b.patch);
    return core == 0 ? comparePrerelease(a.prerelease, b.prerelease) : core;
  }

  public static List<String> sortDescending(Collection<String> versions) {
    if (versions == null) throw new IllegalArgumentException("Ansible versions must not be null");
    return versions.stream()
        .map(value -> new ParsedVersion(value, Parsed.parse(value)))
        .sorted((left, right) -> {
          int result = compareParsed(right.parsed(), left.parsed());
          return result == 0 ? left.value().compareTo(right.value()) : result;
        })
        .map(ParsedVersion::value)
        .toList();
  }

  private static int comparePrerelease(String left, String right) {
    if (left == null && right == null) return 0;
    if (left == null) return 1;
    if (right == null) return -1;
    String[] a = left.split("\\.");
    String[] b = right.split("\\.");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      if (i >= a.length) return -1;
      if (i >= b.length) return 1;
      boolean an = a[i].chars().allMatch(Character::isDigit);
      boolean bn = b[i].chars().allMatch(Character::isDigit);
      int result;
      if (an && bn) result = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
      else if (an) result = -1;
      else if (bn) result = 1;
      else result = a[i].compareTo(b[i]);
      if (result != 0) return result;
    }
    return 0;
  }

  private static Matcher requireMatcher(String value) {
    if (value == null || value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("Invalid Ansible collection version: " + value);
    }
    Matcher matcher = SEMVER.matcher(value);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid Ansible collection version: " + value);
    }
    return matcher;
  }

  private record Parsed(BigInteger major, BigInteger minor, BigInteger patch, String prerelease) {
    private static Parsed parse(String value) {
      Matcher matcher = requireMatcher(value);
      return new Parsed(
          new BigInteger(matcher.group(1)),
          new BigInteger(matcher.group(2)),
          new BigInteger(matcher.group(3)),
          matcher.group(4));
    }
  }

  private record ParsedVersion(String value, Parsed parsed) {
  }
}
