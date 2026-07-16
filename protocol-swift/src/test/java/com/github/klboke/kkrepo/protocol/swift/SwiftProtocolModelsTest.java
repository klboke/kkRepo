package com.github.klboke.kkrepo.protocol.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwiftProtocolModelsTest {
  @Test
  void discoversVersionedManifestsWithoutEvaluatingSource() {
    assertEquals("Package@swift-5.9.swift", SwiftToolsVersions.manifestFilename("5.9"));
    assertEquals("5.9", SwiftToolsVersions.fromManifestFilename("Package@swift-5.9.swift").orElseThrow());
    assertEquals("5.9", SwiftToolsVersions.fromManifest(
        "// swift-tools-version: 5.9\nimport PackageDescription").orElseThrow());
    assertEquals("6.0", SwiftToolsVersions.fromManifest(
        "\uFEFF//swift-tools-version:6.0\r\nimport PackageDescription").orElseThrow());
    assertTrue(SwiftToolsVersions.fromManifest(
        "// comment\n// swift-tools-version:5.9").isEmpty());
    assertTrue(SwiftToolsVersions.fromManifestFilename("Package.swift").isEmpty());
  }

  @Test
  void rendersSafeLinkHeadersAndManifestAttributes() {
    SwiftLink latest = SwiftLink.of(
        "https://repo.example/repository/swift/mona/LinkedList/1.2.3", "latest-version");
    SwiftLink alternate = SwiftLink.alternateManifest(
        "https://repo.example/repository/swift/mona/LinkedList/1.2.3/Package.swift?swift-version=5.9",
        "Package@swift-5.9.swift",
        "5.9");
    String header = SwiftLinkHeader.render(List.of(latest, alternate));

    assertTrue(header.contains("rel=\"latest-version\""));
    assertTrue(header.contains("filename=\"Package@swift-5.9.swift\""));
    assertTrue(header.contains("swift-tools-version=\"5.9\""));
    assertThrows(IllegalArgumentException.class, () -> new SwiftLink(
        URI.create("https://repo.example/ok"), "alternate\r\nX-Evil: yes", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> SwiftLink.alternateManifest(
        "https://repo.example/manifest", "Package@swift-5.8.swift", "5.9"));
  }

  @Test
  void buildsProtocolJsonModelsWithStableVersionOrderAndDeduplicatedIdentities() {
    SwiftReleaseList releases = SwiftReleaseList.available(
        List.of("1.0.0", "2.0.0-beta.1", "2.0.0"), version -> "https://repo/" + version);
    assertEquals(List.of("2.0.0", "2.0.0-beta.1", "1.0.0"),
        List.copyOf(releases.releases().keySet()));

    SwiftReleaseList withUnavailable = SwiftReleaseList.listed(
        List.of("1.0.0"), Map.of("2.0.0", "administratively deleted"),
        version -> "https://repo/" + version);
    assertEquals(410, withUnavailable.releases().get("2.0.0").problem().status());
    assertEquals("administratively deleted",
        withUnavailable.releases().get("2.0.0").problem().detail());
    assertEquals(null, withUnavailable.releases().get("2.0.0").url());

    SwiftIdentifiers identifiers = new SwiftIdentifiers(List.of(
        "Mona.LinkedList", "mona.linkedlist", "Other.LinkedList"));
    assertEquals(List.of("Mona.LinkedList", "Other.LinkedList"), identifiers.identifiers());

    String uppercaseChecksum = "A".repeat(64);
    SwiftReleaseResource resource = SwiftReleaseResource.sourceArchive(uppercaseChecksum, null);
    SwiftReleaseMetadata metadata = new SwiftReleaseMetadata(
        "Mona.LinkedList",
        "1.0.0",
        List.of(resource),
        Map.of("repositoryURLs", List.of("https://github.com/mona/LinkedList")),
        Instant.parse("2026-01-01T00:00:00.123Z"));
    assertEquals("a".repeat(64), metadata.resources().getFirst().checksum());
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), metadata.publishedAt());
    assertThrows(UnsupportedOperationException.class,
        () -> metadata.metadata().put("description", "changed"));
    assertThrows(IllegalArgumentException.class, () -> new SwiftReleaseMetadata(
        "Mona.LinkedList", "1.0.0", List.of(), Map.of(), null));
    assertThrows(IllegalArgumentException.class, () -> new SwiftProblem(
        "about:blank", "Bad Request", 400, "bad", null, Map.of("status", 999)));
  }

  @Test
  void exposesHostedProxyAndGroupCapabilityOnNexusPaths() {
    SwiftRepositoryProtocol protocol = new SwiftRepositoryProtocol();
    assertEquals(RepositoryFormat.SWIFT, protocol.format());
    assertTrue(protocol.capability().hostedRead());
    assertTrue(protocol.capability().hostedWrite());
    assertTrue(protocol.capability().proxyRead());
    assertTrue(protocol.capability().groupRead());
    assertTrue(protocol.capability().nexusPathCompatible());
  }
}
