package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.RepositoryProtocolController;
import com.github.klboke.kkrepo.server.cargo.CargoExceptions;
import com.github.klboke.kkrepo.protocol.pub.PubContentTypes;
import com.github.klboke.kkrepo.server.npm.NpmExceptions;
import com.github.klboke.kkrepo.server.pub.PubExceptions;
import com.github.klboke.kkrepo.server.releaseage.ReleaseAgeDeniedException;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolDispatcher;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MavenErrorAdviceTest {

  @ParameterizedTest
  @ValueSource(strings = {"*/*", "application/problem+json,*/*", "application/problem+json"})
  void missingMavenContentKeepsPlainJsonWhenClientAcceptsProblemDetails(String accept)
      throws Exception {
    var dispatcher = mock(RepositoryProtocolDispatcher.class);
    var mvc = MockMvcBuilders.standaloneSetup(new RepositoryProtocolController(dispatcher))
        .setControllerAdvice(new MavenErrorAdvice())
        .build();
    for (String path : java.util.List.of(
        "com/acme/demo/maven-metadata.xml",
        "com/acme/demo/1.0-SNAPSHOT/maven-metadata.xml",
        "com/acme/demo/1.0/demo-1.0.jar")) {
      doThrow(new MavenExceptions.MavenNotFoundException(path)).when(dispatcher)
          .dispatchRead(eq("releases"), any(), eq(HttpMethod.GET));

      var response = mvc.perform(get("/repository/releases/" + path)
          .header(HttpHeaders.ACCEPT, accept)).andReturn().getResponse();
      assertEquals(404, response.getStatus());
      assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());
      var body = new ObjectMapper().readTree(response.getContentAsByteArray());
      assertEquals(404, body.path("status").asInt());
      assertEquals("Not Found", body.path("error").asText());
      assertEquals(path, body.path("message").asText());
    }
  }

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
  void releaseAgeDenialIsForbiddenAndNotCacheable() {
    MavenErrorAdvice advice = new MavenErrorAdvice();

    ResponseEntity<Map<String, Object>> response = advice.releaseAgeDenied(
        new ReleaseAgeDeniedException("Artifact is currently quarantined.", Instant.EPOCH));

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    assertEquals(false, response.getBody().get("success"));
    assertEquals("Artifact is currently quarantined.", response.getBody().get("error"));
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
