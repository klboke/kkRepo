package com.github.klboke.kkrepo.protocol.openvsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OpenVsxVersionsTest {
  @Test
  void ordersNumericSegmentsAndPrereleases() {
    assertTrue(OpenVsxVersions.compare("1.10.0", "1.9.9") > 0);
    assertTrue(OpenVsxVersions.compare("2.0.0", "10.0.0") < 0);
    assertTrue(OpenVsxVersions.compare("1.0.0-beta.2", "1.0.0-beta.10") < 0);
    assertTrue(OpenVsxVersions.compare("1.0.0-beta", "1.0.0") < 0);
    assertEquals(0, OpenVsxVersions.compare("1.0.0+build.1", "1.0.0+build.2"));
  }

  @Test
  void selectsLatestStableFirst() {
    assertEquals("1.2.0", OpenVsxVersions.latest(List.of("1.0.0", "1.2.0", "2.0.0-rc.1")));
    assertTrue(OpenVsxVersions.isPrerelease("2.0.0-rc.1"));
    assertFalse(OpenVsxVersions.isPrerelease("2.0.0"));
  }

  @Test
  void rejectsEmptySegmentsAndComparesThemSafely() {
    assertFalse(OpenVsxCoordinates.validVersion("1..2"));
    assertFalse(OpenVsxCoordinates.validVersion("1.0.0-"));
    assertFalse(OpenVsxCoordinates.validVersion("-1.0.0"));
    assertFalse(OpenVsxCoordinates.validVersion("1.0.0-a..b"));
    assertEquals(0, OpenVsxVersions.compare("1..2", "1..2"));
    assertTrue(OpenVsxVersions.compare("1..2", "1.0.0") < 0);
  }
}
