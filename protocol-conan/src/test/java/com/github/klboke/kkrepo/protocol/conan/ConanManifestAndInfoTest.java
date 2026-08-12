package com.github.klboke.kkrepo.protocol.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConanManifestAndInfoTest {
  @Test
  void parsesOfficialManifestAndComputesConanSummaryHash() {
    ConanManifest manifest = ConanManifest.parse(("1710000000\n"
        + "conanfile.py: 0123456789abcdef0123456789abcdef\n")
        .getBytes(StandardCharsets.UTF_8));

    assertEquals(1710000000L, manifest.timestamp());
    assertEquals("0123456789abcdef0123456789abcdef",
        manifest.md5ByPath().get("conanfile.py"));
    assertEquals("d6caaf23d7aee5b6b4725aad2de3b735", manifest.summaryHash());
  }

  @Test
  void rejectsDuplicateOrUnsafeManifestPaths() {
    assertThrows(IllegalArgumentException.class, () -> ConanManifest.parse(("1\n"
        + "same: 0123456789abcdef0123456789abcdef\n"
        + "same: 0123456789abcdef0123456789abcdef\n")
        .getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class, () -> ConanManifest.parse(("1\n"
        + "../escape: 0123456789abcdef0123456789abcdef\n")
        .getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void enforcesManifestConstructorAndWireBounds() {
    String checksum = "0123456789ABCDEF0123456789ABCDEF";
    ConanManifest normalized = new ConanManifest(1, Map.of("nested/file", checksum));
    assertEquals(checksum.toLowerCase(), normalized.md5ByPath().get("nested/file"));
    assertThrows(UnsupportedOperationException.class,
        () -> normalized.md5ByPath().put("other", checksum));

    assertThrows(IllegalArgumentException.class, () -> new ConanManifest(-1, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new ConanManifest(1, null));
    assertThrows(IllegalArgumentException.class,
        () -> new ConanManifest(1, Map.of(ConanManifest.FILE_NAME, checksum)));
    assertThrows(IllegalArgumentException.class,
        () -> new ConanManifest(1, Map.of("file", "not-an-md5")));

    Map<String, String> tooMany = new LinkedHashMap<>();
    for (int index = 0; index <= ConanManifest.MAX_ENTRIES; index++) {
      tooMany.put("file-" + index, checksum);
    }
    assertThrows(IllegalArgumentException.class, () -> new ConanManifest(1, tooMany));

    for (byte[] invalid : new byte[][] {
        null,
        new byte[0],
        new byte[ConanManifest.MAX_BYTES + 1],
        "1".getBytes(StandardCharsets.UTF_8),
        "bad\n".getBytes(StandardCharsets.UTF_8),
        "1\r\n".getBytes(StandardCharsets.UTF_8),
        new byte[] {'1', '\n', 0},
        "1\nmissing separator\n".getBytes(StandardCharsets.UTF_8)
    }) {
      assertThrows(IllegalArgumentException.class, () -> ConanManifest.parse(invalid));
    }

    StringBuilder entries = new StringBuilder("1\n");
    for (int index = 0; index <= ConanManifest.MAX_ENTRIES; index++) {
      entries.append("file-").append(index).append(": ").append(checksum).append('\n');
    }
    assertThrows(IllegalArgumentException.class,
        () -> ConanManifest.parse(entries.toString().getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void projectsOnlyBoundedSearchableConanInfoSections() {
    ConanInfo info = ConanInfo.parse(("""
        [settings]
        os=Linux
        arch=x86_64
        [options]
        shared=True
        [requires]
        zlib/1.3.1
        [env]
        SECRET=ignored
        """).getBytes(StandardCharsets.UTF_8));

    assertEquals("Linux", info.settings().get("os"));
    assertEquals("True", info.options().get("shared"));
    assertEquals("", info.requires().get("zlib/1.3.1"));
    assertEquals(2, info.settings().size());
  }

  @Test
  void acceptsAnEmptySettingsIndependentConanInfo() {
    ConanInfo info = ConanInfo.parse(new byte[0]);

    assertEquals("", info.rawContent());
    assertEquals(0, info.settings().size());
    assertEquals(0, info.options().size());
    assertEquals(0, info.requires().size());
  }

  @Test
  void rejectsMalformedUtf8AndNulBytes() {
    assertThrows(IllegalArgumentException.class, () -> ConanInfo.parse(new byte[] {
        (byte) 0xc3, (byte) 0x28
    }));
    assertThrows(IllegalArgumentException.class, () -> ConanInfo.parse(new byte[] {
        '[', 'x', ']', '\n', 0
    }));
  }

  @Test
  void enforcesConanInfoBoundsAndKeepsProjectionImmutable() {
    ConanInfo value = new ConanInfo(null, null, null, "raw");
    assertEquals(Map.of(), value.settings());
    assertThrows(UnsupportedOperationException.class,
        () -> value.settings().put("os", "Linux"));
    assertThrows(IllegalArgumentException.class,
        () -> new ConanInfo(Map.of(), Map.of(), Map.of(), null));
    assertThrows(IllegalArgumentException.class,
        () -> new ConanInfo(Map.of(), Map.of(), Map.of(), "x".repeat(ConanInfo.MAX_BYTES + 1)));

    assertThrows(IllegalArgumentException.class, () -> ConanInfo.parse(null));
    assertThrows(IllegalArgumentException.class,
        () -> ConanInfo.parse(new byte[ConanInfo.MAX_BYTES + 1]));
    assertThrows(IllegalArgumentException.class,
        () -> ConanInfo.parse("[settings]\r\nos=Linux".getBytes(StandardCharsets.UTF_8)));

    for (String invalid : new String[] {
        "[settings]\n=Linux\n",
        "[settings]\n" + "k".repeat(257) + "=value\n",
        "[settings]\nkey=" + "v".repeat(4097) + "\n",
        "[settings]\nkey=value\nkey=duplicate\n",
        "[settings]\nkey=bad\u007fvalue\n"
    }) {
      assertThrows(IllegalArgumentException.class,
          () -> ConanInfo.parse(invalid.getBytes(StandardCharsets.UTF_8)));
    }

    StringBuilder entries = new StringBuilder("[settings]\n");
    for (int index = 0; index <= 1024; index++) {
      entries.append('k').append(index).append("=v\n");
    }
    assertThrows(IllegalArgumentException.class,
        () -> ConanInfo.parse(entries.toString().getBytes(StandardCharsets.UTF_8)));
  }
}
