package com.github.klboke.kkrepo.protocol.ohpm;

import java.util.regex.Pattern;

public final class OhpmVersions {
  private static final String NUMBER = "(?:0|[1-9][0-9]*)";
  private static final String PRERELEASE = "(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)";
  private static final Pattern VERSION = Pattern.compile("v?(" + NUMBER + ")\\.(" + NUMBER + ")\\.(" + NUMBER + ")"
      + "(?:-" + PRERELEASE + "(?:\\." + PRERELEASE + ")*)?"
      + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

  private OhpmVersions() {}

  public static boolean isValid(String version) {
    if (version == null || version.length() > 256) return false;
    var matcher = VERSION.matcher(version);
    if (!matcher.matches()) return false;
    try {
      for (int component = 1; component <= 3; component++) {
        if (Long.parseLong(matcher.group(component)) > 9_007_199_254_740_991L) return false;
      }
      return true;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }
}
