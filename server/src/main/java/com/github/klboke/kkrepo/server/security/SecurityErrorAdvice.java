package com.github.klboke.kkrepo.server.security;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SecurityErrorAdvice {
  @ExceptionHandler(ApiKeyAlreadyExistsException.class)
  public ResponseEntity<Map<String, String>> apiKeyConflict(ApiKeyAlreadyExistsException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("message", exception.getMessage()));
  }

  @ExceptionHandler(SecurityValidationException.class)
  public ResponseEntity<Map<String, String>> validation(SecurityValidationException exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("message", exception.getMessage()));
  }
}
