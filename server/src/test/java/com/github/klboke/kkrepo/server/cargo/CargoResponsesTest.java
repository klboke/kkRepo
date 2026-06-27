package com.github.klboke.kkrepo.server.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CargoResponsesTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void textResponseGeneratesStableBodyEtagWhenMissing() {
    MavenResponse first = CargoResponses.text("line-one\n", null,
        Instant.parse("2026-06-08T00:00:00Z"), true);
    MavenResponse second = CargoResponses.text("line-one\n", null,
        Instant.parse("2026-06-08T00:00:00Z"), true);

    assertNotNull(first.etag());
    assertEquals(first.etag(), second.etag());
  }

  @Test
  void jsonWithBodyEtagGeneratesStableConfigEtag() {
    Map<String, Object> config = Map.of("dl", "http://localhost/repository/cargo/crates");

    MavenResponse first = CargoResponses.jsonWithBodyEtag(objectMapper, config, 200, true);
    MavenResponse second = CargoResponses.jsonWithBodyEtag(objectMapper, config, 200, true);

    assertNotNull(first.etag());
    assertEquals(first.etag(), second.etag());
  }

  @Test
  void unsupportedSearchReturnsCargoJsonError() {
    MavenResponse response = CargoResponses.unsupportedSearch(objectMapper, true);

    assertEquals(501, response.status());
    assertEquals("application/json", response.contentType());
    assertNotNull(response.etag());
  }
}
