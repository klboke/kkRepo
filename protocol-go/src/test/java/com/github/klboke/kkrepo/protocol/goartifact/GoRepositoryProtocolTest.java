package com.github.klboke.kkrepo.protocol.goartifact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.core.ProtocolCapability;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import org.junit.jupiter.api.Test;

class GoRepositoryProtocolTest {
  @Test
  void describesSupportedRepositoryModes() {
    GoRepositoryProtocol protocol = new GoRepositoryProtocol();

    assertEquals(RepositoryFormat.GO, protocol.format());
    assertEquals(
        new ProtocolCapability(false, false, true, true, true),
        protocol.capability());
  }
}
