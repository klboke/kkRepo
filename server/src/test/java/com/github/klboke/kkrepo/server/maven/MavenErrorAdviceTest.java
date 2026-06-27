package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.server.cargo.CargoExceptions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class MavenErrorAdviceTest {

  @Test
  void notFoundBodyIncludesHttpStatus() {
    MavenErrorAdvice advice = new MavenErrorAdvice();

    ResponseEntity<Map<String, Object>> response = advice.notFound(
        new MavenExceptions.MavenNotFoundException("io/sentry/sentry-logback/6.9.1/sentry-logback-6.9.1.module"));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertEquals(404, response.getBody().get("status"));
    assertEquals("Not Found", response.getBody().get("error"));
    assertEquals("io/sentry/sentry-logback/6.9.1/sentry-logback-6.9.1.module",
        response.getBody().get("message"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void cargoBadRequestUsesCargoErrorBodyShape() {
    MavenErrorAdvice advice = new MavenErrorAdvice();

    ResponseEntity<Map<String, Object>> response = advice.cargoBadRequest(
        new CargoExceptions.BadRequestException("Invalid Cargo publish body"));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    var errors = (java.util.List<Map<String, Object>>) response.getBody().get("errors");
    assertEquals("Invalid Cargo publish body", errors.get(0).get("detail"));
  }
}
