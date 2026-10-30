package com.github.klboke.kkrepo.server.releaseage;

import java.time.Instant;

/**
 * Raised when a proxy repository refuses to serve an artifact that was published more recently than
 * the repository's minimum release age. Mapped to 403 with {@code Cache-Control: no-store}; it must
 * never be negative-cached because the artifact becomes available once it matures.
 */
public class ReleaseAgeDeniedException extends RuntimeException {
  private final Instant eligibleAt;

  public ReleaseAgeDeniedException(String message, Instant eligibleAt) {
    super(message);
    this.eligibleAt = eligibleAt;
  }

  /** Instant at which the artifact stops being quarantined; null when the publish time is unknown. */
  public Instant eligibleAt() {
    return eligibleAt;
  }
}
