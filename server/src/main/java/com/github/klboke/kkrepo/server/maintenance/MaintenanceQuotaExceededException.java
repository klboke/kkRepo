package com.github.klboke.kkrepo.server.maintenance;

/** A maintenance task exceeded its configured deletion or proposal quota. */
public class MaintenanceQuotaExceededException extends RuntimeException {
  public MaintenanceQuotaExceededException(String message) {
    super(message);
  }
}
