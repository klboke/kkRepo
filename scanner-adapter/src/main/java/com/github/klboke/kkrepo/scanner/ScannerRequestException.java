package com.github.klboke.kkrepo.scanner;

public class ScannerRequestException extends RuntimeException {
  private final String code;
  private final int status;
  private final boolean retryable;

  public ScannerRequestException(String code, String message, int status, boolean retryable) {
    super(message);
    this.code = code;
    this.status = status;
    this.retryable = retryable;
  }

  public ScannerRequestException(
      String code, String message, int status, boolean retryable, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.status = status;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public int status() {
    return status;
  }

  public boolean retryable() {
    return retryable;
  }
}
