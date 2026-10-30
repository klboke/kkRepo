package com.github.klboke.kkrepo.server.releaseage;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure minimum-release-age arithmetic shared by the Go, Maven and PyPI proxies. Stateless, so every
 * replica reaches the same decision from the same publish time.
 */
public final class MinimumReleaseAge {
  private MinimumReleaseAge() {}

  /** Instant at which an artifact published at {@code publishedAt} becomes servable. */
  public static Instant eligibleAt(Instant publishedAt, int minimumAgeMinutes) {
    return publishedAt.plus(Duration.ofMinutes(minimumAgeMinutes));
  }

  public static boolean eligible(Instant publishedAt, int minimumAgeMinutes, Instant now) {
    return publishedAt != null && !eligibleAt(publishedAt, minimumAgeMinutes).isAfter(now);
  }

  public static ReleaseAgeDeniedException denied(
      String what, Instant publishedAt, int minimumAgeMinutes, Instant now) {
    if (publishedAt == null) {
      return new ReleaseAgeDeniedException(
          what + " is quarantined: its publish time could not be determined from the upstream "
              + "(minimum release age is " + minimumAgeMinutes + " minutes)",
          null);
    }
    Instant eligibleAt = eligibleAt(publishedAt, minimumAgeMinutes);
    long remainingMinutes = Math.max(1, Duration.between(now, eligibleAt).toMinutes());
    return new ReleaseAgeDeniedException(
        what + " is currently quarantined. It will be available for download in "
            + formatRemaining(remainingMinutes) + ".",
        eligibleAt);
  }

  /** Throws {@link ReleaseAgeDeniedException} unless the artifact is old enough. */
  public static void require(
      String what, Instant publishedAt, int minimumAgeMinutes, Instant now) {
    if (!eligible(publishedAt, minimumAgeMinutes, now)) {
      throw denied(what, publishedAt, minimumAgeMinutes, now);
    }
  }

  static String formatRemaining(long minutes) {
    if (minutes >= 120) {
      return (minutes + 59) / 60 + " hours";
    }
    if (minutes >= 60) {
      return "about 1 hour";
    }
    return minutes + (minutes == 1 ? " minute" : " minutes");
  }
}
