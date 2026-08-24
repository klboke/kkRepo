package com.github.klboke.kkrepo.server.conan;

import com.github.klboke.kkrepo.server.RepositoryProtocolController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Prevents HTML framework error pages from reaching the Conan CLI. */
@RestControllerAdvice(assignableTypes = RepositoryProtocolController.class)
public class ConanErrorAdvice {
  @ExceptionHandler(ConanExceptions.ConanException.class)
  public ResponseEntity<String> handle(ConanExceptions.ConanException failure) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(failure.status())
        .contentType(MediaType.TEXT_PLAIN)
        .header(HttpHeaders.CACHE_CONTROL, "no-store");
    if (failure.status() == 401) {
      response.header(HttpHeaders.WWW_AUTHENTICATE,
          "Bearer realm=\"Conan API\", Basic realm=\"kkrepo\"");
    }
    return response.body(failure.getMessage() == null ? "Conan request failed" : failure.getMessage());
  }
}
