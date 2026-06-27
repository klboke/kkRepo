package com.github.klboke.kkrepo.protocol.cargo;

public record CargoPath(
    Kind kind,
    String rawPath,
    String crateName,
    String version) {

  public enum Kind {
    ROOT,
    CONFIG,
    INDEX,
    PUBLISH,
    DOWNLOAD,
    YANK,
    UNYANK,
    SEARCH,
    OWNERS,
    UNKNOWN
  }

  public boolean isIndex() {
    return kind == Kind.INDEX;
  }

  public boolean isApi() {
    return switch (kind) {
      case PUBLISH, DOWNLOAD, YANK, UNYANK, SEARCH, OWNERS -> true;
      default -> false;
    };
  }
}
