package com.github.klboke.kkrepo.protocol.openvsx;

import java.util.Comparator;
import java.util.List;

/**
 * Version ordering for Open VSX metadata. Extension versions are SemVer-like; build metadata is
 * ignored for ordering and a prerelease sorts before the corresponding release, matching the
 * VS Code marketplace and Open VSX selection rules.
 */
public final class OpenVsxVersions {
  public static final Comparator<String> COMPARATOR = OpenVsxVersions::compare;
  public static final Comparator<String> DESCENDING = COMPARATOR.reversed();

  private OpenVsxVersions() {
  }

  public static boolean valid(String version) {
    return OpenVsxCoordinates.validVersion(version);
  }

  public static boolean isPrerelease(String version) {
    if (version == null) return false;
    String withoutBuild = buildStripped(version);
    return withoutBuild.indexOf('-') >= 0;
  }

  public static String latest(List<String> versions) {
    if (versions == null || versions.isEmpty()) return null;
    return versions.stream()
        .filter(OpenVsxVersions::valid)
        .filter(version -> !isPrerelease(version))
        .max(COMPARATOR)
        .orElseGet(() -> versions.stream().filter(OpenVsxVersions::valid)
            .max(COMPARATOR).orElse(null));
  }

  public static int compare(String left, String right) {
    String[] a = core(left);
    String[] b = core(right);
    int numeric = compareNumeric(a[0], b[0]);
    if (numeric != 0) return numeric;
    return comparePrerelease(a[1], b[1]);
  }

  private static String[] core(String version) {
    String value = version == null ? "0" : version.trim();
    int plus = value.indexOf('+');
    if (plus >= 0) value = value.substring(0, plus);
    int dash = value.indexOf('-');
    if (dash < 0) return new String[] {value, ""};
    return new String[] {value.substring(0, dash), value.substring(dash + 1)};
  }

  private static String buildStripped(String version) {
    int plus = version.indexOf('+');
    return plus < 0 ? version : version.substring(0, plus);
  }

  private static int compareNumeric(String left, String right) {
    String[] a = left.split("\\.");
    String[] b = right.split("\\.");
    int length = Math.max(a.length, b.length);
    for (int i = 0; i < length; i++) {
      String x = i < a.length ? a[i] : "0";
      String y = i < b.length ? b[i] : "0";
      int numeric = comparePart(x, y);
      if (numeric != 0) return numeric;
    }
    return 0;
  }

  private static int comparePart(String left, String right) {
    if (left.isEmpty() || right.isEmpty()) {
      if (left.isEmpty() && right.isEmpty()) return 0;
      return left.isEmpty() ? -1 : 1;
    }
    boolean leftNumeric = left.chars().allMatch(Character::isDigit);
    boolean rightNumeric = right.chars().allMatch(Character::isDigit);
    if (leftNumeric && rightNumeric) {
      int compare = new java.math.BigInteger(left).compareTo(new java.math.BigInteger(right));
      return Integer.signum(compare);
    }
    if (leftNumeric != rightNumeric) {
      return leftNumeric ? -1 : 1;
    }
    return Integer.signum(left.compareTo(right));
  }

  private static int comparePrerelease(String left, String right) {
    boolean leftEmpty = left.isEmpty();
    boolean rightEmpty = right.isEmpty();
    if (leftEmpty && rightEmpty) return 0;
    if (leftEmpty) return 1;
    if (rightEmpty) return -1;
    String[] a = left.split("[.-]");
    String[] b = right.split("[.-]");
    int length = Math.max(a.length, b.length);
    for (int i = 0; i < length; i++) {
      if (i >= a.length) return -1;
      if (i >= b.length) return 1;
      int compare = comparePart(a[i], b[i]);
      if (compare != 0) return compare;
    }
    return 0;
  }
}
