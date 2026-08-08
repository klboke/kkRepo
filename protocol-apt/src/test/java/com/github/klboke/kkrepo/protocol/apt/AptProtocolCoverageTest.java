package com.github.klboke.kkrepo.protocol.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import org.junit.jupiter.api.Test;

class AptProtocolCoverageTest {
  @Test
  void exposesHostedAndProxyWithoutInventingGroup() {
    AptRepositoryProtocol protocol = new AptRepositoryProtocol();
    assertEquals(RepositoryFormat.APT, protocol.format());
    assertTrue(protocol.capability().hostedRead());
    assertTrue(protocol.capability().hostedWrite());
    assertTrue(protocol.capability().proxyRead());
    assertFalse(protocol.capability().groupRead());
    assertTrue(protocol.capability().nexusPathCompatible());
  }
}
