package com.github.klboke.kkrepo.plugin.api;

/** Input passed to a maintenance task. Destructive runs require an explicit non-dry-run request. */
public record MaintenanceRequest(
    boolean dryRun,
    int maxDeletions) {

  public MaintenanceRequest {
    if (maxDeletions < 0) {
      throw new IllegalArgumentException("maxDeletions must be non-negative");
    }
  }
}
