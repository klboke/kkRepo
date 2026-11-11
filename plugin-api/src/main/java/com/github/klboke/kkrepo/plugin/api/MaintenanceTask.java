package com.github.klboke.kkrepo.plugin.api;

/** Scoped maintenance job. Implementations must honour dry-run, quotas and audit requirements. */
public interface MaintenanceTask {

  String id();

  MaintenanceResult run(MaintenanceRequest request);
}
