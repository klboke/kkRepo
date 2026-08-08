package com.github.klboke.kkrepo.security.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScanFingerprintsTest {
  @Test
  void catalogFingerprintIsStableAndContentSensitive() {
    ScanSubject subject = new ScanSubject(
        SubjectKind.ASSET_BLOB, 1, 2L, 3L, "sha256:" + "a".repeat(64), "a".repeat(64),
        42, "MAVEN", "artifact", "application/java-archive",
        TargetClassification.PACKAGE, List.of(), Map.of());
    String first = ScanFingerprints.catalog(subject, "syft", "1.0", "config-a");
    String second = ScanFingerprints.catalog(subject, "syft", "1.0", "config-a");
    String changed = ScanFingerprints.catalog(subject, "syft", "1.1", "config-a");

    assertEquals(first, second);
    assertNotEquals(first, changed);
    assertEquals(64, first.length());
  }

  @Test
  void condaCatalogModeHasADistinctContentFingerprint() {
    ScanSubject generic = new ScanSubject(
        SubjectKind.ASSET_BLOB, 1, 2L, 3L, "sha256:" + "a".repeat(64), "a".repeat(64),
        42, "RAW", "file", "application/octet-stream",
        TargetClassification.PACKAGE, List.of(), Map.of());
    ScanSubject conda = new ScanSubject(
        SubjectKind.CONDA_PACKAGE, 1, 2L, 3L, "sha256:" + "a".repeat(64), "a".repeat(64),
        42, "CONDA", "package", "application/octet-stream",
        TargetClassification.PACKAGE, List.of(), Map.of());

    assertNotEquals(
        ScanFingerprints.catalog(generic, "syft", "1", "config"),
        ScanFingerprints.catalog(conda, "syft", "1", "config"));
  }

  @Test
  void lengthPrefixPreventsAmbiguousInputs() {
    assertNotEquals(
        ScanFingerprints.sha256("ab", "c"),
        ScanFingerprints.sha256("a", "bc"));
  }

  @Test
  void matchFingerprintIncludesTheOciPlatformOutcome() {
    String complete = ScanFingerprints.match(
        "a".repeat(64),
        true,
        "grype",
        "1",
        "db",
        Instant.parse("2026-07-29T00:00:00Z"),
        "config",
        List.of("linux/amd64", "linux/arm64"),
        List.of());
    String partial = ScanFingerprints.match(
        "a".repeat(64),
        true,
        "grype",
        "1",
        "db",
        Instant.parse("2026-07-29T00:00:00Z"),
        "config",
        List.of("linux/amd64"),
        List.of("linux/arm64"));

    assertNotEquals(complete, partial);
  }

  @Test
  void matchFingerprintIncludesTheVulnerabilityDatabaseBuildTime() {
    String older = ScanFingerprints.match(
        "a".repeat(64),
        true,
        "grype",
        "1",
        "schema-v6",
        Instant.parse("2026-07-28T00:00:00Z"),
        "config");
    String newer = ScanFingerprints.match(
        "a".repeat(64),
        true,
        "grype",
        "1",
        "schema-v6",
        Instant.parse("2026-07-29T00:00:00Z"),
        "config");

    assertNotEquals(older, newer);
  }

  @Test
  void matchFingerprintUsesThePersistedDatabaseTimestampPrecision() {
    String persisted = ScanFingerprints.match(
        "a".repeat(64),
        true,
        "grype",
        "1",
        "schema-v6",
        Instant.parse("2026-07-29T00:00:00.123Z"),
        "config");
    String wire = ScanFingerprints.match(
        "a".repeat(64),
        true,
        "grype",
        "1",
        "schema-v6",
        Instant.parse("2026-07-29T00:00:00.123456789Z"),
        "config");

    assertEquals(persisted, wire);
  }

  @Test
  void catalogFingerprintIncludesTheOciPlatformOutcome() {
    ScanSubject subject = new ScanSubject(
        SubjectKind.OCI_MANIFEST, 1, 2L, 3L, "sha256:" + "a".repeat(64), "a".repeat(64),
        42, "DOCKER", "manifest", "application/vnd.oci.image.manifest.v1+json",
        TargetClassification.OCI_IMAGE, List.of("linux/amd64", "linux/arm64"), Map.of());
    String complete = ScanFingerprints.catalog(
        subject,
        "syft",
        "1",
        "config",
        List.of("linux/amd64", "linux/arm64"),
        List.of());
    String partial = ScanFingerprints.catalog(
        subject,
        "syft",
        "1",
        "config",
        List.of("linux/amd64"),
        List.of("linux/arm64"));

    assertNotEquals(complete, partial);
  }
}
