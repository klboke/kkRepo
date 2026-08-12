package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class ConanErrorAdviceTest {
  private final ConanErrorAdvice advice = new ConanErrorAdvice();

  @Test
  void preservesProtocolStatusAndBearerChallengeOnlyForAuthenticationFailures() {
    var unauthorized = advice.handle(new ConanExceptions.Unauthorized("login required"));
    assertEquals(401, unauthorized.getStatusCode().value());
    assertEquals("login required", unauthorized.getBody());
    assertEquals(MediaType.TEXT_PLAIN, unauthorized.getHeaders().getContentType());
    assertEquals("no-store", unauthorized.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    assertEquals(
        "Bearer realm=\"Conan API\", Basic realm=\"kkrepo\"",
        unauthorized.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));

    var conflict = advice.handle(new ConanExceptions.Conflict("already exists"));
    assertEquals(409, conflict.getStatusCode().value());
    assertEquals("already exists", conflict.getBody());
    assertNull(conflict.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
  }
}
