package com.github.klboke.kkrepo.security.scan;

import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable content identity presented to the catalog stage. */
public record ScanSubject(
    SubjectKind kind,
    long repositoryId,
    Long assetId,
    Long assetBlobId,
    String identity,
    String sha256,
    long size,
    String format,
    String assetKind,
    String mediaType,
    TargetClassification classification,
    List<String> platforms,
    Map<String, Object> attributes) {

  public ScanSubject {
    Objects.requireNonNull(kind, "kind");
    identity = requireText(identity, "identity");
    sha256 = normalizeSha256(sha256);
    if (repositoryId <= 0) throw new IllegalArgumentException("repositoryId must be positive");
    if (size < 0) throw new IllegalArgumentException("size must not be negative");
    Objects.requireNonNull(classification, "classification");
    platforms = platforms == null ? List.of() : List.copyOf(platforms);
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private static String normalizeSha256(String value) {
    String normalized = requireText(value, "sha256").trim().toLowerCase(java.util.Locale.ROOT);
    if (!normalized.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters");
    }
    return normalized;
  }
}
