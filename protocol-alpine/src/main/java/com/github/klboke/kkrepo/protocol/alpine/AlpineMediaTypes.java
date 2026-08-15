package com.github.klboke.kkrepo.protocol.alpine;

import java.util.Locale;

/** Content types used by APK v2 repositories. */
public final class AlpineMediaTypes {
  /** Nexus 3.94 serves both APK v2 packages and indexes as gzip payloads. */
  public static final String APK_PACKAGE = "application/gzip";
  public static final String APK_INDEX = "application/gzip";
  public static final String PUBLIC_KEY = "application/x-pem-file";
  public static final String OCTET_STREAM = "application/octet-stream";

  private AlpineMediaTypes() {
  }

  public static String forPath(String path) {
    String value = path == null ? "" : path.toLowerCase(Locale.ROOT);
    if (value.endsWith(".apk")) return APK_PACKAGE;
    if (value.endsWith("/apkindex.tar.gz") || value.equals("apkindex.tar.gz")) return APK_INDEX;
    if (value.endsWith(".rsa.pub")) return PUBLIC_KEY;
    return OCTET_STREAM;
  }
}
