package com.github.klboke.kkrepo.protocol.alpine;

/** Parsed path below one Nexus-compatible Alpine repository root. */
public record AlpinePath(
    Kind kind,
    String raw,
    String normalized,
    String distribution,
    String channel,
    String repositoryArchitecture,
    String filename) {

  public enum Kind {
    ROOT,
    INDEX,
    PACKAGE,
    V3_INDEX,
    UNKNOWN
  }

  public boolean metadata() {
    return kind == Kind.INDEX || kind == Kind.V3_INDEX;
  }

  public boolean immutable() {
    return kind == Kind.PACKAGE;
  }

  public String namespace() {
    if (distribution == null || channel == null || repositoryArchitecture == null) return null;
    return distribution + "/" + channel + "/" + repositoryArchitecture;
  }
}
