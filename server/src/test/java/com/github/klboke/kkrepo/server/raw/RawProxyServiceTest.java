package com.github.klboke.kkrepo.server.raw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawProxyServiceTest {
  @Test
  void cachePopulationAlwaysUsesUpstreamGetForClientHeadRequests() {
    RepositoryRuntime runtime = new RepositoryRuntime(
        10L,
        "composer-proxy",
        RepositoryFormat.COMPOSER,
        RepositoryType.PROXY,
        "composer-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        "https://repo.packagist.org/",
        1440,
        1440,
        true,
        null,
        List.of());
    Instant lastModified = Instant.parse("2026-07-12T00:00:00Z");

    HttpRemoteFetcher.Request request = RawProxyService.cachePopulationRequest(
        runtime,
        "https://github.com/php-fig/log/archive/refs/tags/3.0.2.zip",
        "etag",
        lastModified);

    assertFalse(request.headOnly());
    assertEquals("GET", request.method());
    assertEquals(HttpRemoteFetcher.TimeoutProfile.CONTENT, request.timeoutProfile());
    assertEquals("composer-proxy", request.repository());
    assertEquals("COMPOSER", request.format());
    assertEquals(lastModified, request.lastModified());
  }
}
