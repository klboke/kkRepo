package com.github.klboke.kkrepo.server.releaseage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MinimumReleaseAgeTest {
  private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
  private static final int FORTY_EIGHT_HOURS = 48 * 60;

  @Test
  void versionIsEligibleExactlyWhenTheCooldownHasElapsed() {
    Instant published = NOW.minusSeconds(48 * 3600L);

    assertTrue(MinimumReleaseAge.eligible(published, FORTY_EIGHT_HOURS, NOW));
    assertFalse(MinimumReleaseAge.eligible(published.plusSeconds(1), FORTY_EIGHT_HOURS, NOW));
  }

  @Test
  void unknownPublishTimeIsNeverEligible() {
    assertFalse(MinimumReleaseAge.eligible(null, FORTY_EIGHT_HOURS, NOW));
  }

  @Test
  void requireThrowsWithRemainingTimeAndEligibleInstant() {
    Instant published = NOW.minusSeconds(3600L);

    ReleaseAgeDeniedException denied = assertThrows(
        ReleaseAgeDeniedException.class,
        () -> MinimumReleaseAge.require("Version v1.0.0", published, FORTY_EIGHT_HOURS, NOW));

    assertEquals(published.plusSeconds(48 * 3600L), denied.eligibleAt());
    assertEquals(
        "Version v1.0.0 is currently quarantined. It will be available for download in 47 hours.",
        denied.getMessage());
  }

  @Test
  void requireDoesNotThrowForMatureVersion() {
    assertDoesNotThrow(() -> MinimumReleaseAge.require(
        "Version v1.0.0", NOW.minusSeconds(49 * 3600L), FORTY_EIGHT_HOURS, NOW));
  }

  @Test
  void missingPublishTimeIsDeniedWithoutEligibleInstant() {
    ReleaseAgeDeniedException denied = assertThrows(
        ReleaseAgeDeniedException.class,
        () -> MinimumReleaseAge.require("Version v1.0.0", null, FORTY_EIGHT_HOURS, NOW));

    assertNull(denied.eligibleAt());
    assertTrue(denied.getMessage().contains("publish time could not be determined"));
  }

  @Test
  void remainingTimeIsRoundedUpAndHumanReadable() {
    assertEquals("1 minute", MinimumReleaseAge.formatRemaining(1));
    assertEquals("30 minutes", MinimumReleaseAge.formatRemaining(30));
    assertEquals("about 1 hour", MinimumReleaseAge.formatRemaining(90));
    assertEquals("2 hours", MinimumReleaseAge.formatRemaining(120));
    assertEquals("48 hours", MinimumReleaseAge.formatRemaining(2880));
    assertEquals("48 hours", MinimumReleaseAge.formatRemaining(2821));
  }
}
