package com.github.klboke.kkrepo.scanner;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Best-effort local cancellation for active adapter work.
 *
 * <p>The durable task row remains the cluster-wide authority. This registry only releases the
 * process and capacity owned by the adapter replica that received the matching request.
 */
@Component
public class ScannerExecutionRegistry {
  private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private final ConcurrentMap<String, ActiveExecution> active = new ConcurrentHashMap<>();

  public <T> T execute(String runId, CheckedSupplier<T> action) throws IOException {
    requireRunId(runId);
    ActiveExecution execution = new ActiveExecution(Thread.currentThread());
    if (active.putIfAbsent(runId, execution) != null) {
      throw new ScannerRequestException(
          "SCANNER_RUN_ALREADY_ACTIVE",
          "Scanner run is already active on this adapter",
          409,
          true);
    }
    try {
      return action.get();
    } finally {
      execution.complete();
      active.remove(runId, execution);
    }
  }

  public boolean cancel(String runId) {
    requireRunId(runId);
    ActiveExecution execution = active.get(runId);
    if (execution == null) return false;
    return execution.cancel();
  }

  private static void requireRunId(String runId) {
    if (runId == null || !RUN_ID.matcher(runId).matches()) {
      throw new ScannerRequestException(
          "SCANNER_RUN_ID_INVALID",
          "Scanner run identifier is invalid",
          400,
          false);
    }
  }

  @FunctionalInterface
  public interface CheckedSupplier<T> {
    T get() throws IOException;
  }

  static final class ActiveExecution {
    private final Thread thread;
    private boolean completed;

    ActiveExecution(Thread thread) {
      this.thread = thread;
    }

    synchronized boolean cancel() {
      if (completed) return false;
      thread.interrupt();
      return true;
    }

    synchronized void complete() {
      completed = true;
    }
  }
}
