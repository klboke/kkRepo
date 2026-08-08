package com.github.klboke.kkrepo.protocol.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AptReleaseTest {
  @Test
  void rendersAndParsesChecksumsDeterministically() {
    AptRelease release = AptRelease.builder("bookworm", Instant.parse("2026-08-08T00:00:00Z"))
        .architectures(List.of("amd64", "all"))
        .components(List.of("main"))
        .checksum("SHA256", "b".repeat(64), 20, "main/binary-amd64/Packages.gz")
        .checksum("SHA256", "a".repeat(64), 10, "main/binary-amd64/Packages")
        .build();
    String rendered = release.render();
    assertTrue(rendered.contains("Acquire-By-Hash: yes"));
    assertTrue(rendered.indexOf("Packages\n") < rendered.indexOf("Packages.gz"));
    AptRelease parsed = AptRelease.parse(rendered);
    assertEquals("bookworm", parsed.fields().get("Suite"));
    assertEquals(2, parsed.checksums().size());
    assertEquals(rendered, parsed.render());
  }
}
