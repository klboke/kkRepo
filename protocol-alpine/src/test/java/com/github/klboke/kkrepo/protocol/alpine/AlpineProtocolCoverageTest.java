package com.github.klboke.kkrepo.protocol.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import org.junit.jupiter.api.Test;

class AlpineProtocolCoverageTest {
  @Test
  void exposesHostedProxyAndGroupCapabilities() {
    AlpineRepositoryProtocol protocol = new AlpineRepositoryProtocol();
    assertEquals(RepositoryFormat.ALPINE, protocol.format());
    assertTrue(protocol.capability().hostedRead());
    assertTrue(protocol.capability().hostedWrite());
    assertTrue(protocol.capability().proxyRead());
    assertTrue(protocol.capability().groupRead());
  }

  @Test
  void validatesSignatureEntries() {
    var parsed = AlpineSignature.parseEntryName(".SIGN.RSA256.kkrepo-alpine.rsa.pub");
    assertEquals(AlpineSignature.Type.RSA256, parsed.type());
    assertEquals("kkrepo-alpine.rsa.pub", parsed.keyFilename());
  }
}
