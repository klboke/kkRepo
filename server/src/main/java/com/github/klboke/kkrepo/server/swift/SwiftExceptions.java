package com.github.klboke.kkrepo.server.swift;

import java.time.Instant;

/** Protocol failures translated to RFC 7807 responses for SwiftPM. */
public final class SwiftExceptions {
  private SwiftExceptions() {}

  public abstract static class SwiftException extends RuntimeException {
    private final int status;
    private final String title;

    protected SwiftException(int status, String title, String detail) {
      super(detail);
      this.status = status;
      this.title = title;
    }

    protected SwiftException(int status, String title, String detail, Throwable cause) {
      super(detail, cause);
      this.status = status;
      this.title = title;
    }

    public int status() {
      return status;
    }

    public String title() {
      return title;
    }
  }

  public static final class BadRequest extends SwiftException {
    public BadRequest(String detail) { super(400, "Bad Request", detail); }
    public BadRequest(String detail, Throwable cause) { super(400, "Bad Request", detail, cause); }
  }

  public static class NotFound extends SwiftException {
    public NotFound(String detail) { super(404, "Not Found", detail); }
  }

  public static final class Tombstoned extends NotFound {
    public Tombstoned(String detail) { super(detail); }
  }

  public static final class MethodNotAllowed extends SwiftException {
    public MethodNotAllowed(String detail) { super(405, "Method Not Allowed", detail); }
  }

  public static final class Conflict extends SwiftException {
    public Conflict(String detail) { super(409, "Conflict", detail); }
  }

  public static final class Forbidden extends SwiftException {
    public Forbidden(String detail) { super(403, "Forbidden", detail); }
  }

  public static final class ContentTooLarge extends SwiftException {
    public ContentTooLarge(String detail) { super(413, "Content Too Large", detail); }
  }

  public static final class UnsupportedMediaType extends SwiftException {
    public UnsupportedMediaType(String detail) { super(415, "Unsupported Media Type", detail); }
  }

  public static final class UnprocessableEntity extends SwiftException {
    public UnprocessableEntity(String detail) { super(422, "Unprocessable Entity", detail); }
    public UnprocessableEntity(String detail, Throwable cause) {
      super(422, "Unprocessable Entity", detail, cause);
    }
  }

  public static final class BadUpstream extends SwiftException {
    public BadUpstream(String detail) { super(502, "Bad Gateway", detail); }
    public BadUpstream(String detail, Throwable cause) { super(502, "Bad Gateway", detail, cause); }
  }

  public static final class UpstreamRateLimited extends SwiftException {
    private final Instant retryAfter;

    public UpstreamRateLimited(String detail, Instant retryAfter) {
      super(429, "Too Many Requests", detail);
      this.retryAfter = retryAfter;
    }

    public Instant retryAfter() {
      return retryAfter;
    }
  }
}
