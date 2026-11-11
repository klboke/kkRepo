package com.github.klboke.kkrepo.plugin.api;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Portable remote request. */
public record RemoteRequest(
    URI uri,
    String method,
    Map<String, List<String>> headers,
    byte[] body,
    Duration timeout) {

  public RemoteRequest {
    Objects.requireNonNull(uri, "uri");
    method = method == null || method.isBlank()
        ? "GET"
        : method.trim().toUpperCase(Locale.ROOT);
    headers = headers == null ? Map.of() : copyHeaders(headers);
    body = body == null ? new byte[0] : body.clone();
    timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  private static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
    var copy = new java.util.LinkedHashMap<String, List<String>>();
    headers.forEach((name, values) -> {
      if (name != null && values != null) {
        copy.put(name, List.copyOf(values));
      }
    });
    return Map.copyOf(copy);
  }
}
