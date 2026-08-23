package com.github.klboke.kkrepo.protocol.r;

/** Media types used by CRAN-style repositories. */
public final class RMediaTypes {
  public static final String SOURCE_PACKAGE = "application/x-gzip";
  public static final String PACKAGES = "text/plain; charset=UTF-8";
  public static final String PACKAGES_GZIP = "application/x-gzip";
  public static final String PACKAGES_RDS = "application/octet-stream";

  private RMediaTypes() {
  }

  public static String forPath(RPath path) {
    return switch (path.kind()) {
      case PACKAGES -> PACKAGES;
      case PACKAGES_GZIP, SOURCE_PACKAGE, ARCHIVE_PACKAGE, OTHER_GZIP -> PACKAGES_GZIP;
      case PACKAGES_RDS, BINARY, STATIC -> PACKAGES_RDS;
      case ROOT, UNKNOWN -> "application/octet-stream";
    };
  }
}
