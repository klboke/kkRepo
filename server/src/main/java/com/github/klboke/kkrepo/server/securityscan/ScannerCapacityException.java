package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.security.scan.ScannerContract;

/** The adapter rejected the request before starting scanner work. */
final class ScannerCapacityException extends ScannerAdapterException {
  private final int retryAfterSeconds;

  ScannerCapacityException(int retryAfterSeconds) {
    super(ScannerContract.CAPACITY_EXHAUSTED, "Scanner adapter returned HTTP 429", true);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  int retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
