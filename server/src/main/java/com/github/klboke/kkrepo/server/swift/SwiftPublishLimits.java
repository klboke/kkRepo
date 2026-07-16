package com.github.klboke.kkrepo.server.swift;

/** Shared bounded-field limits for SwiftPM and administrative multipart publication paths. */
public final class SwiftPublishLimits {
  public static final int MAX_METADATA_BYTES = 1024 * 1024;
  public static final int MAX_SIGNATURE_BYTES = 4 * 1024 * 1024;

  private SwiftPublishLimits() {}
}
