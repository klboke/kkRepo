package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PubAssetWriterTest {

  @Test
  void componentAttributesIncludePublishProvenance() {
    PubPackageMetadata metadata = new PubPackageMetadata(
        "example_package",
        "1.0.0",
        Map.of("name", "example_package", "version", "1.0.0"));
    PubAssetWriter.Digests digests = new PubAssetWriter.Digests(
        "md5",
        "sha1",
        "a".repeat(64),
        "sha512",
        512L);
    Map<String, Object> attrs = PubAssetWriter.componentAttributes(
        metadata,
        digests,
        "packages/example_package/versions/1.0.0.tar.gz",
        Instant.parse("2026-07-08T00:00:00Z"),
        Map.of(
            "publishSource", "pub-client",
            "publishedBy", "alice",
            "publishApiKeyId", 42L,
            "uploadSessionId", "session-1",
            "sourceClient", "Dart pub 3.9.0"));

    assertEquals("example_package", attrs.get("packageName"));
    assertEquals("1.0.0", attrs.get("version"));
    assertEquals("a".repeat(64), attrs.get("archiveSha256"));
    assertEquals(512L, attrs.get("archiveSize"));
    assertEquals("packages/example_package/versions/1.0.0.tar.gz", attrs.get("archivePath"));
    assertEquals("2026-07-08T00:00:00Z", attrs.get("publishedAt"));
    assertEquals("pub-client", attrs.get("publishSource"));
    assertEquals("alice", attrs.get("publishedBy"));
    assertEquals(42L, attrs.get("publishApiKeyId"));
    assertEquals("session-1", attrs.get("uploadSessionId"));
    assertEquals("Dart pub 3.9.0", attrs.get("sourceClient"));
  }
}
