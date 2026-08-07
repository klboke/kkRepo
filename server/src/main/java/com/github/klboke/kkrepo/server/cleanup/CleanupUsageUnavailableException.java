package com.github.klboke.kkrepo.server.cleanup;

/** Fails a tracked download closed when its durable cleanup usage watermark cannot be stored. */
public class CleanupUsageUnavailableException extends RuntimeException {
  public CleanupUsageUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
