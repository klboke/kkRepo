package com.github.klboke.kkrepo.server.ansible;

/** Galaxy v3 failures rendered by {@link AnsibleGalaxyErrorAdvice}. */
public final class AnsibleGalaxyExceptions {
  private AnsibleGalaxyExceptions() {
  }

  public abstract static class GalaxyException extends RuntimeException {
    private final int status;
    private final String code;
    private final String title;

    protected GalaxyException(int status, String code, String title, String detail) {
      super(detail);
      this.status = status;
      this.code = code;
      this.title = title;
    }

    protected GalaxyException(
        int status, String code, String title, String detail, Throwable cause) {
      super(detail, cause);
      this.status = status;
      this.code = code;
      this.title = title;
    }

    public int status() {
      return status;
    }

    public String code() {
      return code;
    }

    public String title() {
      return title;
    }
  }

  public static final class BadRequest extends GalaxyException {
    public BadRequest(String detail) {
      super(400, "invalid", "Bad Request", detail);
    }

    public BadRequest(String detail, Throwable cause) {
      super(400, "invalid", "Bad Request", detail, cause);
    }
  }

  public static final class Conflict extends GalaxyException {
    public Conflict(String detail) {
      super(400, "conflict.collection_exists", "Collection version already exists", detail);
    }
  }

  public static final class NotFound extends GalaxyException {
    public NotFound(String detail) {
      super(404, "not_found", "Not Found", detail);
    }
  }

  public static final class Forbidden extends GalaxyException {
    public Forbidden(String detail) {
      super(403, "forbidden", "Forbidden", detail);
    }
  }

  public static final class MethodNotAllowed extends GalaxyException {
    public MethodNotAllowed(String detail) {
      super(405, "method_not_allowed", "Method Not Allowed", detail);
    }
  }

  public static final class UnsupportedMediaType extends GalaxyException {
    public UnsupportedMediaType(String detail) {
      super(415, "unsupported_media_type", "Unsupported Media Type", detail);
    }
  }

  public static final class ContentTooLarge extends GalaxyException {
    public ContentTooLarge(String detail) {
      super(413, "content_too_large", "Content Too Large", detail);
    }
  }

  public static final class BadUpstream extends GalaxyException {
    public BadUpstream(String detail) {
      super(502, "upstream_error", "Bad Gateway", detail);
    }

    public BadUpstream(String detail, Throwable cause) {
      super(502, "upstream_error", "Bad Gateway", detail, cause);
    }
  }

  public static final class ServiceUnavailable extends GalaxyException {
    public ServiceUnavailable(String detail) {
      super(503, "temporarily_unavailable", "Service Unavailable", detail);
    }
  }
}
