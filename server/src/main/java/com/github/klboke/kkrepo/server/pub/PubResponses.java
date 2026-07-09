package com.github.klboke.kkrepo.server.pub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.pub.PubContentTypes;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

public final class PubResponses {
  private PubResponses() {
  }

  static MavenResponse json(ObjectMapper objectMapper, Object value, int status, boolean headOnly) {
    return json(objectMapper, value, status, null, null, headOnly);
  }

  static MavenResponse json(
      ObjectMapper objectMapper,
      Object value,
      int status,
      String etag,
      Instant lastModified,
      boolean headOnly) {
    return json(objectMapper, value, status, etag, lastModified, PubContentTypes.JSON, headOnly);
  }

  static MavenResponse json(
      ObjectMapper objectMapper,
      Object value,
      int status,
      String etag,
      Instant lastModified,
      String contentType,
      boolean headOnly) {
    byte[] bytes = writeJson(objectMapper, value);
    String effectiveEtag = etag == null ? sha256(bytes) : etag;
    if (headOnly) {
      return MavenResponse.noBody(status, bytes.length, contentType, effectiveEtag, lastModified);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
        contentType, effectiveEtag, lastModified).withStatus(status);
  }

  static MavenResponse jsonBytes(byte[] bytes, int status, String etag, Instant lastModified, boolean headOnly) {
    String effectiveEtag = etag == null ? sha256(bytes) : etag;
    if (headOnly) {
      return MavenResponse.noBody(status, bytes.length, PubContentTypes.JSON, effectiveEtag, lastModified);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
        PubContentTypes.JSON, effectiveEtag, lastModified).withStatus(status);
  }

  public static Map<String, Object> errorBody(String message) {
    return errorBody("pub_error", message);
  }

  public static Map<String, Object> errorBody(String code, String message) {
    return Map.of("error", Map.of(
        "code", code == null || code.isBlank() ? "pub_error" : code,
        "message", message == null ? "Pub request failed" : message));
  }

  static byte[] writeJson(ObjectMapper objectMapper, Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize Pub JSON response", e);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 digest is not available", e);
    }
  }
}
