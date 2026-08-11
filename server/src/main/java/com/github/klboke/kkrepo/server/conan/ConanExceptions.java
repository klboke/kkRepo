package com.github.klboke.kkrepo.server.conan;

/** Protocol-specific failures with stable Conan client HTTP semantics. */
public final class ConanExceptions {
  private ConanExceptions() {}

  public abstract static class ConanException extends RuntimeException {
    private final int status;

    protected ConanException(int status, String message) {
      super(message);
      this.status = status;
    }

    protected ConanException(int status, String message, Throwable cause) {
      super(message, cause);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }

  public static final class BadRequest extends ConanException {
    public BadRequest(String message) { super(400, message); }
    public BadRequest(String message, Throwable cause) { super(400, message, cause); }
  }

  public static final class Unauthorized extends ConanException {
    public Unauthorized(String message) { super(401, message); }
  }

  public static final class Forbidden extends ConanException {
    public Forbidden(String message) { super(403, message); }
  }

  public static final class NotFound extends ConanException {
    public NotFound(String message) { super(404, message); }
  }

  public static final class MethodNotAllowed extends ConanException {
    public MethodNotAllowed(String message) { super(405, message); }
  }

  public static final class Conflict extends ConanException {
    public Conflict(String message) { super(409, message); }
    public Conflict(String message, Throwable cause) { super(409, message, cause); }
  }

  public static final class ContentTooLarge extends ConanException {
    public ContentTooLarge(String message) { super(413, message); }
  }

  public static final class BadUpstream extends ConanException {
    public BadUpstream(String message) { super(502, message); }
    public BadUpstream(String message, Throwable cause) { super(502, message, cause); }
  }

  public static final class Busy extends ConanException {
    public Busy(String message) { super(503, message); }
  }
}
