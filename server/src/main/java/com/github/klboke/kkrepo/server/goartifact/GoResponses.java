package com.github.klboke.kkrepo.server.goartifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

final class GoResponses {
  private GoResponses() {
  }

  static MavenResponse text(String body, Instant lastModified, boolean headOnly) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, "text/plain", null, lastModified);
    }
    return MavenResponse.ok(
        new ByteArrayInputStream(bytes), bytes.length, "text/plain", null, lastModified);
  }

  static MavenResponse info(
      ObjectMapper objectMapper,
      String version,
      Instant time,
      Instant lastModified,
      boolean headOnly) {
    return bytes(infoBytes(objectMapper, version, time), "text/plain", lastModified, headOnly);
  }

  static byte[] infoBytes(ObjectMapper objectMapper, String version, Instant time) {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("Version", version);
    // GOPROXY requires an RFC 3339 JSON string regardless of ObjectMapper timestamp settings.
    info.put("Time", time.toString());
    try {
      return objectMapper.writeValueAsBytes(info);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Unable to serialize Go module version info", error);
    }
  }

  static MavenResponse bytes(
      byte[] body,
      String contentType,
      Instant lastModified,
      boolean headOnly) {
    String etag = sha256(body);
    if (headOnly) {
      return MavenResponse.noBody(200, body.length, contentType, etag, lastModified);
    }
    return MavenResponse.ok(
        new ByteArrayInputStream(body), body.length, contentType, etag, lastModified);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 digest is unavailable", error);
    }
  }
}
