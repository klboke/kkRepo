package com.github.klboke.kkrepo.server.composer;

public final class ComposerExceptions {
  private ComposerExceptions() {
  }

  public static class BadRequestException extends RuntimeException {
    public BadRequestException(String message) { super(message); }
    public BadRequestException(String message, Throwable cause) { super(message, cause); }
  }
}
