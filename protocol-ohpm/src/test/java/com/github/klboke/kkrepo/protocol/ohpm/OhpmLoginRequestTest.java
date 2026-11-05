package com.github.klboke.kkrepo.protocol.ohpm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OhpmLoginRequestTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
  private static KeyPair key;

  @BeforeAll
  static void key() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    key = generator.generateKeyPair();
  }

  @Test
  void verifiesTheClientV1SignatureAndDetectsFieldTampering() throws Exception {
    ObjectNode body = body();
    OhpmLoginRequest request = read(body);
    assertTrue(request.verify(key.getPublic()));
    assertEquals("v1-publisher-" + NOW.toEpochMilli() + "-0123456789ab4def8123456789abcdef",
        new String(request.signedBytes(), StandardCharsets.UTF_8));
    body.put("publishId", "another");
    assertFalse(read(body).verify(key.getPublic()));
    body = body();
    body.put("timestamp", NOW.toEpochMilli() + 1);
    assertFalse(read(body).verify(key.getPublic()));
    body = body();
    body.put("nonce", "1123456789ab4def8123456789abcdef");
    assertFalse(read(body).verify(key.getPublic()));
  }

  @Test
  void boundsPastFutureAndExtremeTimestamps() throws Exception {
    var request = read(body());
    Duration tolerance = Duration.ofMinutes(5);
    assertTrue(request.timely(NOW.minusSeconds(300), tolerance));
    assertTrue(request.timely(NOW.plusSeconds(300), tolerance));
    assertFalse(request.timely(NOW.minusSeconds(301), tolerance));
    assertFalse(request.timely(NOW.plusSeconds(301), tolerance));
    assertFalse(read(body().put("timestamp", Long.MAX_VALUE)).timely(NOW, tolerance));
    assertFalse(read(body().put("timestamp", Long.MIN_VALUE)).timely(NOW, tolerance));
  }

  @Test
  void rejectsAmbiguousVersionsTimestampsNoncesAndSignatureEncodings() throws Exception {
    for (String version : new String[] {"1", "vv1", "v2", ""}) {
      assertThrows(IllegalArgumentException.class, () -> read(body().put("version", version)));
    }
    assertThrows(IllegalArgumentException.class, () -> read(body().put("timestamp", 1.5)));
    assertThrows(IllegalArgumentException.class, () -> read(body().put("timestamp", "123")));
    assertThrows(IllegalArgumentException.class, () -> read(body().put("nonce", "short")));
    assertThrows(IllegalArgumentException.class, () -> read(body().put("publishId", "publisher\n")));
    assertThrows(IllegalArgumentException.class, () -> read(body().put("signature", "invalid")));
    ObjectNode missing = body();
    missing.remove("nonce");
    assertThrows(IllegalArgumentException.class, () -> read(missing));
  }

  @Test
  void rejectsDuplicateFieldsTrailingDocumentsAndOversizedBodies() throws Exception {
    String body = JSON.writeValueAsString(body());
    assertThrows(IOException.class, () -> OhpmLoginRequest.read(input(body.replace("\"version\":\"v1\"",
        "\"version\":\"v1\",\"version\":\"v1\""))));
    assertThrows(IOException.class, () -> OhpmLoginRequest.read(input(body + "{}")));
    assertEquals(413, assertThrows(OhpmException.class,
        () -> OhpmLoginRequest.read(input(" ".repeat(4097)))).status());
  }

  private static ObjectNode body() throws Exception {
    String nonce = "0123456789ab4def8123456789abcdef";
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(key.getPrivate());
    signer.update(("v1-publisher-" + NOW.toEpochMilli() + "-" + nonce).getBytes(StandardCharsets.UTF_8));
    return JSON.createObjectNode().put("version", "v1").put("publishId", "publisher")
        .put("timestamp", NOW.toEpochMilli()).put("nonce", nonce)
        .put("signature", Base64.getEncoder().encodeToString(signer.sign()));
  }

  private static OhpmLoginRequest read(ObjectNode body) throws Exception {
    return OhpmLoginRequest.read(new ByteArrayInputStream(JSON.writeValueAsBytes(body)));
  }

  private static ByteArrayInputStream input(String body) {
    return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
  }
}
