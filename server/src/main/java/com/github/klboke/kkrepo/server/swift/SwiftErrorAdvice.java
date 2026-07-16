package com.github.klboke.kkrepo.server.swift;

import com.github.klboke.kkrepo.server.RepositoryContentController;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Keeps Spring's HTML/default error document out of Swift Package Registry responses. */
@RestControllerAdvice(assignableTypes = RepositoryContentController.class)
public class SwiftErrorAdvice {
  @ExceptionHandler(SwiftExceptions.SwiftException.class)
  public ResponseEntity<Map<String, Object>> handle(
      SwiftExceptions.SwiftException failure, jakarta.servlet.http.HttpServletRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", failure.title());
    body.put("status", failure.status());
    body.put("detail", failure.getMessage() == null ? failure.title() : failure.getMessage());
    String path = request == null ? null : request.getRequestURI();
    if (path != null && !path.isBlank()) {
      body.put("instance", URI.create(path).toASCIIString());
    }
    ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status())
        .header("Content-Version", "1")
        .header("Cache-Control", "no-store")
        .header("Content-Type", "application/problem+json");
    if (failure instanceof SwiftExceptions.UpstreamRateLimited limited
        && limited.retryAfter() != null) {
      long seconds = Math.max(
          1L, Duration.between(Instant.now(), limited.retryAfter()).toSeconds());
      response.header("Retry-After", Long.toString(seconds));
    }
    return response.body(body);
  }
}
