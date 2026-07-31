package com.github.klboke.kkrepo.server.securityscan;

/** Classified scanner failure used by retry and terminal-failure policy. */
public class ScannerAdapterException extends RuntimeException {
  private final String code;
  private final boolean retryable;

  public ScannerAdapterException(String code, String message, boolean retryable) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }

  public ScannerAdapterException(
      String code, String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
