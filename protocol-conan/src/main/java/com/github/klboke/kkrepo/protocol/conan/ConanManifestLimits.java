package com.github.klboke.kkrepo.protocol.conan;

/** Immutable resource limits, independent of the Conan manifest wire format. */
public record ConanManifestLimits(int maxEntries, int maxBytes) {
  public static final ConanManifestLimits DEFAULTS = new ConanManifestLimits(
      ConanManifest.MAX_ENTRIES, ConanManifest.MAX_BYTES);

  public ConanManifestLimits {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("Conan manifest max-entries must be positive");
    }
    // Bounded readers probe one extra byte to detect an oversized stream.
    if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "Conan manifest max-bytes must be between 1 and 2147483646 bytes");
    }
  }

  public String entryLimitMessage(long observedEntries) {
    return "Conan manifest entry limit exceeded: limit=" + maxEntries
        + ", observed=" + observedEntries;
  }

  public String byteLimitMessage(long observedBytes) {
    return "Conan manifest size limit exceeded: limit=" + maxBytes
        + " bytes, observed=" + observedBytes + " bytes";
  }
}
