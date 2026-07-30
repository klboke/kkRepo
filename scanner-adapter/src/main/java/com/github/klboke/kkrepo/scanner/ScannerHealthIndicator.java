package com.github.klboke.kkrepo.scanner;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Keeps traffic away from scanner pods until engines and the vulnerability database are ready. */
@Component("scanner")
public class ScannerHealthIndicator implements HealthIndicator {
  private final ScannerEngineService engine;

  public ScannerHealthIndicator(ScannerEngineService engine) {
    this.engine = engine;
  }

  @Override
  public Health health() {
    var readiness = engine.readiness();
    Health.Builder health = readiness.ready()
        ? Health.up()
        : Health.status("DEGRADED");
    return health
        .withDetail("status", readiness.status())
        .withDetail("engine", readiness.engineName())
        .withDetail("engineVersion", readiness.engineVersion())
        .withDetail("databaseRevision", readiness.vulnerabilityDatabaseRevision())
        .withDetail("databaseUpdatedAt", readiness.vulnerabilityDatabaseUpdatedAt())
        .withDetails(readiness.details())
        .build();
  }
}
