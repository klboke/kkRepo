package com.github.klboke.kkrepo.protocol.winget;

import java.util.Locale;

/** WinGet version ordering (SemVer precedence with a deterministic fallback). */
public final class WingetVersions {
  private WingetVersions() {}

  public static int compare(String left, String right) {
    if (left == null && right == null) return 0;
    if (left == null) return -1;
    if (right == null) return 1;
    String a = left.trim();
    String b = right.trim();
    if (a.equals(b)) return 0;

    String[] aParts = split(a);
    String[] bParts = split(b);
    int core = compareCore(aParts[0], bParts[0]);
    if (core != 0) return core;
    int prerelease = comparePrerelease(aParts[1], bParts[1]);
    if (prerelease != 0) return prerelease;
    return 0;
  }

  private static String[] split(String version) {
    String withoutBuild = version;
    int plus = withoutBuild.indexOf('+');
    if (plus >= 0) {
      withoutBuild = withoutBuild.substring(0, plus);
    }
    int dash = withoutBuild.indexOf('-');
    if (dash < 0) {
      return new String[] {withoutBuild, ""};
    }
    return new String[] {withoutBuild.substring(0, dash), withoutBuild.substring(dash + 1)};
  }

  private static int compareCore(String left, String right) {
    String[] a = left.split("\\.", -1);
    String[] b = right.split("\\.", -1);
    int length = Math.max(a.length, b.length);
    for (int i = 0; i < length; i++) {
      String x = i < a.length ? a[i] : "0";
      String y = i < b.length ? b[i] : "0";
      int compared = compareIdentifier(x, y, true);
      if (compared != 0) return compared;
    }
    return 0;
  }

  private static int comparePrerelease(String left, String right) {
    boolean leftEmpty = left.isEmpty();
    boolean rightEmpty = right.isEmpty();
    if (leftEmpty && rightEmpty) return 0;
    if (leftEmpty) return 1;
    if (rightEmpty) return -1;
    String[] a = left.split("\\.", -1);
    String[] b = right.split("\\.", -1);
    int length = Math.max(a.length, b.length);
    for (int i = 0; i < length; i++) {
      if (i >= a.length) return -1;
      if (i >= b.length) return 1;
      boolean aNumeric = isNumeric(a[i]);
      boolean bNumeric = isNumeric(b[i]);
      if (aNumeric && bNumeric) {
        int compared = compareNumeric(a[i], b[i]);
        if (compared != 0) return compared;
      } else if (aNumeric != bNumeric) {
        return aNumeric ? -1 : 1;
      } else {
        int compared = a[i].compareToIgnoreCase(b[i]);
        if (compared != 0) return compared;
      }
    }
    return 0;
  }

  private static int compareIdentifier(String left, String right, boolean missingZero) {
    boolean aNumeric = isNumeric(left);
    boolean bNumeric = isNumeric(right);
    if (aNumeric && bNumeric) {
      return compareNumeric(left, right);
    }
    if (aNumeric != bNumeric) {
      return aNumeric ? -1 : 1;
    }
    return left.compareToIgnoreCase(right);
  }

  private static int compareNumeric(String left, String right) {
    String a = stripLeadingZeros(left);
    String b = stripLeadingZeros(right);
    if (a.length() != b.length()) return Integer.compare(a.length(), b.length());
    return a.compareTo(b);
  }

  private static String stripLeadingZeros(String value) {
    int index = 0;
    while (index < value.length() - 1 && value.charAt(index) == '0') {
      index++;
    }
    return value.substring(index);
  }

  private static boolean isNumeric(String value) {
    if (value.isEmpty()) return false;
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) return false;
    }
    return true;
  }

  public static String channelKey(String channel) {
    return channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
  }
}
