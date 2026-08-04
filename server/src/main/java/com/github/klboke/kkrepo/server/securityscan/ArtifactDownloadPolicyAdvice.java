package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.server.cleanup.CleanupUsageUnavailableException;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ArtifactDownloadPolicyAdvice {
  @ExceptionHandler(CleanupUsageUnavailableException.class)
  public ResponseEntity<Map<String, Object>> cleanupUsageUnavailable(
      CleanupUsageUnavailableException failure) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Retry-After", "5")
        .body(Map.of(
            "code", "CLEANUP_USAGE_UNAVAILABLE",
            "message", failure.getMessage()));
  }

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
      return response
          .header(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("errors", List.of(Map.of(
              "code", DockerErrorCode.DENIED.name(),
              "message", "artifact is unavailable under repository security policy"))));
    }
    return response.body(Map.of(
        "code", failure.decision().name(),
        "message", "Artifact is unavailable under repository security policy"));
  }
}
