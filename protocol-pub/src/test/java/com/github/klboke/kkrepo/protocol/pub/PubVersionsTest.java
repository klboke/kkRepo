package com.github.klboke.kkrepo.protocol.pub;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PubVersionsTest {
  @Test
  void comparesSemverPreReleasesBelowStable() {
    assertTrue(PubVersions.compare("1.0.0-dev.2", "1.0.0") < 0);
    assertTrue(PubVersions.compare("1.0.0-dev.10", "1.0.0-dev.2") > 0);
    assertTrue(PubVersions.compare("1.2.0", "1.1.9") > 0);
  }

  @Test
  void detectsPrereleaseVersions() {
    assertTrue(PubVersions.isPrerelease("2.0.0-dev.1"));
    assertFalse(PubVersions.isPrerelease("1.0.0"));
    assertFalse(PubVersions.isPrerelease("1.0.0+build.1"));
  }
}
