package com.github.klboke.kkrepo.protocol.winget;

/** A WinGet REST API failure carrying the status and stable error code to return. */
public final class WingetException extends RuntimeException {
  private final int status;
  private final String code;

  public WingetException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static WingetException badRequest(String message) {
    return new WingetException(400, "InvalidRequest", message);
  }

  public static WingetException notFound(String message) {
    return new WingetException(404, "NotFound", message);
  }

  public static WingetException methodNotAllowed(String message) {
    return new WingetException(405, "MethodNotAllowed", message);
  }

  public static WingetException conflict(String message) {
    return new WingetException(409, "Conflict", message);
  }

  public static WingetException forbidden(String message) {
    return new WingetException(403, "Forbidden", message);
  }

  public static WingetException payloadTooLarge(String message) {
    return new WingetException(413, "PayloadTooLarge", message);
  }

  public static WingetException unsupportedMediaType(String message) {
    return new WingetException(415, "UnsupportedMediaType", message);
  }

  public static WingetException upstream(String message) {
    return new WingetException(502, "UpstreamError", message);
  }

  public static WingetException unavailable(String message) {
    return new WingetException(503, "Unavailable", message);
  }
}
