package com.github.klboke.kkrepo.protocol.alpine;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/** APK v2 package identity and internal digest helpers. */
public final class AlpineChecksums {
  private static final Pattern V2_IDENTITY =
      Pattern.compile("Q1[A-Za-z0-9+/]{27}=");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

  private AlpineChecksums() {
  }

  /** Computes the apk-tools Q1 identity over the raw compressed control gzip member. */
  public static String v2Identity(byte[] compressedControlMember) {
    if (compressedControlMember == null || compressedControlMember.length == 0) {
      throw new IllegalArgumentException("Compressed APK control member is required");
    }
    return "Q1" + Base64.getEncoder().encodeToString(digest("SHA-1", compressedControlMember));
  }

  public static String requireV2Identity(String value) {
    if (value == null || !V2_IDENTITY.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid APK v2 package identity: " + value);
    }
    return value;
  }

  public static String requireSha256(String value) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid SHA-256 digest: " + value);
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static byte[] digest(String algorithm, byte[] bytes) {
    try {
      return MessageDigest.getInstance(algorithm).digest(bytes);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
