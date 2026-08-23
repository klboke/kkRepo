package com.github.klboke.kkrepo.protocol.r;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** R {@code numeric_version}/{@code package_version} validation and ordering. */
public final class RVersions {
  public static final Comparator<String> COMPARATOR = RVersions::compare;
  private static final Pattern VERSION = Pattern.compile("[0-9]+(?:[.-][0-9]+)+");
  private static final int MAX_LENGTH = 255;

  private RVersions() {
  }

  public static boolean isValid(String value) {
    return value != null && value.length() <= MAX_LENGTH && VERSION.matcher(value).matches();
  }

  public static String require(String value) {
    if (!isValid(value)) {
      throw new IllegalArgumentException("Invalid R package version: " + value);
    }
    return value;
  }

  public static int compare(String left, String right) {
    if (left == right) return 0;
    if (left == null) return -1;
    if (right == null) return 1;
    List<BigInteger> a = segments(require(left));
    List<BigInteger> b = segments(require(right));
    int length = Math.min(a.size(), b.size());
    for (int index = 0; index < length; index++) {
      int compared = a.get(index).compareTo(b.get(index));
      if (compared != 0) return compared;
    }
    return Integer.compare(a.size(), b.size());
  }

  /**
   * Binary-collated key whose lexical order follows {@link #compare(String, String)}.
   * A key that is a strict prefix sorts first, matching R's shorter-version ordering.
   */
  public static byte[] orderKey(String value) {
    List<BigInteger> values = new ArrayList<>(segments(require(value)));
    StringBuilder key = new StringBuilder("r1|");
    for (BigInteger segment : values) {
      String digits = segment.toString();
      key.append(String.format(java.util.Locale.ROOT, "%04d", digits.length()))
          .append(':').append(digits).append('|');
    }
    return key.toString().getBytes(StandardCharsets.US_ASCII);
  }

  private static List<BigInteger> segments(String value) {
    String[] raw = value.split("[.-]", -1);
    List<BigInteger> result = new ArrayList<>(raw.length);
    for (String part : raw) result.add(new BigInteger(part));
    return result;
  }
}
