package com.github.klboke.kkrepo.protocol.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DebianVersionsTest {
  @Test
  void followsEpochUpstreamRevisionAndTildeOrdering() {
    assertLess("1.0~rc1-1", "1.0-1");
    assertLess("1.0~~", "1.0~");
    assertLess("1.0-1", "1.0-2");
    assertLess("2.0", "1:1.0");
    assertLess("1.0", "1.0+git1");
    assertLess("1.0-1~bpo12+1", "1.0-1");
    assertEquals(0, DebianVersions.compare("1.0", "1.0-0"));
    assertEquals(0, DebianVersions.compare("01:1.0-01", "1:1.0-1"));
  }

  @Test
  void comparesNumericRunsWithoutOverflow() {
    assertLess("1.999999999999999999999999999", "1.1000000000000000000000000000");
    assertLess("1.09", "1.10");
  }

  @Test
  void rejectsMalformedVersions() {
    assertThrows(IllegalArgumentException.class, () -> DebianVersions.require(null));
    assertThrows(IllegalArgumentException.class, () -> DebianVersions.require(""));
    assertThrows(IllegalArgumentException.class, () -> DebianVersions.require("x:1.0"));
    assertThrows(IllegalArgumentException.class, () -> DebianVersions.require("1.0-"));
    assertThrows(IllegalArgumentException.class, () -> DebianVersions.require(" 1.0"));
  }

  private static void assertLess(String left, String right) {
    assertTrue(DebianVersions.compare(left, right) < 0, () -> left + " should be older than " + right);
    assertTrue(DebianVersions.compare(right, left) > 0, () -> right + " should be newer than " + left);
  }
}
