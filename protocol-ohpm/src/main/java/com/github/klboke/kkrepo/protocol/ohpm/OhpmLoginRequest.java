package com.github.klboke.kkrepo.protocol.ohpm;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

public record OhpmLoginRequest(String publishId, long timestamp, String nonce, String signature) {
  private static final int MAX_BYTES = 4096;
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
          .maxStringLength(2048).maxTokenCount(32).maxNumberLength(20).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public OhpmLoginRequest {
    if (publishId == null || !publishId.matches("[A-Za-z0-9_-]{1,128}")
        || nonce == null || !nonce.matches("[a-fA-F0-9]{32}")
        || signature == null || signature.length() < 344 || signature.length() > 1368) {
      throw new IllegalArgumentException("Invalid OHPM login fields");
    }
    byte[] bytes = Base64.getDecoder().decode(signature);
    if (!Base64.getEncoder().encodeToString(bytes).equals(signature)) {
      throw new IllegalArgumentException("Invalid OHPM signature encoding");
    }
  }

  public static OhpmLoginRequest read(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) throw new OhpmException(413, "OHPM login request exceeds size limit");
    JsonNode root = JSON.readTree(bytes);
    if (root == null || !root.isObject() || root.size() != 5
        || !"v1".equals(root.path("version").textValue())
        || !root.path("timestamp").isIntegralNumber() || !root.path("timestamp").canConvertToLong()) {
      throw new IllegalArgumentException("Invalid OHPM login request");
    }
    return new OhpmLoginRequest(root.path("publishId").textValue(), root.path("timestamp").longValue(),
        root.path("nonce").textValue(), root.path("signature").textValue());
  }

  public byte[] signedBytes() {
    return ("v1-" + publishId + "-" + timestamp + "-" + nonce).getBytes(StandardCharsets.UTF_8);
  }

  public boolean timely(Instant now, Duration tolerance) {
    Instant submitted = Instant.ofEpochMilli(timestamp);
    return !submitted.isBefore(now.minus(tolerance)) && !submitted.isAfter(now.plus(tolerance));
  }

  public boolean verify(PublicKey key) {
    try {
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key);
      verifier.update(signedBytes());
      return verifier.verify(Base64.getDecoder().decode(signature));
    } catch (GeneralSecurityException error) {
      return false;
    }
  }
}
