package com.github.klboke.kkrepo.protocol.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
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
}
