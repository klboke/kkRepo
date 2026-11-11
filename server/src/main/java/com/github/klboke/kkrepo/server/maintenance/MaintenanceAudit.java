package com.github.klboke.kkrepo.server.maintenance;

/** Audit sink for scripted maintenance runs. */
public interface MaintenanceAudit {
  void record(MaintenanceAuditEvent event);
}
