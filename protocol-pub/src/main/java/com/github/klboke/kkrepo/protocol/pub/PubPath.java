package com.github.klboke.kkrepo.protocol.pub;

public record PubPath(
    Kind kind,
    String rawPath,
    String packageName,
    String version,
    String sessionId) {

  public enum Kind {
    ROOT,
    PACKAGE_METADATA,
    VERSION_METADATA,
    VERSION_JSON,
    ARCHIVE,
    PUBLISH_INIT,
    PUBLISH_UPLOAD,
    PUBLISH_FINALIZE,
    ADVISORIES,
    PACKAGE_NAMES,
    PACKAGE_NAME_COMPLETION,
    UNKNOWN
  }
}
