package com.github.klboke.kkrepo.protocol.apt;

/** Parsed path below a Nexus-compatible APT repository root. */
public record AptPath(
    Kind kind,
    String raw,
    String normalized,
    String distribution,
    String component,
    String architecture,
    String filename,
    Compression compression) {

  public enum Kind {
    ROOT,
    PUBLIC_KEY,
    IN_RELEASE,
    RELEASE,
    RELEASE_SIGNATURE,
    PACKAGES,
    SOURCES,
    CONTENTS,
    TRANSLATION,
    PDIFF,
    BY_HASH,
    PACKAGE,
    FLAT_METADATA,
    UNKNOWN
  }

  public enum Compression {
    NONE,
    GZIP,
    BZIP2,
    XZ,
    ZSTD
  }

  public boolean metadata() {
    return switch (kind) {
      case IN_RELEASE, RELEASE, RELEASE_SIGNATURE, PACKAGES, SOURCES, CONTENTS,
          TRANSLATION, PDIFF, BY_HASH, FLAT_METADATA, PUBLIC_KEY -> true;
      default -> false;
    };
  }

  public boolean immutable() {
    return kind == Kind.BY_HASH || kind == Kind.PACKAGE;
  }
}
