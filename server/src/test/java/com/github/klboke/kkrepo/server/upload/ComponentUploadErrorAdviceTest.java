package com.github.klboke.kkrepo.server.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.server.cargo.CargoExceptions;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ComponentUploadErrorAdviceTest {

  private final ComponentUploadErrorAdvice advice = new ComponentUploadErrorAdvice();

  @Test
  void cargoUploadBadRequestUsesUploadErrorBody() {
    ResponseEntity<Map<String, String>> response =
        advice.cargoBadRequest(new CargoExceptions.BadRequestException("Invalid .crate upload"));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("Invalid .crate upload", response.getBody().get("error"));
  }

  @Test
  void cargoUploadWritePolicyDeniedUsesBadRequest() {
    ResponseEntity<Map<String, String>> response =
        advice.cargoBadRequest(new CargoExceptions.WritePolicyDenied("Cargo asset already exists"));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("Cargo asset already exists", response.getBody().get("error"));
  }

  @Test
  void cargoUploadMethodNotAllowedUsesMethodNotAllowed() {
    ResponseEntity<Map<String, String>> response =
        advice.cargoMethod(new CargoExceptions.MethodNotAllowed("Operation is only valid on hosted Cargo repositories"));

    assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
    assertEquals("Operation is only valid on hosted Cargo repositories", response.getBody().get("error"));
  }

  @Test
  void terraformUploadBadRequestUsesUploadErrorBody() {
    ResponseEntity<Map<String, String>> response =
        advice.mavenBadRequest(new MavenExceptions.BadRequestException("Invalid Terraform archive"));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("Invalid Terraform archive", response.getBody().get("error"));
  }
}
