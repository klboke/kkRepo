package com.github.klboke.kkrepo.protocol.terraform;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;

/** Terraform-compatible SemVer precedence (build metadata does not affect ordering). */
public final class TerraformVersions {
  private TerraformVersions() {}

  public static List<String> descending(Iterable<String> versions) {
    java.util.ArrayList<String> result = new java.util.ArrayList<>();
    versions.forEach(result::add);
    result.sort(comparator().reversed());
    return List.copyOf(result);
  }

  public static Comparator<String> comparator() {
    return (left, right) -> compare(parse(left), parse(right));
  }

  private static Version parse(String raw) {
    String withoutBuild = raw.split("\\+", 2)[0];
    String[] mainAndPre = withoutBuild.split("-", 2);
    String[] main = mainAndPre[0].split("\\.");
    return new Version(new BigInteger(main[0]), new BigInteger(main[1]), new BigInteger(main[2]),
        mainAndPre.length == 1 ? List.of() : List.of(mainAndPre[1].split("\\.")));
  }

  private static int compare(Version a, Version b) {
    int c = a.major.compareTo(b.major);
    if (c == 0) c = a.minor.compareTo(b.minor);
    if (c == 0) c = a.patch.compareTo(b.patch);
    if (c != 0) return c;
    if (a.pre.isEmpty()) return b.pre.isEmpty() ? 0 : 1;
    if (b.pre.isEmpty()) return -1;
    for (int i = 0; i < Math.min(a.pre.size(), b.pre.size()); i++) {
      String x = a.pre.get(i);
      String y = b.pre.get(i);
      boolean xn = x.matches("[0-9]+");
      boolean yn = y.matches("[0-9]+");
      c = xn && yn ? new BigInteger(x).compareTo(new BigInteger(y))
          : xn ? -1 : yn ? 1 : x.compareTo(y);
      if (c != 0) return c;
    }
    return Integer.compare(a.pre.size(), b.pre.size());
  }

  private record Version(BigInteger major, BigInteger minor, BigInteger patch, List<String> pre) {}
}
