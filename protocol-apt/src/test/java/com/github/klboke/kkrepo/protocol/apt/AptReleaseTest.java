package com.github.klboke.kkrepo.protocol.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

  @Test
  void validatesBuildersChecksumsAndReleaseSyntax() {
    Instant date = Instant.parse("2026-08-08T00:00:00Z");
    AptRelease release = AptRelease.builder("stable", date)
        .architectures(List.of("amd64"))
        .components(List.of("main"))
        .validUntil(date.plusSeconds(60))
        .validUntil(null)
        .field("Description", "fixture")
        .checksum("MD5Sum", "a".repeat(32), 1, "Packages")
        .checksum("SHA1", "b".repeat(40), 1, "Packages")
        .checksum("SHA512", "C".repeat(128), 1, "Packages")
        .build();
    assertTrue(release.render().contains("c".repeat(128)));
    assertEquals(3, AptRelease.parse(release.render()).checksums().size());

    assertThrows(IllegalArgumentException.class, () -> AptRelease.builder("bad/name", date));
    assertThrows(IllegalArgumentException.class,
        () -> AptRelease.builder("stable", date).architectures(List.of("bad arch")));
    assertThrows(IllegalArgumentException.class,
        () -> AptRelease.builder("stable", date).components(List.of("bad/name")));
    assertThrows(IllegalArgumentException.class,
        () -> AptRelease.builder("stable", date).field("SHA256", "bad"));
    assertThrows(IllegalArgumentException.class,
        () -> new AptRelease.Checksum("SHA3", "a", 1, "Packages"));
    assertThrows(IllegalArgumentException.class,
        () -> new AptRelease.Checksum("SHA256", "z".repeat(64), 1, "Packages"));
    assertThrows(IllegalArgumentException.class,
        () -> new AptRelease.Checksum("SHA256", "a".repeat(64), -1, "Packages"));
    assertThrows(IllegalArgumentException.class,
        () -> new AptRelease.Checksum("SHA256", "a".repeat(64), 1, "../Packages"));
    assertThrows(IllegalArgumentException.class,
        () -> AptRelease.parse("Suite: stable\nSHA256: malformed\n"));
  }
}
