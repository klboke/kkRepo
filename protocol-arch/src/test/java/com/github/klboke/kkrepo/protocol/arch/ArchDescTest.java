package com.github.klboke.kkrepo.protocol.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArchDescTest {
  @Test
  void rendersAndParsesCanonicalDesc() {
    ArchDesc desc = new ArchDesc(
        "demo-1.0.0-1-x86_64.pkg.tar.zst", "demo", "demo", "1.0.0-1", "Demo package",
        1234L, 5678L, "0123456789abcdef0123456789abcdef", "a".repeat(64), null,
        "https://example.test/demo", List.of("MIT"), "x86_64", 1700000000L, "Demo Packager",
        List.of("glibc"), List.of(), List.of("cmake"), List.of(), List.of(), List.of(),
        List.of(), List.of("tools"));
    String rendered = desc.render();
    assertTrue(rendered.contains("%FILENAME%\ndemo-1.0.0-1-x86_64.pkg.tar.zst\n"));
    assertTrue(rendered.contains("%DEPENDS%\nglibc\n"));
    assertEquals(desc, ArchDesc.parse(rendered));
  }

  @Test
  void ordersEpochAndPkgrel() {
    assertTrue(ArchVersions.compare("1:1.0-1", "2.0-1") > 0);
    assertTrue(ArchVersions.compare("1.0-2", "1.0-10") < 0);
    assertTrue(ArchVersions.compare("2.0", "1.9.9") > 0);
  }

  @Test
  void comparatorIsAntisymmetricForEmptyTokens() {
    String[] values = {"1.0", "1", "1..2", "1-2", "1+2", "1_2", ".1", "1."};
    for (String left : values) {
      for (String right : values) {
        int forward = Integer.signum(ArchVersions.compare(left, right));
        int reverse = Integer.signum(ArchVersions.compare(right, left));
        assertEquals(-forward, reverse, left + " vs " + right);
      }
    }
  }
}
