package com.github.klboke.kkrepo.protocol.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnsibleGalaxyPathParserTest {
  private final AnsibleGalaxyPathParser parser = new AnsibleGalaxyPathParser();

  @Test
  void parsesDiscoveryAndClientRoutes() {
    assertEquals(AnsibleGalaxyPath.Kind.DISCOVERY, parser.parse("").kind());
    assertEquals(AnsibleGalaxyPath.Kind.DISCOVERY, parser.parse("api/").kind());
    assertEquals(AnsibleGalaxyPath.Kind.PUBLISH,
        parser.parse("api/v3/artifacts/collections/").kind());
    assertEquals(AnsibleGalaxyPath.Kind.IMPORT_TASK,
        parser.parse("api/v3/imports/collections/0dfd1f0d-fa14-4caa-b928-be3ec7c8650e/").kind());
  }

  @Test
  void parsesShortAndNexusLongMetadataAliases() {
    AnsibleGalaxyPath shortPath = parser.parse(
        "api/v3/collections/community/general/versions/8.6.1/");
    assertEquals(AnsibleGalaxyPath.Kind.VERSION_DETAIL, shortPath.kind());
    assertEquals("community.general:8.6.1", shortPath.coordinate());
    assertFalse(shortPath.longAlias());

    AnsibleGalaxyPath longPath = parser.parse(
        "api/v3/plugin/ansible/content/published/collections/index/community/general/versions/");
    assertEquals(AnsibleGalaxyPath.Kind.VERSION_LIST, longPath.kind());
    assertTrue(longPath.longAlias());
  }

  @Test
  void parsesArtifactAndPagination() {
    AnsibleGalaxyPath artifact = parser.parse(
        "api/v3/plugin/ansible/content/published/collections/artifacts/community-general-8.6.1.tar.gz");
    assertEquals(AnsibleGalaxyPath.Kind.ARTIFACT, artifact.kind());
    assertEquals("community-general-8.6.1.tar.gz", artifact.filename());

    AnsibleGalaxyRequestTarget target = parser.parse(
        "api/v3/collections/community/general/versions/", "limit=25&offset=50");
    assertEquals(25, target.limit());
    assertEquals(50, target.offset());
  }

  @Test
  void rejectsUnsafeAndAmbiguousInput() {
    assertEquals(AnsibleGalaxyPath.Kind.UNKNOWN,
        parser.parse("api/v3/collections/community/%252fetc/").kind());
    assertEquals(AnsibleGalaxyPath.Kind.UNKNOWN,
        parser.parse("api/v3/collections/community/general/../secret/").kind());
    assertThrows(IllegalArgumentException.class, () -> parser.parse(
        "api/v3/collections/community/general/versions/", "limit=1&limit=2"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse(
        "api/v3/collections/community/general/", "limit=10"));
  }
}
