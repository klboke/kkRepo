package com.github.klboke.kkrepo.server.securityscan;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ArtifactDownloadPolicyAdvice {
  @ExceptionHandler(ArtifactPolicyException.class)
  public ResponseEntity<?> blocked(
      ArtifactPolicyException failure, HttpServletRequest request) {
    HttpStatus status = failure.pending()
        ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
    ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff");
    if (failure.pending()) {
      response.header(HttpHeaders.RETRY_AFTER, Integer.toString(failure.retryAfterSeconds()));
    }
    String path = request == null ? "" : request.getRequestURI();
    if (path.startsWith("/v2/")) {
      String code = failure.pending() ? "UNAVAILABLE" : "DENIED";
      return response.body(Map.of("errors", List.of(Map.of(
          "code", code,
          "message", "artifact is unavailable under repository security policy"))));
    }
    return response.body(Map.of(
        "code", failure.decision().name(),
        "message", "Artifact is unavailable under repository security policy"));
  }
}
