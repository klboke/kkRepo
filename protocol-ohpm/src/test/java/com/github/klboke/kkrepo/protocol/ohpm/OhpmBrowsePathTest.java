package com.github.klboke.kkrepo.protocol.ohpm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OhpmBrowsePathTest {
  @Test
  void mapsPackageVersionAndFilesWithoutDecodingTwice() {
    assertEquals("demo", OhpmBrowsePath.parse("demo").storagePath());
    assertEquals("@scope/demo", OhpmBrowsePath.parse("@scope/demo").storagePath());
    assertEquals("@scope/demo/-/@scope/demo-1.0.0.har",
        OhpmBrowsePath.parse("@scope/demo/1.0.0").storagePath());
    assertEquals("demo/-/demo-1.0.0.hsp", OhpmBrowsePath.parse("demo/1.0.0/demo-1.0.0.hsp").storagePath());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "@scope", "@scope%2fdemo", "../demo", "demo/", "demo/invalid",
      "demo/1.0.0/other.har", "demo/1.0.0/demo-1.0.0.har/extra", "@scope/demo/-/@scope/demo-1.0.0.har"})
  void rejectsAmbiguousOrNonPackagePaths(String path) {
    assertThrows(IllegalArgumentException.class, () -> OhpmBrowsePath.parse(path));
  }
}
