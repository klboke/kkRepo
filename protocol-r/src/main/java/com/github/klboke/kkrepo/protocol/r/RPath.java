package com.github.klboke.kkrepo.protocol.r;

/** Parsed CRAN-style repository path. */
public record RPath(
    Kind kind,
    String raw,
    String normalized,
    String namespace,
    String filename,
    String packageName,
    String version) {

  public boolean packageArchive() {
    return kind == Kind.SOURCE_PACKAGE || kind == Kind.ARCHIVE_PACKAGE;
  }

  public boolean gzip() {
    return normalized != null && normalized.endsWith(".gz");
  }

  public enum Kind {
    ROOT,
    PACKAGES,
    PACKAGES_GZIP,
    PACKAGES_RDS,
    SOURCE_PACKAGE,
    ARCHIVE_PACKAGE,
    OTHER_GZIP,
    BINARY,
    STATIC,
    UNKNOWN
  }
}
