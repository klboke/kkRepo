package com.github.klboke.kkrepo.server.swift;

/** Shared bounded-field limits for SwiftPM and administrative multipart publication paths. */
public final class SwiftPublishLimits {
  public static final int MAX_METADATA_BYTES = 1024 * 1024;

  /**
   * Source signatures are Base64 encoded into a required response header. Keeping the raw CMS
   * value at 4 KiB leaves more than 2 KiB of Tomcat's default 8 KiB total response-header budget
   * for the status line and the other required Swift registry headers.
   */
  public static final int MAX_SOURCE_ARCHIVE_SIGNATURE_BYTES = 4 * 1024;

  /** Metadata signatures are persisted as assets and are not emitted as HTTP header values. */
  public static final int MAX_METADATA_SIGNATURE_BYTES = 4 * 1024 * 1024;

  private SwiftPublishLimits() {}
}
