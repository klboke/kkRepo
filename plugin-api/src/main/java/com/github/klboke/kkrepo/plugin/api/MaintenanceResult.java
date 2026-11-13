package com.github.klboke.kkrepo.plugin.api;

import java.util.List;

/** Outcome of one maintenance run. */
public record MaintenanceResult(
    int proposed,
    int deleted,
    List<String> messages) {

  public MaintenanceResult {
    if (proposed < 0 || deleted < 0) {
      throw new IllegalArgumentException("Maintenance counters must be non-negative");
    }
    messages = messages == null ? List.of() : List.copyOf(messages);
  }
}
