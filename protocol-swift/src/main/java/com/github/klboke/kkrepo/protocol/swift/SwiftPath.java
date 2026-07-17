package com.github.klboke.kkrepo.protocol.swift;

public record SwiftPath(
    Kind kind,
    String rawPath,
    String scope,
    String name,
    String version,
    boolean jsonAlias) {

  public SwiftPath(Kind kind, String rawPath, String scope, String name, String version) {
    this(kind, rawPath, scope, name, version, false);
  }

  public enum Kind {
    ROOT,
    LOGIN,
    IDENTIFIERS,
    RELEASE_LIST,
    RELEASE_METADATA,
    MANIFEST,
    SOURCE_ARCHIVE,
    UNKNOWN
  }

  public String identity() {
    return scope == null || name == null ? null : scope + "." + name;
  }

  public String identityKey() {
    return scope == null || name == null
        ? null
        : SwiftScope.key(scope) + "." + SwiftPackageName.key(name);
  }

  public boolean hasReleaseCoordinate() {
    return scope != null && name != null && version != null;
  }
}
