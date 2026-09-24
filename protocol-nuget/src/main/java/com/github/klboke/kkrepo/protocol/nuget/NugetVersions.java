package com.github.klboke.kkrepo.protocol.nuget;

import java.util.Arrays;

/** NuGet precedence: numeric release parts, SemVer prereleases, and ignored build metadata. */
public final class NugetVersions {
  private NugetVersions() {}

  public static int compare(String left, String right) {
    String[] a = withoutBuild(left).split("-", 2);
    String[] b = withoutBuild(right).split("-", 2);
    String[] ar = a[0].split("\\.", -1);
    String[] br = b[0].split("\\.", -1);
    boolean av = Arrays.stream(ar).allMatch(NugetVersions::numeric);
    boolean bv = Arrays.stream(br).allMatch(NugetVersions::numeric);
    // Keep malformed legacy values in a separate lexical ordering. Mixing numeric
    // and lexical comparisons per pair would violate the comparator's transitivity.
    if (!av || !bv) return av != bv ? av ? -1 : 1 : left.compareToIgnoreCase(right);
    for (int i = 0; i < Math.max(ar.length, br.length); i++) {
      String x = i < ar.length ? ar[i] : "0";
      String y = i < br.length ? br[i] : "0";
      int result = compareNumeric(x, y);
      if (result != 0) return result;
    }
    if (a.length != b.length) return a.length == 1 ? 1 : -1;
    if (a.length == 1) return 0;
    String[] ap = a[1].split("\\.", -1);
    String[] bp = b[1].split("\\.", -1);
    for (int i = 0; i < Math.min(ap.length, bp.length); i++) {
      boolean an = numeric(ap[i]), bn = numeric(bp[i]);
      int result = an && bn ? compareNumeric(ap[i], bp[i])
          : an != bn ? an ? -1 : 1 : ap[i].compareToIgnoreCase(bp[i]);
      if (result != 0) return result;
    }
    return Integer.compare(ap.length, bp.length);
  }

  public static String withoutBuild(String version) {
    return version.split("\\+", 2)[0];
  }

  private static int compareNumeric(String left, String right) {
    int a = 0, b = 0;
    while (a < left.length() - 1 && left.charAt(a) == '0') a++;
    while (b < right.length() - 1 && right.charAt(b) == '0') b++;
    int length = Integer.compare(left.length() - a, right.length() - b);
    if (length != 0) return length;
    while (a < left.length()) {
      int digit = Character.compare(left.charAt(a++), right.charAt(b++));
      if (digit != 0) return digit;
    }
    return 0;
  }

  private static boolean numeric(String value) {
    return !value.isEmpty() && value.chars().allMatch(ch -> ch >= '0' && ch <= '9');
  }
}
