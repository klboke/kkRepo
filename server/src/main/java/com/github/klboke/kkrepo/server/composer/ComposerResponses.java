package com.github.klboke.kkrepo.server.composer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

final class ComposerResponses {
  static final String JSON = "application/json";

  private ComposerResponses() {
  }

  static MavenResponse json(ObjectMapper mapper, Object value, Instant lastModified, boolean headOnly) {
    byte[] bytes;
    try {
      bytes = mapper.writeValueAsBytes(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize Composer metadata", e);
    }
    String etag = sha256(bytes);
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, JSON, etag, lastModified);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, JSON, etag, lastModified);
  }

  static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  static String sha256(String value) {
    return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
