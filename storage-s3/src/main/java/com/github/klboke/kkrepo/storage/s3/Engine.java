package com.github.klboke.kkrepo.storage.s3;

import java.util.Locale;

/**
 * Object-storage access engine for an S3-type blob store. Each engine maps to a distinct
 * {@link com.github.klboke.kkrepo.core.BlobStorage} implementation:
 * <ul>
 *   <li>{@link #AWS_S3} — AWS S3 SDK (works against AWS S3, RustFS, and S3-compatible stores)</li>
 *   <li>{@link #OSS_NATIVE} — Aliyun native OSS SDK v2</li>
 * </ul>
 *
 * <p>The canonical {@link #id()} is what gets persisted in the blob-store {@code engine} attribute;
 * {@link #fromValue(String)} accepts that id plus historical aliases and falls back to
 * {@link #AWS_S3} for unknown/blank values (the safe default that also covers generic S3-compatible
 * stores).
 */
public enum Engine {
  AWS_S3("aws-s3"),
  OSS_NATIVE("oss-native");

  private final String id;

  Engine(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  public static Engine fromValue(String value) {
    if (value == null || value.isBlank()) {
      return AWS_S3;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "oss", "oss-native", "oss-sdk", "oss-v2" -> OSS_NATIVE;
      case "s3", "aws", "aws-s3" -> AWS_S3;
      default -> AWS_S3;
    };
  }
}
