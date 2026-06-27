package com.github.klboke.kkrepo.server.cargo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class CargoResponses {
  private CargoResponses() {
  }

  static MavenResponse json(ObjectMapper objectMapper, Object value, int status, boolean headOnly) {
    return json(objectMapper, value, status, null, null, headOnly);
  }

  static MavenResponse jsonWithBodyEtag(ObjectMapper objectMapper, Object value, int status, boolean headOnly) {
    byte[] bytes = writeJson(objectMapper, value);
    return json(bytes, status, sha256(bytes), null, headOnly);
  }

  static MavenResponse json(
      ObjectMapper objectMapper,
      Object value,
      int status,
      String etag,
      Instant lastModified,
      boolean headOnly) {
    byte[] bytes = writeJson(objectMapper, value);
    return json(bytes, status, etag, lastModified, headOnly);
  }

  private static MavenResponse json(
      byte[] bytes, int status, String etag, Instant lastModified, boolean headOnly) {
    if (headOnly) {
      return MavenResponse.noBody(status, bytes.length, "application/json", etag, lastModified);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/json", etag, lastModified)
        .withStatus(status);
  }

  static MavenResponse text(String body, String etag, Instant lastModified, boolean headOnly) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    String effectiveEtag = etag == null ? sha256(bytes) : etag;
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, "text/plain", effectiveEtag, lastModified);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "text/plain", effectiveEtag, lastModified);
  }

  static MavenResponse unsupportedSearch(ObjectMapper objectMapper, boolean headOnly) {
    return jsonWithBodyEtag(objectMapper, errorBody("Cargo search is not supported by this registry"), 501, headOnly);
  }

  public static Map<String, Object> errorBody(String detail) {
    return Map.of("errors", List.of(Map.of("detail", detail == null ? "Cargo request failed" : detail)));
  }

  static byte[] writeJson(ObjectMapper objectMapper, Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize Cargo JSON response", e);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 digest is not available", e);
    }
  }
}
