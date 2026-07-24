package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports scanner degradation from shared database state without making application startup or
 * audit-only repository traffic depend on a live adapter request.
 */
@Component("securityScanner")
public class SecurityScannerHealthIndicator implements HealthIndicator {
  private final SecurityScanDao scans;
  private final SecurityScanningProperties properties;

  public SecurityScannerHealthIndicator(
      SecurityScanDao scans, SecurityScanningProperties properties) {
    this.scans = scans;
    this.properties = properties;
  }

  @Override
  public Health health() {
    if (!properties.isEnabled()) {
      return Health.up().withDetail("enabled", false).build();
    }
    var snapshot = scans.latestScannerSnapshot().orElse(null);
    if (snapshot == null) {
      return degraded("SNAPSHOT_UNAVAILABLE", null, null);
    }
    if (!snapshot.ready()) {
      return degraded(
          "SCANNER_NOT_READY", snapshot.id(), snapshot.vulnerabilityDatabaseUpdatedAt());
    }
    Instant databaseUpdatedAt = snapshot.vulnerabilityDatabaseUpdatedAt();
    Duration maxAge = properties.getScannerDatabaseMaxAge();
    if (databaseUpdatedAt == null) {
      return degraded("DATABASE_AGE_UNKNOWN", snapshot.id(), null);
    }
    if (maxAge != null
        && !maxAge.isZero()
        && !maxAge.isNegative()
        && databaseUpdatedAt.plus(maxAge).isBefore(Instant.now())) {
      return degraded("DATABASE_STALE", snapshot.id(), databaseUpdatedAt);
    }
    return Health.up()
        .withDetail("enabled", true)
        .withDetail("snapshotId", snapshot.id())
        .withDetail("databaseUpdatedAt", databaseUpdatedAt)
        .build();
  }

  private static Health degraded(String reason, Long snapshotId, Instant databaseUpdatedAt) {
    Health.Builder health = Health.status("DEGRADED")
        .withDetail("enabled", true)
        .withDetail("reasonCode", reason);
    if (snapshotId != null) health.withDetail("snapshotId", snapshotId);
    if (databaseUpdatedAt != null) health.withDetail("databaseUpdatedAt", databaseUpdatedAt);
    return health.build();
  }
}
