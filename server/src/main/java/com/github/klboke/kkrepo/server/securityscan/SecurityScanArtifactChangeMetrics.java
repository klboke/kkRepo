package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Backlog visibility for the durable artifact-change projection. */
@Component
public class SecurityScanArtifactChangeMetrics {
  private final ArtifactChangeDao changes;
  private final AtomicLong backlog = new AtomicLong();
  private final AtomicLong oldestAgeSeconds = new AtomicLong();

  public SecurityScanArtifactChangeMetrics(
      ArtifactChangeDao changes, MeterRegistry registry) {
    this.changes = changes;
    Gauge.builder("kkrepo_security_scan_artifact_event_backlog", backlog, AtomicLong::get)
        .description("Unreclaimed artifact content-change events")
        .register(registry);
    Gauge.builder(
            "kkrepo_security_scan_artifact_event_oldest_age_seconds",
            oldestAgeSeconds,
            AtomicLong::get)
        .description("Age of the oldest unreclaimed artifact content-change event")
        .register(registry);
  }

  @Scheduled(fixedDelayString = "${kkrepo.security-scanning.metrics-refresh:15s}")
  public void refresh() {
    Instant now = Instant.now();
    changes.retainedRange().ifPresentOrElse(range -> {
      backlog.set(range.estimatedCount());
      oldestAgeSeconds.set(
          Math.max(0, Duration.between(range.oldestOccurredAt(), now).toSeconds()));
    }, () -> {
      backlog.set(0);
      oldestAgeSeconds.set(0);
    });
  }
}
