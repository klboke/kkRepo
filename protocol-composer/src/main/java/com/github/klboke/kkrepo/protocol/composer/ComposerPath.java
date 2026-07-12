package com.github.klboke.kkrepo.protocol.composer;

public record ComposerPath(
    Kind kind,
    String rawPath,
    String packageName,
    boolean dev,
    String version,
    String fileName) {

  public enum Kind {
    ROOT,
    PACKAGES,
    PACKAGE_METADATA,
    PROVIDERS,
    PACKAGE_LIST,
    DIST,
    UNKNOWN
  }
}
