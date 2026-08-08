package com.github.klboke.kkrepo.protocol.apt;

import java.util.Locale;

/** Media types used by Debian repository assets. */
public final class AptMediaTypes {
  public static final String DEBIAN_PACKAGE = "application/vnd.debian.binary-package";
  public static final String TEXT = "text/plain; charset=utf-8";
  public static final String PGP_KEYS = "application/pgp-keys";
  public static final String PGP_SIGNATURE = "application/pgp-signature";
  public static final String GZIP = "application/gzip";
  public static final String BZIP2 = "application/x-bzip2";
  public static final String XZ = "application/x-xz";
  public static final String ZSTD = "application/zstd";
  public static final String BINARY = "application/octet-stream";

  private AptMediaTypes() {
  }

  public static String forPath(String path) {
    String value = path == null ? "" : path.toLowerCase(Locale.ROOT);
    if (value.endsWith(".deb") || value.endsWith(".udeb")) return DEBIAN_PACKAGE;
    if (value.endsWith(".gpg")) return PGP_SIGNATURE;
    if (value.endsWith("gpg.key") || value.endsWith(".asc")) return PGP_KEYS;
    if (value.endsWith(".gz")) return GZIP;
    if (value.endsWith(".bz2")) return BZIP2;
    if (value.endsWith(".xz")) return XZ;
    if (value.endsWith(".zst")) return ZSTD;
    if (value.endsWith("release") || value.endsWith("inrelease")
        || value.endsWith("packages") || value.endsWith("sources")) {
      return TEXT;
    }
    return BINARY;
  }
}
