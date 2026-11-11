package com.github.klboke.kkrepo.server.maintenance;

/** Audit record for one maintenance task run. */
public record MaintenanceAuditEvent(
    String taskId,
    boolean dryRun,
    int proposed,
    int deleted,
    long durationMs,
    String outcome,
    String error) {
}
