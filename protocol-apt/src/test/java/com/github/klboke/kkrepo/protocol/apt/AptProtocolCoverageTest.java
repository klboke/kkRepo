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

  @Test
  void classifiesPlainMetadataUnknownContentAndImmutablePaths() {
    assertEquals(AptMediaTypes.TEXT, AptMediaTypes.forPath("Packages"));
    assertEquals(AptMediaTypes.TEXT, AptMediaTypes.forPath("Sources"));
    assertEquals(AptMediaTypes.BINARY, AptMediaTypes.forPath("unknown.bin"));
    AptPathParser parser = new AptPathParser();
    assertTrue(parser.parse(
        "dists/stable/main/binary-amd64/by-hash/SHA256/" + "a".repeat(64)).immutable());
    assertTrue(parser.parse("pool/d/demo/demo_1.0_amd64.deb").immutable());
    assertFalse(parser.parse("dists/stable/Release").immutable());
  }
}
