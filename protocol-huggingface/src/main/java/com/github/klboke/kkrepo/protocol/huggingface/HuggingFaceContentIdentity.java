package com.github.klboke.kkrepo.protocol.huggingface;

import java.util.Locale;
import java.util.regex.Pattern;

/** Expected identity from tree and resolve metadata. Xet hashes are provenance, not checksums. */
public record HuggingFaceContentIdentity(
    String gitOid, String linkedSha256, Long expectedSize, String xetHash) {
  private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");

  public HuggingFaceContentIdentity {
    gitOid = normalizeGitOid(gitOid);
    linkedSha256 = normalizeSha256(linkedSha256, "linked SHA-256");
    xetHash = safeProvenance(xetHash);
    if (expectedSize != null && expectedSize < 0) {
      throw new IllegalArgumentException("Expected size must not be negative");
    }
  }

  /**
   * Resolves the dual meaning of Hub's {@code X-Linked-ETag}: regular Git blobs expose a
   * 40-character Git object id while LFS/Xet-backed blobs expose their 64-character SHA-256.
   */
  public static HuggingFaceContentIdentity fromResolveHeaders(
      String knownGitOid,
      String knownLinkedSha256,
      Long expectedSize,
      String xetHash,
      String linkedEtag,
      String responseEtag) {
    String gitOid = normalizeGitOid(knownGitOid);
    String linkedSha256 = normalizeSha256(knownLinkedSha256, "linked SHA-256");
    String linked = normalizeLinkedEtag(linkedEtag);
    if (linked != null) {
      if (linked.length() == 64) {
        if (linkedSha256 != null && !linkedSha256.equals(linked)) {
          throw new IllegalArgumentException("Hugging Face linked SHA-256 changed");
        }
        linkedSha256 = linked;
      } else {
        if (gitOid != null && !gitOid.equals(linked)) {
          throw new IllegalArgumentException("Hugging Face Git OID changed");
        }
        gitOid = linked;
      }
    }
    if (gitOid == null && linkedSha256 == null) {
      gitOid = optionalGitOid(responseEtag);
    }
    return new HuggingFaceContentIdentity(gitOid, linkedSha256, expectedSize, xetHash);
  }

  public void verify(String actualSha256, long actualSize) {
    if (expectedSize != null && expectedSize != actualSize) {
      throw new IllegalArgumentException(
          "Hugging Face content size mismatch: expected " + expectedSize + ", got " + actualSize);
    }
    if (linkedSha256 != null && !linkedSha256.equalsIgnoreCase(actualSha256)) {
      throw new IllegalArgumentException("Hugging Face linked SHA-256 mismatch");
    }
  }

  private static String normalizeGitOid(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = normalizeHex(value, "Git OID");
    if (normalized.length() != 40 && normalized.length() != 64) {
      throw new IllegalArgumentException("Invalid Git OID");
    }
    return normalized;
  }

  private static String normalizeSha256(String value, String label) {
    if (value == null || value.isBlank()) return null;
    String normalized = normalizeHex(value, label);
    if (normalized.length() != 64) {
      throw new IllegalArgumentException("Invalid " + label);
    }
    return normalized;
  }

  private static String normalizeLinkedEtag(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = normalizeHex(value, "linked ETag");
    if (normalized.length() != 40 && normalized.length() != 64) {
      throw new IllegalArgumentException("Invalid linked ETag");
    }
    return normalized;
  }

  private static String optionalGitOid(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = HuggingFaceHeaders.unquote(value).trim();
    if ((normalized.length() != 40 && normalized.length() != 64)
        || !HEX.matcher(normalized).matches()) {
      return null;
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  private static String normalizeHex(String value, String label) {
    if (value == null || value.isBlank()) return null;
    String normalized = HuggingFaceHeaders.unquote(value).trim();
    if (normalized.startsWith("sha256:")) normalized = normalized.substring(7);
    if (!HEX.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Invalid " + label);
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  private static String safeProvenance(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = HuggingFaceHeaders.unquote(value).trim();
    if (normalized.length() > 256 || normalized.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      throw new IllegalArgumentException("Invalid Xet provenance hash");
    }
    return normalized;
  }
}
