package com.github.klboke.kkrepo.protocol.openvsx;

import java.util.Locale;
import java.util.regex.Pattern;

/** Identity rules shared by the Open VSX registry routes and storage layout. */
public final class OpenVsxCoordinates {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final Pattern VERSION = Pattern.compile(
      "(?=.{1,64}$)[0-9A-Za-z]+(?:[._+-][0-9A-Za-z]+){0,31}");
  private static final Pattern TARGET_PLATFORM = Pattern.compile(
      "(win32-x64|win32-ia32|win32-arm64|linux-x64|linux-arm64|linux-armhf|alpine-x64|alpine-arm64|darwin-x64|darwin-arm64|web|universal)");

  private OpenVsxCoordinates() {
  }

  public static boolean validName(String value) {
    return value != null && NAME.matcher(value).matches()
        && !value.equals(".") && !value.equals("..");
  }

  public static boolean validVersion(String value) {
    return value != null && VERSION.matcher(value).matches()
        && !value.equals(".") && !value.equals("..");
  }

  public static boolean validTargetPlatform(String value) {
    return value == null || value.isBlank() || TARGET_PLATFORM.matcher(value).matches();
  }

  /** Open VSX normalizes extension names for lookups but preserves namespace case. */
  public static String normalizeName(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }

  public static String extensionId(String namespace, String extension) {
    return namespace + "." + normalizeName(extension);
  }
}
