package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScannerSnapshot;
import java.time.Duration;
import java.time.Instant;

/**
 * Shared scanner readiness evaluation used by both health reporting and the management API.
 *
 * <p>The persisted snapshot remains the multi-replica source of truth. Readiness is derived at
 * read time so an otherwise-ready snapshot becomes degraded when its observation or vulnerability
 * database ages past the configured limit.
 */
public record SecurityScannerStatus(boolean ready, String reasonCode) {
  private static final Duration MAX_OBSERVATION_CLOCK_SKEW = Duration.ofSeconds(5);
  static final String READY = "READY";
  static final String DEPLOYMENT_DISABLED = "DEPLOYMENT_DISABLED";
  static final String SNAPSHOT_UNAVAILABLE = "SNAPSHOT_UNAVAILABLE";
  static final String SCANNER_NOT_READY = "SCANNER_NOT_READY";
  static final String SCANNER_OBSERVATION_STALE = "SCANNER_OBSERVATION_STALE";
  static final String DATABASE_AGE_UNKNOWN = "DATABASE_AGE_UNKNOWN";
  static final String DATABASE_STALE = "DATABASE_STALE";

  public static SecurityScannerStatus disabled() {
    return new SecurityScannerStatus(false, DEPLOYMENT_DISABLED);
  }

  public static SecurityScannerStatus evaluate(
      ScannerSnapshot snapshot, SecurityScanningProperties properties, Instant now) {
    if (snapshot == null) {
      return new SecurityScannerStatus(false, SNAPSHOT_UNAVAILABLE);
    }
    if (!snapshot.ready()) {
      return new SecurityScannerStatus(false, SCANNER_NOT_READY);
    }
    Duration observationMaxAge = properties.getScannerObservationMaxAge();
    if (snapshot.observedAt() == null
        || observedTooFarInFuture(snapshot.observedAt(), now)
        || expired(snapshot.observedAt(), observationMaxAge, now)) {
      return new SecurityScannerStatus(false, SCANNER_OBSERVATION_STALE);
    }
    Instant databaseUpdatedAt = snapshot.vulnerabilityDatabaseUpdatedAt();
    if (databaseUpdatedAt == null) {
      return new SecurityScannerStatus(false, DATABASE_AGE_UNKNOWN);
    }
    if (expired(databaseUpdatedAt, properties.getScannerDatabaseMaxAge(), now)) {
      return new SecurityScannerStatus(false, DATABASE_STALE);
    }
    return new SecurityScannerStatus(true, READY);
  }

  static boolean observedTooFarInFuture(Instant observedAt, Instant now) {
    return observedAt.isAfter(now.plus(MAX_OBSERVATION_CLOCK_SKEW));
  }

  private static boolean expired(Instant timestamp, Duration maxAge, Instant now) {
    return maxAge != null
        && !maxAge.isZero()
        && !maxAge.isNegative()
        && timestamp.plus(maxAge).isBefore(now);
  }
}
