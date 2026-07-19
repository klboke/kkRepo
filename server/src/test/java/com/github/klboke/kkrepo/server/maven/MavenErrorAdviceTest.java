package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.server.cargo.CargoExceptions;
import com.github.klboke.kkrepo.protocol.pub.PubContentTypes;
import com.github.klboke.kkrepo.server.npm.NpmExceptions;
import com.github.klboke.kkrepo.server.pub.PubExceptions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class MavenErrorAdviceTest {

  @Test
  void npmMinimumReleaseAgeDenialIsNotFoundAndNotCacheable() {
    MavenErrorAdvice advice = new MavenErrorAdvice();

    ResponseEntity<Map<String, Object>> response = advice.npmReleaseAgeDenied(
        new NpmExceptions.ReleaseAgeDenied("release is too new"));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    assertEquals(false, response.getBody().get("success"));
    assertEquals("release is too new", response.getBody().get("error"));
  }

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
  void cargoNotFoundUsesCargoErrorBodyShape() {
    MavenErrorAdvice advice = new MavenErrorAdvice();

    ResponseEntity<Map<String, Object>> response = advice.cargoNotFound(
        new CargoExceptions.CargoNotFoundException("missing-crate"));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    var errors = (java.util.List<Map<String, Object>>) response.getBody().get("errors");
    assertEquals("missing-crate", errors.get(0).get("detail"));
  }

  @Test
  void cargoIndexNotFoundReturnsEmptyNotFoundForSparseIndexMisses() {
    MavenErrorAdvice advice = new MavenErrorAdvice();

    ResponseEntity<Void> response = advice.cargoIndexNotFound(
        new CargoExceptions.CargoIndexNotFoundException("missing-crate"));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertEquals(null, response.getBody());
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

  @Test
  @SuppressWarnings("unchecked")
  void pubBadRequestUsesPubErrorBodyShapeAndContentType() {
    MavenErrorAdvice advice = new MavenErrorAdvice();

    ResponseEntity<Map<String, Object>> response = advice.pubBadRequest(
        new PubExceptions.BadRequestException("Invalid Pub upload form token"));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals(PubContentTypes.JSON, response.getHeaders().getContentType().toString());
    Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
    assertEquals("bad_request", error.get("code"));
    assertEquals("Invalid Pub upload form token", error.get("message"));
  }
}
