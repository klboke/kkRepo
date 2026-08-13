package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.server.RepositoryContentController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
    PypiRepositoryController.class,
    RepositoryContentController.class
})
public class PypiErrorAdvice {
  @ExceptionHandler(PypiExceptions.PypiNotFoundException.class)
  public ResponseEntity<Void> notFound(PypiExceptions.PypiNotFoundException e) {
    return status(HttpStatus.NOT_FOUND);
  }

  @ExceptionHandler(PypiExceptions.BadRequestException.class)
  public ResponseEntity<Void> badRequest(PypiExceptions.BadRequestException e) {
    return status(HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(PypiExceptions.WritePolicyDenied.class)
  public ResponseEntity<Void> writeDenied(PypiExceptions.WritePolicyDenied e) {
    return status(HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(PypiExceptions.MethodNotAllowed.class)
  public ResponseEntity<Void> method(PypiExceptions.MethodNotAllowed e) {
    return status(HttpStatus.METHOD_NOT_ALLOWED);
  }

  @ExceptionHandler(PypiExceptions.BadUpstreamException.class)
  public ResponseEntity<Void> upstream(PypiExceptions.BadUpstreamException e) {
    return status(HttpStatus.BAD_GATEWAY);
  }

  private ResponseEntity<Void> status(HttpStatus status) {
    return ResponseEntity.status(status).build();
  }
}
