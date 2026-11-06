package com.github.klboke.kkrepo.protocol.ohpm;

public record OhpmPath(
    Kind kind,
    OhpmPackageId packageId,
    String version,
    String fileName,
    String tag) {

  public enum Kind {
    PING,
    LOGIN,
    METADATA,
    PUBLISH,
    STREAM_PUBLISH,
    UNPUBLISH,
    DOWNLOAD,
    DIST_TAG,
    UNKNOWN
  }

  public String assetPath() {
    if (kind == Kind.DOWNLOAD) return packageId.downloadPath(fileName);
    return packageId == null ? null : packageId.id();
  }
}
