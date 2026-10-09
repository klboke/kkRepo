package com.github.klboke.kkrepo.server.yum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class YumMetadataDepthTest {
  @Test
  void explicitDepthZeroOverridesImportedDepthAndMissingSettingsDefaultToRoot() {
    assertEquals(0, YumMetadataDepth.read(Map.of()));
    assertEquals(0, YumMetadataDepth.read(null));
    assertEquals(0, YumMetadataDepth.read(Map.of("yum", Map.of("repodataDepth", 0),
        "sourceRepository", Map.of("yum", Map.of("repodataDepth", 2)))));
    assertThrows(IllegalArgumentException.class,
        () -> YumMetadataDepth.read(Map.of("yum", Map.of("repodataDepth", -1))));
  }

  @Test
  void scopesCoalesceDeeperPathsAndRetainFullRepairForLegacyOrOversizedRoots() {
    assertEquals("1:fedora-45/", YumMetadataDepth.rebuildScope(1, "fedora-45/Packages/demo.rpm"));
    assertEquals("1:fedora-45/", YumMetadataDepth.rebuildScope(1, "fedora-45/repodata/repomd.xml"));
    assertEquals("0:", YumMetadataDepth.rebuildScope(0, "Packages/demo.rpm"));
    assertEquals("", YumMetadataDepth.rebuildScope(1, "demo.rpm"));
    assertEquals("", YumMetadataDepth.rebuildScope(1, "repodata/repomd.xml"));
    assertEquals("", YumMetadataDepth.rebuildScope(1, "a".repeat(512) + "/demo.rpm"));
    assertEquals("fedora-45/", YumMetadataDepth.scopedRoot(1, "1:fedora-45/"));
    assertEquals("", YumMetadataDepth.scopedRoot(0, "0:"));
    assertNull(YumMetadataDepth.scopedRoot(1, ""));
    assertNull(YumMetadataDepth.scopedRoot(1, null));
    assertNull(YumMetadataDepth.scopedRoot(1, "0:"));
    assertNull(YumMetadataDepth.scopedRoot(1, "1:fedora-45/Packages/"));
  }

  @Test
  void rootsFollowConfiguredDepthRatherThanImmediateRpmParent() {
    String path = "fedora-45/x86_64/Packages/demo.rpm";
    assertEquals("", YumMetadataDepth.root(path, 0));
    assertEquals("fedora-45/", YumMetadataDepth.root(path, 1));
    assertEquals("fedora-45/x86_64/", YumMetadataDepth.root(path, 2));
    assertNull(YumMetadataDepth.root(path, 4));
    assertEquals("fedora-45/x86_64/", YumMetadataDepth.metadataRoot("fedora-45/x86_64/repodata/repomd.xml"));
    assertNull(YumMetadataDepth.metadataRoot(path));
  }
}
