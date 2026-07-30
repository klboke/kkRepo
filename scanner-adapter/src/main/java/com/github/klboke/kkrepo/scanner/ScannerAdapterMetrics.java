package com.github.klboke.kkrepo.scanner;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Bounded-label operational metrics for scanner database maintenance. */
@Component
public class ScannerAdapterMetrics {
  private final MeterRegistry registry;

  public ScannerAdapterMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void recordDatabaseUpdate(String outcome) {
    Counter.builder("kkrepo_scanner_database_updates_total")
        .description("Scanner vulnerability database update outcomes")
        .tag("outcome", outcome)
        .register(registry)
        .increment();
  }
}
