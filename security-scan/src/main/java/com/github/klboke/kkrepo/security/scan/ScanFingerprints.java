package com.github.klboke.kkrepo.security.scan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable, length-delimited SHA-256 fingerprints used for immutable result reuse. */
public final class ScanFingerprints {
  private static final HexFormat HEX = HexFormat.of();

  private ScanFingerprints() {}

  public static String catalog(
      ScanSubject subject,
      String catalogEngine,
      String catalogEngineVersion,
      String catalogConfigurationDigest) {
    return sha256(
        subject.kind().name(),
        subject.identity(),
        subject.classification().name(),
        catalogEngine,
        catalogEngineVersion,
        catalogConfigurationDigest);
  }

  public static String match(
      String sbomSha256,
      boolean inventoryComplete,
      String matcherEngine,
      String matcherEngineVersion,
      String vulnerabilityDatabaseRevision,
      String matchConfigurationDigest) {
    return sha256(
        sbomSha256,
        Boolean.toString(inventoryComplete),
        matcherEngine,
        matcherEngineVersion,
        vulnerabilityDatabaseRevision,
        matchConfigurationDigest);
  }

  public static String finding(
      String advisoryIdentity,
      String packageIdentity,
      String installedVersion,
      String locationIdentity) {
    return sha256(advisoryIdentity, packageIdentity, installedVersion, locationIdentity);
  }

  public static String configuration(String canonicalConfiguration) {
    return sha256(canonicalConfiguration);
  }

  public static String sha256(String... values) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
    for (String value : values) {
      byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
      digest.update((byte) (bytes.length >>> 24));
      digest.update((byte) (bytes.length >>> 16));
      digest.update((byte) (bytes.length >>> 8));
      digest.update((byte) bytes.length);
      digest.update(bytes);
    }
    return HEX.formatHex(digest.digest());
  }
}
