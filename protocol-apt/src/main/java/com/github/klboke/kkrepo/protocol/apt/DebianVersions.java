package com.github.klboke.kkrepo.protocol.apt;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Pattern;

/** Debian Policy version validation and dpkg-compatible comparison. */
public final class DebianVersions {
  private static final Pattern UPSTREAM = Pattern.compile("[0-9A-Za-z.+~:-]+");
  private static final Pattern REVISION = Pattern.compile("[0-9A-Za-z.+~]+");
  public static final Comparator<String> COMPARATOR = DebianVersions::compare;

  private DebianVersions() {
  }

  public static String require(String value) {
    parse(value);
    return value;
  }

  public static int compare(String left, String right) {
    Parsed a = parse(left);
    Parsed b = parse(right);
    int epoch = a.epoch().compareTo(b.epoch());
    if (epoch != 0) return epoch;
    int upstream = comparePart(a.upstream(), b.upstream());
    return upstream != 0 ? upstream : comparePart(a.revision(), b.revision());
  }

  private static Parsed parse(String raw) {
    if (raw == null || raw.isBlank() || raw.length() > 255 || !raw.equals(raw.strip())) {
      throw invalid(raw);
    }
    String remainder = raw;
    BigInteger epoch = BigInteger.ZERO;
    int epochSeparator = raw.indexOf(':');
    if (epochSeparator >= 0) {
      String epochText = raw.substring(0, epochSeparator);
      if (epochText.isEmpty() || !epochText.chars().allMatch(Character::isDigit)) throw invalid(raw);
      epoch = new BigInteger(epochText);
      remainder = raw.substring(epochSeparator + 1);
    }
    int revisionSeparator = remainder.lastIndexOf('-');
    String upstream = revisionSeparator >= 0 ? remainder.substring(0, revisionSeparator) : remainder;
    String revision = revisionSeparator >= 0 ? remainder.substring(revisionSeparator + 1) : "0";
    if (upstream.isEmpty() || revision.isEmpty() || !UPSTREAM.matcher(upstream).matches()
        || !REVISION.matcher(revision).matches()) {
      throw invalid(raw);
    }
    return new Parsed(epoch, upstream, revision);
  }

  private static int comparePart(String left, String right) {
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < left.length() || rightIndex < right.length()) {
      while ((leftIndex < left.length() && !Character.isDigit(left.charAt(leftIndex)))
          || (rightIndex < right.length() && !Character.isDigit(right.charAt(rightIndex)))) {
        int leftOrder = order(leftIndex < left.length() ? left.charAt(leftIndex) : 0);
        int rightOrder = order(rightIndex < right.length() ? right.charAt(rightIndex) : 0);
        if (leftOrder != rightOrder) return Integer.compare(leftOrder, rightOrder);
        if (leftIndex < left.length()) leftIndex++;
        if (rightIndex < right.length()) rightIndex++;
      }

      while (leftIndex < left.length() && left.charAt(leftIndex) == '0') leftIndex++;
      while (rightIndex < right.length() && right.charAt(rightIndex) == '0') rightIndex++;
      int leftStart = leftIndex;
      int rightStart = rightIndex;
      while (leftIndex < left.length() && Character.isDigit(left.charAt(leftIndex))) leftIndex++;
      while (rightIndex < right.length() && Character.isDigit(right.charAt(rightIndex))) rightIndex++;
      int leftLength = leftIndex - leftStart;
      int rightLength = rightIndex - rightStart;
      if (leftLength != rightLength) return Integer.compare(leftLength, rightLength);
      for (int offset = 0; offset < leftLength; offset++) {
        char a = left.charAt(leftStart + offset);
        char b = right.charAt(rightStart + offset);
        if (a != b) return Character.compare(a, b);
      }
    }
    return 0;
  }

  private static int order(char value) {
    if (value == '~') return -1;
    if (value == 0) return 0;
    if (Character.isLetter(value)) return value;
    return value + 256;
  }

  private static IllegalArgumentException invalid(String value) {
    return new IllegalArgumentException("Invalid Debian version: " + value);
  }

  private record Parsed(BigInteger epoch, String upstream, String revision) { }
}
