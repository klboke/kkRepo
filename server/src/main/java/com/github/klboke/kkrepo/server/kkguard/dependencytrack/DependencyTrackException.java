package com.github.klboke.kkrepo.server.kkguard.dependencytrack;

/** Sanitized failure raised when Dependency-Track cannot serve a kkGuard request. */
public class DependencyTrackException extends RuntimeException {
  private final int statusCode;

  public DependencyTrackException(String message) {
    this(message, 0, null);
  }

  public DependencyTrackException(String message, Throwable cause) {
    this(message, 0, cause);
  }

  public DependencyTrackException(String message, int statusCode) {
    this(message, statusCode, null);
  }

  public DependencyTrackException(String message, int statusCode, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  /** HTTP status returned by Dependency-Track, or {@code 0} when the call never completed. */
  public int statusCode() {
    return statusCode;
  }
}
