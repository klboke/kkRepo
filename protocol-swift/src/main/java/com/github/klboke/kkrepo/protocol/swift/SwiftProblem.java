package com.github.klboke.kkrepo.protocol.swift;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** RFC 7807 problem details returned to SwiftPM instead of framework-specific errors. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SwiftProblem(
    String type,
    String title,
    Integer status,
    String detail,
    String instance,
    Map<String, Object> extensions) {
  private static final Set<String> RESERVED =
      Set.of("type", "title", "status", "detail", "instance");

  public SwiftProblem {
    if (status != null && (status < 100 || status > 599)) {
      throw new IllegalArgumentException("Invalid problem status: " + status);
    }
    LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
    if (extensions != null) {
      extensions.forEach((name, value) -> {
        if (name == null || name.isBlank() || RESERVED.contains(name)) {
          throw new IllegalArgumentException("Invalid problem extension name: " + name);
        }
        copied.put(name, value);
      });
    }
    extensions = Collections.unmodifiableMap(copied);
  }

  public static SwiftProblem of(int status, String title, String detail) {
    return new SwiftProblem("about:blank", title, status, detail, null, Map.of());
  }

  @Override
  @JsonAnyGetter
  public Map<String, Object> extensions() {
    return extensions;
  }
}
