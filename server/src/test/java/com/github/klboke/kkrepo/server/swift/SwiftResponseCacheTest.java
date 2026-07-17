package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwiftResponseCacheTest {

  @Test
  void returnsSmallResponseOnlyForTheSameDurableRevisionState() throws Exception {
    SwiftResponseCache cache = new SwiftResponseCache(new InMemorySharedCache(), true);
    Instant now = Instant.parse("2026-07-17T08:00:00Z");
    byte[] body = "{\"releases\":{}}".getBytes(StandardCharsets.UTF_8);
    cache.put(
        "release-list",
        "[1:HOSTED:true:7]",
        60,
        now,
        body,
        "etag",
        Map.of("Content-Version", "1", "Link", "<https://example>; rel=\"canonical\""));

    SwiftResponseCache.Snapshot snapshot = cache.find(
        "release-list", "[1:HOSTED:true:7]", 60, now.plusSeconds(30)).orElseThrow();
    MavenResponse response = snapshot.response(false);

    assertEquals("etag", response.etag());
    assertEquals("1", response.headers().get("Content-Version"));
    assertEquals(new String(body, StandardCharsets.UTF_8),
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    assertTrue(cache.find(
        "release-list", "[1:HOSTED:true:8]", 60, now.plusSeconds(30)).isEmpty());
  }

  @Test
  void zeroMetadataAgeDisablesTheRecoverableCache() {
    SwiftResponseCache cache = new SwiftResponseCache(new InMemorySharedCache(), true);
    cache.put("release-list", "7", 0, Instant.now(), new byte[] {1}, "etag", Map.of());

    assertTrue(cache.find("release-list", "7", 0, Instant.now()).isEmpty());
  }
}
