package com.github.klboke.kkrepo.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Canonical object-key layout shared by every object-storage adapter.
 *
 * <p>Keys are content-addressed and sharded by the first two digest byte pairs to spread load
 * across storage partitions. A random suffix makes each write attempt unique, so a failed or
 * abandoned upload never collides with a published blob and can be deleted on its own.
 */
public final class BlobObjectKeys {
  private BlobObjectKeys() {
  }

  public static String immutableObjectKey(String prefix, String repository, String sha256) {
    String cleanPrefix = trimSlashes(prefix);
    String repoPart = encodeSegment(repository);
    String digestPart = digestSegment(sha256);
    String shardA = digestPart.length() >= 2 ? digestPart.substring(0, 2) : "xx";
    String shardB = digestPart.length() >= 4 ? digestPart.substring(2, 4) : "xx";
    String key = "repositories/" + repoPart + "/blobs/v2/" + shardA + "/" + shardB
        + "/" + digestPart + "/" + UUID.randomUUID();
    return cleanPrefix.isBlank() ? key : cleanPrefix + "/" + key;
  }

  public static String trimSlashes(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.replaceAll("^/+", "").replaceAll("/+$", "");
  }

  private static String encodeSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String digestSegment(String value) {
    if (value == null || value.isBlank()) {
      return "unverified";
    }
    String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
    return normalized.isBlank() ? "unverified" : normalized;
  }
}
