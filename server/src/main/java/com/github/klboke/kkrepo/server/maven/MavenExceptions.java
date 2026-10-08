package com.github.klboke.kkrepo.server.maven;

/** Container for thin, format-specific Maven exceptions translated to HTTP by {@link MavenErrorAdvice}. */
public final class MavenExceptions {
  private MavenExceptions() {}

  public static class MavenNotFoundException extends RuntimeException {
    public MavenNotFoundException(String message) { super(message); }
  }

  public static class LayoutPolicyViolation extends RuntimeException {
    public LayoutPolicyViolation(String message) { super(message); }
  }

  public static class VersionPolicyViolation extends RuntimeException {
    public VersionPolicyViolation(String message) { super(message); }
  }

  public static class WritePolicyDenied extends RuntimeException {
    public WritePolicyDenied(String message) { super(message); }
  }

  public static class BadRequestException extends RuntimeException {
    public BadRequestException(String message) { super(message); }
    public BadRequestException(String message, Throwable cause) { super(message, cause); }
  }

  public static class MethodNotAllowed extends RuntimeException {
    public MethodNotAllowed(String message) { super(message); }
  }

  /** Expected upstream authentication rejection, distinct from transport/programming failures. */
  public static class UpstreamUnauthorizedException extends BadUpstreamException {
    public UpstreamUnauthorizedException() { super("Upstream returned 401"); }
  }

  public static class BadUpstreamException extends RuntimeException {
    public BadUpstreamException(String message) { super(message); }
    public BadUpstreamException(String message, Throwable cause) { super(message, cause); }
  }
}
