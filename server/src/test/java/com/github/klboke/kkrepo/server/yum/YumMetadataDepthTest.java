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
    assertEquals("d1:6665646f72612d34352f", YumMetadataDepth.rebuildScope(1, "fedora-45/Packages/demo.rpm"));
    assertEquals("d1:6665646f72612d34352f", YumMetadataDepth.rebuildScope(1, "fedora-45/repodata/repomd.xml"));
    assertEquals("d0:", YumMetadataDepth.rebuildScope(0, "Packages/demo.rpm"));
    assertEquals("", YumMetadataDepth.rebuildScope(1, "demo.rpm"));
    assertEquals("", YumMetadataDepth.rebuildScope(1, "repodata/repomd.xml"));
    assertEquals("", YumMetadataDepth.rebuildScope(1, "a".repeat(512) + "/demo.rpm"));
    assertEquals("fedora-45/", YumMetadataDepth.scopedRoot(1, "d1:6665646f72612d34352f"));
    assertEquals("", YumMetadataDepth.scopedRoot(0, "d0:"));
    assertNull(YumMetadataDepth.scopedRoot(1, ""));
    assertNull(YumMetadataDepth.scopedRoot(1, null));
    assertNull(YumMetadataDepth.scopedRoot(1, "d0:"));
    assertNull(YumMetadataDepth.scopedRoot(1, "d1:6665646f72612d34352f5061636b616765732f"));
    assertNull(YumMetadataDepth.scopedRoot(1, "d1:not-hex"));
    var paths = java.util.List.of("Fedora-45/", "fedora-45/", "fédora-45/", "发行版/");
    var scopes = paths.stream().map(path -> YumMetadataDepth.rebuildScope(1, path + "demo.rpm")).toList();
    assertEquals(paths.size(), new java.util.HashSet<>(scopes).size());
    for (int i = 0; i < paths.size(); i++) {
      assertEquals(paths.get(i), YumMetadataDepth.scopedRoot(1, scopes.get(i)));
    }
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
