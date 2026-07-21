package com.github.klboke.kkrepo.protocol.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import org.junit.jupiter.api.Test;

class AnsibleGalaxyProtocolCoverageTest {
  private final AnsibleGalaxyPathParser parser = new AnsibleGalaxyPathParser();

  @Test
  void exposesTheHostedProxyGroupProtocolCapability() {
    AnsibleGalaxyRepositoryProtocol protocol = new AnsibleGalaxyRepositoryProtocol();
    assertEquals(RepositoryFormat.ANSIBLEGALAXY, protocol.format());
    assertTrue(protocol.capability().hostedRead());
    assertTrue(protocol.capability().hostedWrite());
    assertTrue(protocol.capability().proxyRead());
    assertTrue(protocol.capability().groupRead());
  }

  @Test
  void validatesNamesAndNullableCoordinates() {
    assertNull(AnsibleGalaxyNames.key(null));
    assertEquals("community", AnsibleGalaxyNames.key("COMMUNITY"));
    assertThrows(IllegalArgumentException.class,
        () -> AnsibleGalaxyNames.requireNamespace("Bad"));
    assertThrows(IllegalArgumentException.class,
        () -> AnsibleGalaxyNames.requireCollection("bad__name"));
    assertNull(parser.parse("api/v3/collections/acme/tools/").coordinate());
  }

  @Test
  void rejectsMalformedRoutesQueriesAndPercentEncodings() {
    assertEquals(AnsibleGalaxyPath.Kind.UNKNOWN,
        parser.parse("api/v3/imports/collections/not-a-uuid/").kind());
    assertEquals(AnsibleGalaxyPath.Kind.UNKNOWN,
        parser.parse(AnsibleGalaxyPathParser.ARTIFACT_BASE + "bad.tar.gz").kind());
    assertEquals(AnsibleGalaxyPath.Kind.UNKNOWN,
        parser.parse("api/v3/collections/Bad/tools/").kind());
    assertEquals(AnsibleGalaxyPath.Kind.UNKNOWN,
        parser.parse("api/v3/collections/acme/tools/other/").kind());
    assertThrows(IllegalArgumentException.class, () -> parser.parse(
        "api/v3/collections/acme/tools/versions/", "other=1"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse(
        "api/v3/collections/acme/tools/versions/", "limit=0"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse(
        "api/v3/collections/acme/tools/versions/", "offset=bad"));

    for (String path : new String[] {
        "bad?query", "%", "%GG", "%C3%28", "%2f", "bad\\path", "bad\u0001path"
    }) {
      assertEquals(AnsibleGalaxyPath.Kind.UNKNOWN, parser.parse(path).kind(), path);
    }
    assertEquals(AnsibleGalaxyPath.Kind.UNKNOWN, parser.parse("%41").kind());
    assertEquals(AnsibleGalaxyPath.Kind.UNKNOWN, parser.parse("%4a").kind());
    assertFalse(AnsibleGalaxyPathParser.isArtifactFilename("x".repeat(256)));
    assertThrows(IllegalArgumentException.class,
        () -> AnsibleGalaxyPathParser.canonicalFilename("Bad", "tools", "1.0.0"));
  }

  @Test
  void comparesEquivalentPrereleaseIdentifiersDeterministically() {
    assertEquals(0, AnsibleGalaxyVersions.compare("1.0.0-alpha", "1.0.0-alpha"));
    assertTrue(AnsibleGalaxyVersions.compare("1.0.0-alpha.1", "1.0.0-alpha.beta") < 0);
    assertThrows(IllegalArgumentException.class,
        () -> AnsibleGalaxyVersions.sortDescending(null));
  }
}
