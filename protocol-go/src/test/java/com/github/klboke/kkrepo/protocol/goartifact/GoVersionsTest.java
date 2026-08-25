package com.github.klboke.kkrepo.protocol.goartifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoVersionsTest {
  @Test
  void comparesSemverAndFiltersPseudoVersionsFromList() {
    assertTrue(GoVersions.compare("v1.10.0", "v1.9.9") > 0);
    assertTrue(GoVersions.compare("v1.0.0", "v1.0.0-rc.1") > 0);
    assertTrue(GoVersions.isPseudoVersion("v0.0.0-20250824120000-abcdef123456"));
    assertTrue(GoVersions.isPseudoVersion("v1.2.4-0.20250824120000-abcdef1"));
    assertTrue(GoVersions.isPseudoVersion("v1.2.3-rc.0.20250824120000-abcdef1"));
    assertFalse(GoVersions.isPseudoVersion("v1.2.3-rc.1"));
    assertFalse(GoVersions.isPseudoVersion("v1.2.3-rc.20250824120000-abcdef1"));
    assertEquals(
        List.of("v1.2.3-rc.1", "v1.2.3", "v1.10.0"),
        GoVersions.listVersions(List.of(
            "v1.10.0", "v0.0.0-20250824120000-abcdef123456", "v1.2.3", "v1.2.3-rc.1")));
  }

  @Test
  void latestPrefersReleaseThenPrereleaseThenNewestPseudoTimestamp() {
    Instant now = Instant.parse("2026-08-25T00:00:00Z");
    assertEquals("v1.9.0", GoVersions.latest(List.of(
        new GoVersions.Candidate("v2.0.0-rc.1", now),
        new GoVersions.Candidate("v1.9.0", now.minusSeconds(60))))
        .orElseThrow().version());
    assertEquals("v2.0.0-rc.2", GoVersions.latest(List.of(
        new GoVersions.Candidate("v2.0.0-rc.1", now),
        new GoVersions.Candidate("v2.0.0-rc.2", now.minusSeconds(60))))
        .orElseThrow().version());
    assertEquals("v0.0.0-20250825120000-bbbbbbb", GoVersions.latest(List.of(
        new GoVersions.Candidate("v0.0.0-20250824120000-aaaaaaa", now.plusSeconds(60)),
        new GoVersions.Candidate("v0.0.0-20250825120000-bbbbbbb", now)))
        .orElseThrow().version());
  }

  @Test
  void rejectsNonCanonicalVersions() {
    assertEquals("v1.2.3-!r!c1", GoVersions.escape("v1.2.3-RC1"));
    assertEquals("v1.2.3-RC1", GoVersions.unescape("v1.2.3-!r!c1"));
    assertThrows(IllegalArgumentException.class, () -> GoVersions.requireCanonical("1.2.3"));
    assertThrows(IllegalArgumentException.class, () -> GoVersions.requireCanonical("v01.2.3"));
    assertThrows(IllegalArgumentException.class, () -> GoVersions.requireCanonical("v1.2"));
  }
}
