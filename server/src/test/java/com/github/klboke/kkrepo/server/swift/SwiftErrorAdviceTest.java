package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class SwiftErrorAdviceTest {

  @Test
  void rendersRfc7807ProblemDetailsWithProtocolHeaders() {
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/swift/Acme/Demo/1.0.0");

    ResponseEntity<Map<String, Object>> response = new SwiftErrorAdvice().handle(
        new SwiftExceptions.NotFound("Swift release was not found"), request);

    assertEquals(404, response.getStatusCode().value());
    assertEquals("application/problem+json", response.getHeaders().getFirst("Content-Type"));
    assertEquals("1", response.getHeaders().getFirst("Content-Version"));
    assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
    assertEquals("about:blank", response.getBody().get("type"));
    assertEquals("Not Found", response.getBody().get("title"));
    assertEquals(404, response.getBody().get("status"));
    assertEquals("Swift release was not found", response.getBody().get("detail"));
    assertEquals("/repository/swift/Acme/Demo/1.0.0", response.getBody().get("instance"));
  }

  @Test
  void rendersUpstreamRateLimitsAs429WithRetryAfter() {
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/swift-proxy/apple/swift-nio");

    ResponseEntity<Map<String, Object>> response = new SwiftErrorAdvice().handle(
        new SwiftExceptions.UpstreamRateLimited(
            "GitHub rate limit", Instant.now().plusSeconds(30)),
        request);

    assertEquals(429, response.getStatusCode().value());
    assertEquals("Too Many Requests", response.getBody().get("title"));
    assertEquals(429, response.getBody().get("status"));
    assertEquals("1", response.getHeaders().getFirst("Content-Version"));
    assertTrue(Long.parseLong(response.getHeaders().getFirst("Retry-After")) >= 1L);
  }
}
