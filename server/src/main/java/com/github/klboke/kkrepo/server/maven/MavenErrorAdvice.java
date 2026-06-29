package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.server.RepositoryContentController;
import com.github.klboke.kkrepo.server.cargo.CargoExceptions;
import com.github.klboke.kkrepo.server.cargo.CargoResponses;
import com.github.klboke.kkrepo.server.npm.NpmExceptions;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RepositoryContentController.class)
public class MavenErrorAdvice {

  @ExceptionHandler(MavenExceptions.MavenNotFoundException.class)
  public ResponseEntity<Map<String, Object>> notFound(MavenExceptions.MavenNotFoundException e) {
    return body(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(MavenExceptions.LayoutPolicyViolation.class)
  public ResponseEntity<Map<String, Object>> layout(MavenExceptions.LayoutPolicyViolation e) {
    return body(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(MavenExceptions.VersionPolicyViolation.class)
  public ResponseEntity<Map<String, Object>> version(MavenExceptions.VersionPolicyViolation e) {
    return body(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(MavenExceptions.WritePolicyDenied.class)
  public ResponseEntity<Map<String, Object>> writeDenied(MavenExceptions.WritePolicyDenied e) {
    return body(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(MavenExceptions.MethodNotAllowed.class)
  public ResponseEntity<Map<String, Object>> method(MavenExceptions.MethodNotAllowed e) {
    return body(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
  }

  @ExceptionHandler(MavenExceptions.BadUpstreamException.class)
  public ResponseEntity<Map<String, Object>> upstream(MavenExceptions.BadUpstreamException e) {
    return body(HttpStatus.BAD_GATEWAY, e.getMessage());
  }

  @ExceptionHandler(NpmExceptions.NpmNotFoundException.class)
  public ResponseEntity<Map<String, Object>> npmNotFound(NpmExceptions.NpmNotFoundException e) {
    return npmBody(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(NpmExceptions.BadRequestException.class)
  public ResponseEntity<Map<String, Object>> npmBadRequest(NpmExceptions.BadRequestException e) {
    return npmBody(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(NpmExceptions.WritePolicyDenied.class)
  public ResponseEntity<Map<String, Object>> npmWriteDenied(NpmExceptions.WritePolicyDenied e) {
    return npmBody(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(NpmExceptions.MethodNotAllowed.class)
  public ResponseEntity<Map<String, Object>> npmMethod(NpmExceptions.MethodNotAllowed e) {
    return npmBody(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
  }

  @ExceptionHandler(NpmExceptions.BadUpstreamException.class)
  public ResponseEntity<Map<String, Object>> npmUpstream(NpmExceptions.BadUpstreamException e) {
    return npmBody(HttpStatus.BAD_GATEWAY, e.getMessage());
  }

  @ExceptionHandler(CargoExceptions.CargoNotFoundException.class)
  public ResponseEntity<Map<String, Object>> cargoNotFound(CargoExceptions.CargoNotFoundException e) {
    return cargoBody(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(CargoExceptions.CargoIndexNotFoundException.class)
  public ResponseEntity<Void> cargoIndexNotFound(CargoExceptions.CargoIndexNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
  }

  @ExceptionHandler(CargoExceptions.BadRequestException.class)
  public ResponseEntity<Map<String, Object>> cargoBadRequest(CargoExceptions.BadRequestException e) {
    return cargoBody(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(CargoExceptions.WritePolicyDenied.class)
  public ResponseEntity<Map<String, Object>> cargoWriteDenied(CargoExceptions.WritePolicyDenied e) {
    return cargoBody(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(CargoExceptions.MethodNotAllowed.class)
  public ResponseEntity<Map<String, Object>> cargoMethod(CargoExceptions.MethodNotAllowed e) {
    return cargoBody(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
  }

  @ExceptionHandler(CargoExceptions.BadUpstreamException.class)
  public ResponseEntity<Map<String, Object>> cargoUpstream(CargoExceptions.BadUpstreamException e) {
    return cargoBody(HttpStatus.BAD_GATEWAY, e.getMessage());
  }

  private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of(
        "status", status.value(),
        "error", status.getReasonPhrase(),
        "message", message == null ? status.getReasonPhrase() : message));
  }

  private ResponseEntity<Map<String, Object>> npmBody(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of(
        "success", false,
        "error", message == null ? status.getReasonPhrase() : message));
  }

  @SuppressWarnings("unchecked")
  private ResponseEntity<Map<String, Object>> cargoBody(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(CargoResponses.errorBody(
        message == null ? status.getReasonPhrase() : message));
  }
}
