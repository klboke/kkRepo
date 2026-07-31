package com.github.klboke.kkrepo.server.ansible;

import com.github.klboke.kkrepo.server.RepositoryContentController;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Prevents Spring HTML errors from leaking into Galaxy v3 client responses. */
@RestControllerAdvice(assignableTypes = RepositoryContentController.class)
public class AnsibleGalaxyErrorAdvice {
  @ExceptionHandler(AnsibleGalaxyExceptions.GalaxyException.class)
  public ResponseEntity<Map<String, Object>> handle(
      AnsibleGalaxyExceptions.GalaxyException failure) {
    Map<String, Object> error = Map.of(
        "status", Integer.toString(failure.status()),
        "code", failure.code(),
        "title", failure.title(),
        "detail", failure.getMessage() == null ? failure.title() : failure.getMessage());
    return ResponseEntity.status(failure.status())
        .contentType(MediaType.APPLICATION_JSON)
        .header("Cache-Control", "no-store")
        .body(Map.of("errors", List.of(error)));
  }
}
