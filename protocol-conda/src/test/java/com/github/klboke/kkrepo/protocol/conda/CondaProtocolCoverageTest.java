package com.github.klboke.kkrepo.protocol.conda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import org.junit.jupiter.api.Test;

class CondaProtocolCoverageTest {
  @Test
  void exposesAllThreeRepositoryCapabilities() {
    CondaRepositoryProtocol protocol = new CondaRepositoryProtocol();
    assertEquals(RepositoryFormat.CONDA, protocol.format());
    assertTrue(protocol.capability().hostedRead());
    assertTrue(protocol.capability().hostedWrite());
    assertTrue(protocol.capability().proxyRead());
    assertTrue(protocol.capability().groupRead());
    assertTrue(protocol.capability().nexusPathCompatible());
  }
}
