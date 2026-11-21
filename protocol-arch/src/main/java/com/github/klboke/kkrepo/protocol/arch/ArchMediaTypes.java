package com.github.klboke.kkrepo.protocol.arch;

/** Content types used by pacman repositories. */
public final class ArchMediaTypes {
  public static final String DATABASE = "application/x-tar";
  public static final String DATABASE_GZIP = "application/gzip";
  public static final String PACKAGE_ZSTD = "application/zstd";
  public static final String PACKAGE_XZ = "application/x-xz";
  public static final String PACKAGE_GZIP = "application/gzip";
  public static final String SIGNATURE = "application/pgp-signature";
  public static final String PGP_KEYS = "application/pgp-keys";
  public static final String OCTET_STREAM = "application/octet-stream";

  private ArchMediaTypes() {
  }

  public static String forPackage(String filename) {
    if (filename == null) return OCTET_STREAM;
    if (filename.endsWith(".zst")) return PACKAGE_ZSTD;
    if (filename.endsWith(".xz")) return PACKAGE_XZ;
    if (filename.endsWith(".gz")) return PACKAGE_GZIP;
    return OCTET_STREAM;
  }
}
