package com.github.klboke.kkrepo.protocol.ohpm;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public record OhpmIntegrity(String value) {
  private static final String PREFIX = "sha512-";

  public OhpmIntegrity {
    if (value == null || value.length() != PREFIX.length() + 88 || !value.startsWith(PREFIX)) {
      throw new IllegalArgumentException("OHPM integrity must use SHA-512");
    }
    byte[] digest = Base64.getDecoder().decode(value.substring(PREFIX.length()));
    if (digest.length != 64 || !encode(digest).equals(value)) {
      throw new IllegalArgumentException("Invalid OHPM SHA-512 integrity");
    }
  }

  public static OhpmIntegrity compute(InputStream input) throws IOException {
    MessageDigest digest = sha512();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    return new OhpmIntegrity(encode(digest.digest()));
  }

  public boolean matches(InputStream input) throws IOException {
    OhpmIntegrity actual = compute(input);
    return MessageDigest.isEqual(
        Base64.getDecoder().decode(value.substring(PREFIX.length())),
        Base64.getDecoder().decode(actual.value.substring(PREFIX.length())));
  }

  private static String encode(byte[] digest) {
    return PREFIX + Base64.getEncoder().encodeToString(digest);
  }

  private static MessageDigest sha512() {
    try {
      return MessageDigest.getInstance("SHA-512");
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }
}
