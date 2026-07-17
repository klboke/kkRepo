package com.github.klboke.kkrepo.protocol.swift;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Locale;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SwiftReleaseResource(
    String name,
    String type,
    String checksum,
    SwiftReleaseSigning signing) {
  public SwiftReleaseResource {
    if (!"source-archive".equals(name) || !SwiftMediaTypes.ARCHIVE.equals(type)) {
      throw new IllegalArgumentException("Unsupported Swift release resource");
    }
    if (checksum == null || !checksum.matches("^[0-9A-Fa-f]{64}$")) {
      throw new IllegalArgumentException("Invalid Swift source archive SHA-256");
    }
    checksum = checksum.toLowerCase(Locale.ROOT);
  }

  public static SwiftReleaseResource sourceArchive(String sha256, SwiftReleaseSigning signing) {
    return new SwiftReleaseResource("source-archive", SwiftMediaTypes.ARCHIVE, sha256, signing);
  }
}
