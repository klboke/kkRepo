package com.github.klboke.kkrepo.protocol.swift;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SwiftListedRelease(String url, SwiftProblem problem) {
  public static SwiftListedRelease available(String url) {
    return new SwiftListedRelease(url, null);
  }

  public static SwiftListedRelease unavailable(String detail) {
    String explanation = detail == null || detail.isBlank()
        ? "This release is no longer available"
        : detail;
    return new SwiftListedRelease(null, SwiftProblem.of(410, "Gone", explanation));
  }
}
