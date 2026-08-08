package com.github.klboke.kkrepo.protocol.conda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CondaPathParserTest {
  private final CondaPathParser parser = new CondaPathParser();

  @Test
  void parsesRootAndNestedChannelMetadata() {
    assertEquals(CondaPath.Kind.ROOT, parser.parse("").kind());
    CondaPath root = parser.parse("linux-64/repodata.json.zst");
    assertEquals(CondaPath.Kind.REPODATA, root.kind());
    assertEquals("", root.channel());
    assertEquals("linux-64", root.subdir());
    assertEquals(CondaPath.Encoding.ZSTD, root.encoding());

    CondaPath nested = parser.parse("team-a/release/noarch/repodata.json.bz2");
    assertEquals(CondaPath.Kind.REPODATA, nested.kind());
    assertEquals("team-a/release", nested.channel());
    assertEquals("noarch", nested.subdir());
    assertEquals("team-a/release/noarch/repodata.json.bz2", nested.canonicalPath());
  }

  @Test
  void parsesPackagesAndChannelRootDocuments() {
    CondaPath modern = parser.parse("linux-64/zlib-1.3.1-h4ab18f5_1.conda");
    assertTrue(modern.packageFile());
    assertEquals("zlib-1.3.1-h4ab18f5_1.conda", modern.filename());
    CondaPath legacy = parser.parse("label/dev/noarch/demo-1.0-py_0.tar.bz2");
    assertTrue(legacy.packageFile());
    assertEquals("label/dev", legacy.channel());
    assertEquals(CondaPath.Kind.REPODATA,
        parser.parse("label/Release_Candidate/linux-64/repodata.json").kind());
    assertEquals(CondaPath.Kind.CHANNELDATA,
        parser.parse("label/dev/channeldata.json").kind());
  }

  @Test
  void recognizesOptionalFastPathDocuments() {
    assertEquals(CondaPath.Kind.CURRENT_REPODATA,
        parser.parse("linux-64/current_repodata.json.zst").kind());
    assertEquals(CondaPath.Kind.SHARDED_REPODATA,
        parser.parse("linux-64/repodata_shards.msgpack.zst").kind());
  }

  @Test
  void rejectsTraversalDoubleEncodingAndInvalidCase() {
    for (String path : new String[] {
        "../linux-64/repodata.json",
        "team//linux-64/repodata.json",
        "Team/linux-64/repodata.json",
        "team/linux/repodata.json",
        "team/linux_64/repodata.json",
        "label/Release Candidate/linux-64/repodata.json",
        "team/%252fetc/noarch/repodata.json",
        "linux-64/not-a-package.zip"
    }) {
      assertEquals(CondaPath.Kind.UNKNOWN, parser.parse(path).kind(), path);
    }
    assertFalse(CondaPathParser.isPackage("../../demo-1.0-0.conda"));
    assertTrue(CondaPathParser.isPackage("a".repeat(205) + ".conda"));
    assertFalse(CondaPathParser.isPackage("a".repeat(206) + ".conda"));
  }
}
