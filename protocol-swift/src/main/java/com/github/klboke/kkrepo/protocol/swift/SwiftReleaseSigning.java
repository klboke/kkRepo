package com.github.klboke.kkrepo.protocol.swift;

import java.util.Base64;

public record SwiftReleaseSigning(
    String signatureBase64Encoded,
    String signatureFormat) {
  public SwiftReleaseSigning {
    if (signatureBase64Encoded == null || signatureBase64Encoded.isBlank()
        || signatureFormat == null || signatureFormat.isBlank()) {
      throw new IllegalArgumentException("Swift release signing fields must not be blank");
    }
    Base64.getDecoder().decode(signatureBase64Encoded);
  }
}
