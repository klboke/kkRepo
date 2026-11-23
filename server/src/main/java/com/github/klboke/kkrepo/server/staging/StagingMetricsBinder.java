package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Exposes durable staging counts as Prometheus gauges on every replica. */
@Component
public class StagingMetricsBinder {
  private static final Logger log = LoggerFactory.getLogger(StagingMetricsBinder.class);

  private final StagingDao staging;
  private final MultiGauge sessions;
  private final MultiGauge items;
  private final MultiGauge groupChanges;

  public StagingMetricsBinder(StagingDao staging, MeterRegistry registry) {
    this.staging = staging;
    this.sessions = MultiGauge.builder("staging.sessions").register(registry);
    this.items = MultiGauge.builder("staging.items").register(registry);
    this.groupChanges = MultiGauge.builder("staging.group_changes").register(registry);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.staging.metrics-refresh-ms:30000}",
      initialDelayString = "${kkrepo.staging.metrics-initial-delay-ms:30000}")
  public void refresh() {
    try {
      apply(sessions, "state", staging.countSessionsByState());
      apply(items, "status", staging.countItemsByStatus());
      apply(groupChanges, "status", staging.countGroupChangesByStatus());
    } catch (RuntimeException error) {
      log.warn("Failed refreshing staging metrics", error);
    }
  }

  private static void apply(MultiGauge gauge, String tag, Map<String, Long> values) {
    List<MultiGauge.Row<Number>> rows = values.entrySet().stream()
        .map(entry -> MultiGauge.Row.<Number>of(
            Tags.of(tag, entry.getKey()),
            entry.getValue() == null ? 0L : entry.getValue()))
        .toList();
    gauge.register(rows, true);
  }
}
