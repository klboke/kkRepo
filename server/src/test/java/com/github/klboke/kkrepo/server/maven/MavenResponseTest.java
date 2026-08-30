package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MavenResponseTest {

  @Test
  void materializesALazyBodyOnceAndPreservesResponseMetadata() {
    AtomicInteger opens = new AtomicInteger();
    InputStream body = new ByteArrayInputStream(new byte[] {1, 2, 3});
    Instant lastModified = Instant.parse("2026-08-30T00:00:00Z");
    MavenResponse lazy = MavenResponse.ok(
            () -> {
              opens.incrementAndGet();
              return body;
            },
            3L,
            "application/gzip",
            "chart-etag",
            lastModified)
        .withStatus(206)
        .withHeader("X-Test", "preserved")
        .withInternalAttribute("sha256", "digest");

    MavenResponse materialized = lazy.materializeBody();

    assertEquals(1, opens.get());
    assertSame(body, materialized.body());
    assertSame(body, materialized.body());
    assertEquals(1, opens.get());
    assertEquals(206, materialized.status());
    assertEquals(3L, materialized.contentLength());
    assertEquals("application/gzip", materialized.contentType());
    assertEquals("chart-etag", materialized.etag());
    assertEquals(lastModified, materialized.lastModified());
    assertEquals("preserved", materialized.headers().get("X-Test"));
    assertEquals("digest", materialized.internalAttribute("sha256"));
  }

  @Test
  void leavesEagerAndBodylessResponsesUnchanged() {
    MavenResponse eager = MavenResponse.ok(
        new ByteArrayInputStream(new byte[] {1}), 1L, "application/octet-stream", null, null);
    MavenResponse bodyless = MavenResponse.noBody(204);

    assertSame(eager, eager.materializeBody());
    assertSame(bodyless, bodyless.materializeBody());
  }
}
