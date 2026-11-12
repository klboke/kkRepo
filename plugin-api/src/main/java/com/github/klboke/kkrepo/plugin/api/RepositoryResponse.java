package com.github.klboke.kkrepo.plugin.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Framework-agnostic repository response returned by a plugin handler. */
public record RepositoryResponse(
    int status,
    Map<String, List<String>> headers,
    byte[] body,
    StreamingBody stream) {

  public RepositoryResponse {
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("Invalid HTTP status: " + status);
    }
    headers = copyHeaders(headers);
    body = body == null ? new byte[0] : body.clone();
  }

  /** Compatibility constructor for in-memory responses. */
  public RepositoryResponse(
      int status,
      Map<String, List<String>> headers,
      byte[] body) {
    this(status, headers, body, null);
  }

  public static RepositoryResponse ok(byte[] body) {
    return new RepositoryResponse(200, Map.of(), body);
  }

  public static RepositoryResponse noContent() {
    return new RepositoryResponse(204, Map.of(), new byte[0]);
  }

  public static RepositoryResponse status(int status) {
    return new RepositoryResponse(status, Map.of(), new byte[0]);
  }

  public static RepositoryResponse streaming(
      int status,
      Map<String, List<String>> headers,
      StreamingBody stream) {
    return new RepositoryResponse(status, headers, new byte[0],
        Objects.requireNonNull(stream, "stream"));
  }

  public boolean isStreaming() {
    return stream != null;
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  private static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
    if (headers == null) return Map.of();
    Map<String, List<String>> copy = new LinkedHashMap<>();
    headers.forEach((name, values) -> {
      if (name != null && values != null) {
        copy.put(name, List.copyOf(values));
      }
    });
    return Map.copyOf(copy);
  }
}
