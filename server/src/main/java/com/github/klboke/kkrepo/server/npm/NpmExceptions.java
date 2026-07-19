package com.github.klboke.kkrepo.server.npm;

public final class NpmExceptions {
  private NpmExceptions() {
  }

  public static class NpmNotFoundException extends RuntimeException {
    public NpmNotFoundException(String message) {
      super(message);
    }
  }

  public static class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
      super(message);
    }

    public BadRequestException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class WritePolicyDenied extends RuntimeException {
    public WritePolicyDenied(String message) {
      super(message);
    }
  }

  public static class ReleaseAgeDenied extends RuntimeException {
    public ReleaseAgeDenied(String message) {
      super(message);
    }
  }

  public static class BadUpstreamException extends RuntimeException {
    public BadUpstreamException(String message) {
      super(message);
    }
  }

  public static class MethodNotAllowed extends RuntimeException {
    public MethodNotAllowed(String message) {
      super(message);
    }
  }
}
