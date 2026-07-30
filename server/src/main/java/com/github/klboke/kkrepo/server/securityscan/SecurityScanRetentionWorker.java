package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded lifecycle cleanup for durable workflow and derived scanner data. */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.security-scanning",
    name = "enabled",
    havingValue = "true")
public class SecurityScanRetentionWorker {
  private static final Logger log =
      LoggerFactory.getLogger(SecurityScanRetentionWorker.class);

  private final SecurityScanDao scans;
  private final SecurityScanningProperties properties;
  private final SecurityScanMetrics metrics;

  public SecurityScanRetentionWorker(
      SecurityScanDao scans,
      SecurityScanningProperties properties,
      SecurityScanMetrics metrics) {
    this.scans = scans;
    this.properties = properties;
    this.metrics = metrics;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.retention.delay:1h}",
      initialDelayString = "${kkrepo.security-scanning.retention.initial-delay:5m}")
  public void runOnce() {
    if (!properties.getRetention().isEnabled()) {
      return;
    }
    Instant now = Instant.now();
    try {
      SecurityScanDao.RetentionResult result = scans.cleanupRetainedData(
          now.minus(
              properties.getRetention().getTerminalTaskDays(), ChronoUnit.DAYS),
          now.minus(properties.getRetention().getResultDays(), ChronoUnit.DAYS),
          properties.getRetention().getBatchSize());
      metrics.recordRetention(result);
    } catch (RuntimeException e) {
      log.warn("Security scan lifecycle retention failed", e);
    }
  }
}
