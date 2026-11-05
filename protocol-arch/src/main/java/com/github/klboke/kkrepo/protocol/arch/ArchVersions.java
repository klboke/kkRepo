package com.github.klboke.kkrepo.protocol.arch;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Locale;

/**
 * Approximate pacman {@code vercmp} ordering for cleanup and latest-package selection.
 *
 * <p>This intentionally follows the documented epoch/pkgver/pkgrel model rather than comparing
 * whole filename strings. Full {@code vercmp} corner cases (alpha/beta suffix weighting) are
 * documented as a limitation; repository database generation never reorders versions, so clients
 * always see the exact package set uploaded by maintainers.
 */
public final class ArchVersions {
  public static final Comparator<String> COMPARATOR = ArchVersions::compare;

  private ArchVersions() {
  }

  public static int compare(String left, String right) {
    if (left == null && right == null) return 0;
    if (left == null) return -1;
    if (right == null) return 1;
    String[] a = splitVersion(left);
    String[] b = splitVersion(right);
    int epoch = new BigInteger(a[0]).compareTo(new BigInteger(b[0]));
    if (epoch != 0) return Integer.signum(epoch);
    int version = compareSegments(a[1], b[1]);
    if (version != 0) return version;
    return new BigInteger(a[2]).compareTo(new BigInteger(b[2]));
  }

  private static String[] splitVersion(String value) {
    String normalized = value.trim();
    String epoch = "0";
    int colon = normalized.indexOf(':');
    if (colon > 0) {
      epoch = digits(normalized.substring(0, colon));
      normalized = normalized.substring(colon + 1);
    }
    String rel = "0";
    int dash = normalized.lastIndexOf('-');
    if (dash > 0) {
      String candidate = normalized.substring(dash + 1);
      if (candidate.chars().allMatch(Character::isDigit)) {
        rel = candidate;
        normalized = normalized.substring(0, dash);
      }
    }
    return new String[] {epoch, normalized, rel};
  }

  private static String digits(String value) {
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < value.length() && Character.isDigit(value.charAt(i)); i++) {
      digits.append(value.charAt(i));
    }
    return digits.isEmpty() ? "0" : digits.toString();
  }

  private static int compareSegments(String left, String right) {
    String[] a = tokenize(left);
    String[] b = tokenize(right);
    int length = Math.max(a.length, b.length);
    for (int i = 0; i < length; i++) {
      if (i >= a.length) return -1;
      if (i >= b.length) return 1;
      int compare = compareToken(a[i], b[i]);
      if (compare != 0) return compare;
    }
    return 0;
  }

  private static String[] tokenize(String value) {
    return value.toLowerCase(Locale.ROOT).split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)|[._+-]");
  }

  private static int compareToken(String left, String right) {
    if (left.isEmpty() && right.isEmpty()) return 0;
    if (left.isEmpty()) return -1;
    if (right.isEmpty()) return 1;
    boolean leftNumeric = left.chars().allMatch(Character::isDigit);
    boolean rightNumeric = right.chars().allMatch(Character::isDigit);
    if (leftNumeric && rightNumeric) {
      return Integer.signum(new BigInteger(left).compareTo(new BigInteger(right)));
    }
    if (leftNumeric != rightNumeric) {
      return leftNumeric ? 1 : -1;
    }
    int alpha = Integer.signum(left.compareTo(right));
    if (alpha != 0) return alpha;
    return 0;
  }
}
