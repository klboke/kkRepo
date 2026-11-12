package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NexusStagingIdCodecTest {
  private final NexusStagingIdCodec codec = new NexusStagingIdCodec();

  @Test
  void roundTripsStagedAssets() {
    String id = codec.encodeAsset(7, 42);
    assertTrue(codec.isStagingId(id));
    assertEquals(new NexusStagingIdCodec.DecodedAsset(7, 42), codec.decodeAsset(id));
    assertThrows(NexusStagingIdCodec.InvalidStagingIdException.class,
        () -> codec.decodeAsset("not-an-id"));
    assertFalse(codec.isStagingId("not-an-id"));
  }

  @Test
  void roundTripsStagedComponents() {
    String id = codec.encodeComponent(7, 3, "acme", "demo", "1.0.0", "raw");
    assertTrue(codec.isStagingId(id));
    var decoded = codec.decodeComponent(id);
    assertEquals(7, decoded.sessionId());
    assertEquals(3, decoded.repositoryId());
    assertEquals(NexusStagingIdCodec.componentIdentity("acme", "demo", "1.0.0", "raw"),
        decoded.identity());
  }
}
