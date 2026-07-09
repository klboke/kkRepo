package com.github.klboke.kkrepo.server.pub;

public final class PubExceptions {
  private PubExceptions() {
  }

  public static class PubNotFoundException extends RuntimeException {
    public PubNotFoundException(String message) { super(message); }
  }

  public static class BadRequestException extends RuntimeException {
    public BadRequestException(String message) { super(message); }
    public BadRequestException(String message, Throwable cause) { super(message, cause); }
  }

  public static class WritePolicyDenied extends RuntimeException {
    public WritePolicyDenied(String message) { super(message); }
  }

  public static class MethodNotAllowed extends RuntimeException {
    public MethodNotAllowed(String message) { super(message); }
  }

  public static class BadUpstreamException extends RuntimeException {
    public BadUpstreamException(String message) { super(message); }
    public BadUpstreamException(String message, Throwable cause) { super(message, cause); }
  }
}
