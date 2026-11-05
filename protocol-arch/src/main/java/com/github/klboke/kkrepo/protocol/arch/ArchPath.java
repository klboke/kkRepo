package com.github.klboke.kkrepo.protocol.arch;

/** Parsed repository-relative pacman path. */
public record ArchPath(
    Kind kind,
    String rawPath,
    String fileName,
    String databaseName,
    String packageName,
    String packageVersion,
    String architecture,
    String compression) {

  public enum Kind {
    ROOT,
    DATABASE,
    DATABASE_TAR,
    FILES,
    FILES_TAR,
    SIGNATURE,
    PUBLIC_KEY,
    PACKAGE,
    UNKNOWN
  }

  public boolean generated() {
    return kind == Kind.DATABASE || kind == Kind.DATABASE_TAR
        || kind == Kind.FILES || kind == Kind.FILES_TAR;
  }

  public boolean signature() {
    return kind == Kind.SIGNATURE;
  }

  public boolean databaseSignature() {
    return kind == Kind.SIGNATURE && databaseName != null;
  }
}
