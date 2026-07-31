package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
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
    SecurityScannerStatus status =
        SecurityScannerStatus.evaluate(snapshot, properties, Instant.now());
    if (!status.ready()) {
      return degraded(
          status.reasonCode(),
          snapshot == null ? null : snapshot.id(),
          snapshot == null ? null : snapshot.vulnerabilityDatabaseUpdatedAt());
    }
    return Health.up()
        .withDetail("enabled", true)
        .withDetail("snapshotId", snapshot.id())
        .withDetail("databaseUpdatedAt", snapshot.vulnerabilityDatabaseUpdatedAt())
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
