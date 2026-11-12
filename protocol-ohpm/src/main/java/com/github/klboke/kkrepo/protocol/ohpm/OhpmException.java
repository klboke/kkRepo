package com.github.klboke.kkrepo.protocol.ohpm;

import java.util.Map;

public final class OhpmException extends RuntimeException {
  private final int status;

  public OhpmException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }

  public Map<String, Object> response() {
    return Map.of("code", status, "message", getMessage(), "error", getMessage(), "additionalMsg", "");
  }
}
