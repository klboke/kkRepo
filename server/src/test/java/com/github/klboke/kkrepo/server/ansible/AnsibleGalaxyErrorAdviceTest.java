package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AnsibleGalaxyErrorAdviceTest {
  private final AnsibleGalaxyErrorAdvice advice = new AnsibleGalaxyErrorAdvice();

  @ParameterizedTest
  @MethodSource("failures")
  void rendersEveryGalaxyFailureAsStableJson(
      AnsibleGalaxyExceptions.GalaxyException failure) {
    var response = advice.handle(failure);
    assertEquals(failure.status(), response.getStatusCode().value());
    assertEquals("application/json", response.getHeaders().getContentType().toString());
    assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
    Map<String, Object> error = errors(response.getBody()).getFirst();
    assertEquals(Integer.toString(failure.status()), error.get("status"));
    assertEquals(failure.code(), error.get("code"));
    assertEquals(failure.title(), error.get("title"));
    assertEquals(failure.getMessage(), error.get("detail"));
  }

  @Test
  void usesTheTitleWhenFailureDetailIsNullAndPreservesCauses() {
    RuntimeException cause = new RuntimeException("root");
    var badRequest = new AnsibleGalaxyExceptions.BadRequest("bad", cause);
    var upstream = new AnsibleGalaxyExceptions.BadUpstream("upstream", cause);
    assertSame(cause, badRequest.getCause());
    assertSame(cause, upstream.getCause());

    AnsibleGalaxyExceptions.GalaxyException withoutDetail =
        new AnsibleGalaxyExceptions.GalaxyException(418, "teapot", "Teapot", null) { };
    Map<String, Object> error = errors(advice.handle(withoutDetail).getBody()).getFirst();
    assertEquals("Teapot", error.get("detail"));
  }

  private static Stream<AnsibleGalaxyExceptions.GalaxyException> failures() {
    return Stream.of(
        new AnsibleGalaxyExceptions.BadRequest("bad"),
        new AnsibleGalaxyExceptions.Conflict("exists"),
        new AnsibleGalaxyExceptions.NotFound("missing"),
        new AnsibleGalaxyExceptions.Forbidden("denied"),
        new AnsibleGalaxyExceptions.MethodNotAllowed("method"),
        new AnsibleGalaxyExceptions.UnsupportedMediaType("media"),
        new AnsibleGalaxyExceptions.ContentTooLarge("large"),
        new AnsibleGalaxyExceptions.BadUpstream("upstream"),
        new AnsibleGalaxyExceptions.ServiceUnavailable("later"));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> errors(Map<String, Object> body) {
    assertNotNull(body);
    return (List<Map<String, Object>>) body.get("errors");
  }
}
