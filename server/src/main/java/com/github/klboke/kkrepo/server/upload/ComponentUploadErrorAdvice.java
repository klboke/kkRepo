package com.github.klboke.kkrepo.server.upload;

import com.github.klboke.kkrepo.server.cargo.CargoExceptions;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.npm.NpmExceptions;
import com.github.klboke.kkrepo.server.pypi.PypiExceptions;
import com.github.klboke.kkrepo.server.pub.PubExceptions;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ComponentUploadController.class)
public class ComponentUploadErrorAdvice {
  @ExceptionHandler({UploadValidationException.class, IllegalArgumentException.class})
  public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
    return body(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(IOException.class)
  public ResponseEntity<Map<String, String>> badUpload(IOException e) {
    return body(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(MavenExceptions.MavenNotFoundException.class)
  public ResponseEntity<Map<String, String>> mavenNotFound(MavenExceptions.MavenNotFoundException e) {
    return body(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler({MavenExceptions.LayoutPolicyViolation.class,
      MavenExceptions.VersionPolicyViolation.class,
      MavenExceptions.WritePolicyDenied.class})
  public ResponseEntity<Map<String, String>> mavenBadRequest(RuntimeException e) {
    return body(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(MavenExceptions.MethodNotAllowed.class)
  public ResponseEntity<Map<String, String>> mavenMethod(MavenExceptions.MethodNotAllowed e) {
    return body(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
  }

  @ExceptionHandler(NpmExceptions.NpmNotFoundException.class)
  public ResponseEntity<Map<String, String>> npmNotFound(NpmExceptions.NpmNotFoundException e) {
    return body(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler({NpmExceptions.BadRequestException.class, NpmExceptions.WritePolicyDenied.class})
  public ResponseEntity<Map<String, String>> npmBadRequest(RuntimeException e) {
    return body(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(NpmExceptions.MethodNotAllowed.class)
  public ResponseEntity<Map<String, String>> npmMethod(NpmExceptions.MethodNotAllowed e) {
    return body(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
  }

  @ExceptionHandler(PypiExceptions.PypiNotFoundException.class)
  public ResponseEntity<Map<String, String>> pypiNotFound(PypiExceptions.PypiNotFoundException e) {
    return body(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler({PypiExceptions.BadRequestException.class, PypiExceptions.WritePolicyDenied.class})
  public ResponseEntity<Map<String, String>> pypiBadRequest(RuntimeException e) {
    return body(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(PypiExceptions.MethodNotAllowed.class)
  public ResponseEntity<Map<String, String>> pypiMethod(PypiExceptions.MethodNotAllowed e) {
    return body(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
  }

  @ExceptionHandler(CargoExceptions.CargoNotFoundException.class)
  public ResponseEntity<Map<String, String>> cargoNotFound(CargoExceptions.CargoNotFoundException e) {
    return body(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler({CargoExceptions.BadRequestException.class, CargoExceptions.WritePolicyDenied.class})
  public ResponseEntity<Map<String, String>> cargoBadRequest(RuntimeException e) {
    return body(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(CargoExceptions.MethodNotAllowed.class)
  public ResponseEntity<Map<String, String>> cargoMethod(CargoExceptions.MethodNotAllowed e) {
    return body(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
  }

  @ExceptionHandler(PubExceptions.PubNotFoundException.class)
  public ResponseEntity<Map<String, String>> pubNotFound(PubExceptions.PubNotFoundException e) {
    return body(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler({PubExceptions.BadRequestException.class, PubExceptions.WritePolicyDenied.class})
  public ResponseEntity<Map<String, String>> pubBadRequest(RuntimeException e) {
    return body(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(PubExceptions.MethodNotAllowed.class)
  public ResponseEntity<Map<String, String>> pubMethod(PubExceptions.MethodNotAllowed e) {
    return body(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
  }

  private ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("error", message == null ? status.getReasonPhrase() : message));
  }
}
