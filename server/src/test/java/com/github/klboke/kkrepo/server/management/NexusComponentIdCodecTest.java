package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NexusComponentIdCodecTest {
  @Test
  void roundTripsLiveComponentIdentifiers() {
    NexusComponentIdCodec codec = new NexusComponentIdCodec();
    String id = codec.encode("raw-hosted", 42);
    assertEquals(new NexusComponentIdCodec.Decoded("raw-hosted", 42), codec.decode(id));
    assertThrows(NexusComponentIdCodec.InvalidComponentIdException.class,
        () -> codec.decode("bad"));
  }
}
