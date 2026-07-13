package com.github.klboke.kkrepo.server.goartifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GoPathTest {
  @Test
  void parsesLatestListAndVersionedPaths() {
    GoPath latest = GoPath.parse("/example.com/acme/demo/@latest");
    GoPath list = GoPath.parse("example.com/acme/demo/@v/list");
    GoPath info = GoPath.parse("example.com/acme/demo/@v/v1.2.3.info");
    GoPath module = GoPath.parse("example.com/acme/demo/@v/v1.2.3.mod");
    GoPath archive = GoPath.parse("example.com/acme/demo/@v/v1.2.3.zip");

    assertEquals(GoAssetKind.LATEST, latest.kind());
    assertEquals(GoAssetKind.LIST, list.kind());
    assertEquals("v1.2.3", info.version());
    assertEquals(GoAssetKind.MODULE, module.kind());
    assertEquals("v1.2.3.zip", archive.fileName());
    assertTrue(archive.hasComponent());
    assertFalse(list.hasComponent());
  }

  @Test
  void exposesProtocolContentTypesAndMetadataKinds() {
    assertEquals("application/zip",
        GoPath.parse("example.com/demo/@v/v1.0.0.zip").contentType());
    assertEquals("application/json",
        GoPath.parse("example.com/demo/@v/list").contentType());
    assertEquals("text/plain",
        GoPath.parse("example.com/demo/@v/v1.0.0.mod").contentType());
    assertFalse(GoPath.parse("example.com/demo/@v/v1.0.0.zip").metadata());
    assertTrue(GoPath.parse("example.com/demo/@latest").metadata());
  }

  @Test
  void rejectsMalformedAndUnsupportedPaths() {
    assertThrows(IllegalArgumentException.class, () -> GoPath.parse(null));
    assertThrows(IllegalArgumentException.class, () -> GoPath.parse("example.com/demo/"));
    assertThrows(IllegalArgumentException.class, () -> GoPath.parse("example.com/demo"));
    assertThrows(IllegalArgumentException.class,
        () -> GoPath.parse("example.com/demo/@v/version"));
    assertThrows(IllegalArgumentException.class,
        () -> GoPath.parse("example.com/demo/@v/v1.0.0.txt"));
    assertThrows(IllegalArgumentException.class,
        () -> GoPath.parse("example.com/@v/demo/@v/v1.0.0.mod"));
  }
}
