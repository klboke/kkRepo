package com.github.klboke.kkrepo.plugin.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Portable remote response. */
public record RemoteResponse(
    int status,
    Map<String, List<String>> headers,
    byte[] body) {

  public RemoteResponse {
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("invalid HTTP status: " + status);
    }
    headers = headers == null ? Map.of() : copyHeaders(headers);
    body = body == null ? new byte[0] : body.clone();
  }

  public boolean successful() {
    return status >= 200 && status < 300;
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  private static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
    var copy = new LinkedHashMap<String, List<String>>();
    headers.forEach((name, values) -> {
      if (name != null && values != null) {
        copy.put(name, List.copyOf(values));
      }
    });
    return Map.copyOf(copy);
  }
}
