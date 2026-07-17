package com.github.klboke.kkrepo.protocol.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SwiftVersionsTest {
  @Test
  void followsSemverPrecedenceIncludingNumericPrereleaseIdentifiers() {
    List<String> ascending = List.of(
        "1.0.0-alpha",
        "1.0.0-alpha.1",
        "1.0.0-alpha.beta",
        "1.0.0-beta",
        "1.0.0-beta.2",
        "1.0.0-beta.11",
        "1.0.0-rc.1",
        "1.0.0");

    for (int i = 0; i < ascending.size() - 1; i++) {
      assertTrue(SwiftVersions.compare(ascending.get(i), ascending.get(i + 1)) < 0);
    }
    assertEquals(0, SwiftVersions.compare("1.0.0+build.1", "1.0.0+build.2"));
    assertEquals(ascending.reversed(), SwiftVersions.sortDescending(ascending));
  }

  @Test
  void validatesVersionsAndNexusCompatibleGitTagPrefixes() {
    assertEquals("1.2.3", SwiftVersions.normalizeGitTag("v1.2.3"));
    assertEquals("1.2.3-beta.1", SwiftVersions.normalizeGitTag("V1.2.3-beta.1"));
    assertEquals("1.2.3", SwiftVersions.normalizeGitTag("1.2.3"));
    assertTrue(SwiftVersions.isPrerelease("1.2.3-rc.1"));
    assertFalse(SwiftVersions.isPrerelease("1.2.3+build"));

    for (String version : new String[] {
        "1", "1.2", "01.2.3", "1.02.3", "1.2.03", "1.2.3-", "1.2.3-a..b",
        "1.2.3-01", "1.2.3+", " 1.2.3", "1.2.3 ", "v1.2.3"
    }) {
      assertThrows(IllegalArgumentException.class, () -> SwiftVersions.require(version), version);
    }
    assertThrows(IllegalArgumentException.class, () -> SwiftVersions.normalizeGitTag("vv1.2.3"));
  }

  @Test
  void supportsArbitrarilyLargeNumericVersionsWithoutOverflow() {
    assertTrue(SwiftVersions.compare(
        "999999999999999999999999999999.0.0", "2.999999999999999999999.999") > 0);
  }
}
