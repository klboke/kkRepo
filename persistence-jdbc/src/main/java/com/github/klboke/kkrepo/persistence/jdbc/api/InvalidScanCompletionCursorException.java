package com.github.klboke.kkrepo.persistence.jdbc.api;

/** The configured JDBC connection cannot represent the completion cursor timestamp. */
public final class InvalidScanCompletionCursorException extends RuntimeException {
  public InvalidScanCompletionCursorException(Throwable cause) {
    super("Invalid completion cursor timestamp", cause);
  }
}
