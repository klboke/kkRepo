package com.github.klboke.kkrepo.protocol.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConanManifestLimitsTest {
  @Test
  void accepts7922EntriesOnlyWhenRaisedAndReportsTheFirstObservedExcess() {
    byte[] sdk = manifest(7922, 20);
    assertTrue(sdk.length < ConanManifest.MAX_BYTES);
    assertEquals("Conan manifest entry limit exceeded: limit=4096, observed=4097",
        assertThrows(IllegalArgumentException.class, () -> ConanManifest.parse(sdk)).getMessage());
    assertEquals(7922, ConanManifest.parse(sdk,
        new ConanManifestLimits(7922, ConanManifest.MAX_BYTES)).md5ByPath().size());
    assertEquals("Conan manifest entry limit exceeded: limit=7921, observed=7922",
        assertThrows(IllegalArgumentException.class, () -> ConanManifest.parse(sdk,
            new ConanManifestLimits(7921, ConanManifest.MAX_BYTES))).getMessage());
  }

  @Test
  void byteLimitIsIndependentAndInclusiveAndCountsUtf8Bytes() {
    byte[] sdk = manifest(2000, 600);
    assertTrue(sdk.length > ConanManifest.MAX_BYTES);
    assertEquals("Conan manifest size limit exceeded: limit=1048576 bytes, observed="
            + sdk.length + " bytes",
        assertThrows(IllegalArgumentException.class, () -> ConanManifest.parse(sdk)).getMessage());
    assertEquals(2000, ConanManifest.parse(sdk,
        new ConanManifestLimits(4096, sdk.length)).md5ByPath().size());
    assertThrows(IllegalArgumentException.class, () -> ConanManifest.parse(sdk,
        new ConanManifestLimits(4096, sdk.length - 1)));
    byte[] unicode = "1\n目录/a: d41d8cd98f00b204e9800998ecf8427e\n".getBytes(StandardCharsets.UTF_8);
    assertEquals(1, ConanManifest.parse(unicode,
        new ConanManifestLimits(1, unicode.length)).md5ByPath().size());
    assertThrows(IllegalArgumentException.class, () -> ConanManifest.parse(unicode,
        new ConanManifestLimits(1, unicode.length - 1)));
  }

  @Test
  void increasedLimitsStillRejectUnsafePathsDuplicatesAndBadChecksums() {
    var limits = new ConanManifestLimits(50000, 16777216);
    for (String entries : new String[] {
        "../outside: d41d8cd98f00b204e9800998ecf8427e\n",
        "file: invalid\n",
        "file: d41d8cd98f00b204e9800998ecf8427e\nfile: d41d8cd98f00b204e9800998ecf8427e\n"}) {
      assertThrows(IllegalArgumentException.class,
          () -> ConanManifest.parse(("1\n" + entries).getBytes(StandardCharsets.UTF_8), limits));
    }
    assertThrows(IllegalArgumentException.class, () -> new ConanManifestLimits(-1, 100));
    assertThrows(IllegalArgumentException.class, () -> new ConanManifestLimits(1, 0));
    assertThrows(IllegalArgumentException.class, () -> new ConanManifestLimits(1, Integer.MAX_VALUE));
  }

  private static byte[] manifest(int count, int padding) {
    StringBuilder text = new StringBuilder("1\n");
    for (int i = 0; i < count; i++) {
      text.append("include/").append("x".repeat(padding)).append(i)
          .append(": d41d8cd98f00b204e9800998ecf8427e\n");
    }
    return text.toString().getBytes(StandardCharsets.UTF_8);
  }
}
