package com.github.klboke.kkrepo.protocol.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NpmMinimumReleaseAgeTest {
  private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");

  @Test
  void filtersImmatureVersionsAndRewindsLatestWithoutMutatingSource() {
    Map<String, Object> root = packument(
        Map.of("1.0.0", "2026-07-17T12:00:00Z", "1.1.0", "2026-07-19T11:30:01Z"),
        Map.of("latest", "1.1.0"));

    Map<String, Object> filtered = NpmMinimumReleaseAge.filter(root, NOW, 60);

    assertEquals(Map.of("latest", "1.0.0"), NpmMetadata.distTags(filtered));
    assertEquals(1, NpmMetadata.versions(filtered).size());
    assertTrue(NpmMetadata.versions(filtered).containsKey("1.0.0"));
    assertEquals(
        Map.of("1.0.0", "2026-07-17T12:00:00Z"),
        filtered.get(NpmMetadata.TIME));
    assertTrue(NpmMetadata.versions(root).containsKey("1.1.0"), "raw upstream metadata must remain intact");
    assertTrue(((Map<?, ?>) root.get(NpmMetadata.TIME)).containsKey("1.1.0"));
  }

  @Test
  void filtersAnAlreadyIsolatedInstallProjectionWithoutAnotherDeepCopy() {
    Map<String, Object> root = packument(
        Map.of("1.0.0", "2026-07-17T12:00:00Z", "1.1.0", "2026-07-19T11:30:01Z"),
        Map.of("latest", "1.1.0"));
    Map<String, Object> prepared = NpmMetadata.abbreviatePackageRoot(root);
    NpmMinimumReleaseAge.Analysis analysis = NpmMinimumReleaseAge.analyze(root, 60);

    Map<String, Object> filtered = analysis.filterPreparedCopy(prepared, NOW);

    assertSame(prepared, filtered, "the isolated response projection should be filtered in place");
    assertEquals(Map.of("latest", "1.0.0"), NpmMetadata.distTags(filtered));
    assertEquals(List.of("1.0.0"), List.copyOf(NpmMetadata.versions(filtered).keySet()));
    assertEquals(
        Map.of("1.0.0", "2026-07-17T12:00:00Z"),
        filtered.get(NpmMetadata.TIME));
    assertTrue(NpmMetadata.versions(root).containsKey("1.1.0"), "the raw packument must remain intact");
  }

  @Test
  @SuppressWarnings("unchecked")
  void preservesPackageLevelTimeFieldsWhileRemovingHiddenVersionTimes() {
    Map<String, Object> root = packument(
        Map.of("1.0.0", "2026-07-17T12:00:00Z", "1.1.0", "2026-07-19T11:30:01Z"),
        Map.of("latest", "1.1.0"));
    Map<String, Object> time = (Map<String, Object>) root.get(NpmMetadata.TIME);
    time.put("created", "2026-07-17T12:00:00Z");
    time.put("modified", "2026-07-19T11:30:01Z");
    time.put("registry-extension", "preserved");

    Map<String, Object> filtered = NpmMinimumReleaseAge.filter(root, NOW, 60);

    assertEquals(
        Map.of(
            "1.0.0", "2026-07-17T12:00:00Z",
            "created", "2026-07-17T12:00:00Z",
            "modified", "2026-07-19T11:30:01Z",
            "registry-extension", "preserved"),
        filtered.get(NpmMetadata.TIME));
  }

  @Test
  void exactBoundaryIsEligibleAndMissingOrInvalidTimesFailClosed() {
    Map<String, Object> root = packument(
        Map.of("1.0.0", "2026-07-19T11:00:00Z", "1.1.0", "not-a-time"),
        Map.of("latest", "1.1.0"));
    NpmMetadata.versions(root).put("2.0.0", version("2.0.0"));

    assertTrue(NpmMinimumReleaseAge.eligibility(root, "1.0.0", NOW, 60).eligible());
    assertFalse(NpmMinimumReleaseAge.eligibility(root, "1.1.0", NOW, 60).eligible());
    assertFalse(NpmMinimumReleaseAge.eligibility(root, "2.0.0", NOW, 60).eligible());
    assertFalse(NpmMinimumReleaseAge.hasCompletePublishTimes(root));
  }

  @Test
  void detectsMaturityBoundaryIndependentlyOfMetadataTtl() {
    Map<String, Object> root = packument(
        Map.of("1.0.0", "2026-07-19T11:00:00Z"),
        Map.of("latest", "1.0.0"));

    assertTrue(NpmMinimumReleaseAge.crossedMaturityBoundary(
        root, Instant.parse("2026-07-19T11:59:59Z"), NOW, 60));
    assertFalse(NpmMinimumReleaseAge.crossedMaturityBoundary(
        root, NOW, Instant.parse("2026-07-19T12:00:01Z"), 60));
  }

  @Test
  void analysisExposesStableGenerationsAndTheExactNextTransition() {
    Map<String, Object> root = packument(
        Map.of(
            "1.0.0", "2026-07-19T10:00:00Z",
            "1.1.0", "2026-07-19T11:30:00Z"),
        Map.of("latest", "1.1.0"));
    NpmMinimumReleaseAge.Analysis analysis = NpmMinimumReleaseAge.analyze(root, 60);

    assertEquals(1, analysis.visibilityGeneration(NOW));
    assertEquals(
        Instant.parse("2026-07-19T12:30:00Z"),
        analysis.nextMaturityAfter(NOW).orElseThrow());
    assertEquals(2, analysis.visibilityGeneration(Instant.parse("2026-07-19T12:30:00Z")));
    assertTrue(analysis.nextMaturityAfter(Instant.parse("2026-07-19T12:30:00Z")).isEmpty());
  }

  @Test
  void persistedReleaseIndexPreservesPolicySemanticsWithoutThePackument() {
    Map<String, Object> root = packument(
        Map.of(
            "1.0.0", "2026-07-19T10:00:00Z",
            "1.1.0", "2026-07-19T11:30:00Z"),
        Map.of("latest", "1.1.0"));
    NpmMinimumReleaseAge.Analysis fromPackument = NpmMinimumReleaseAge.analyze(root, 60);
    NpmMinimumReleaseAge.Analysis fromIndex = NpmMinimumReleaseAge.analyze(
        NpmMinimumReleaseAge.index(root), 60);

    assertEquals(fromPackument.eligibility("1.0.0", NOW), fromIndex.eligibility("1.0.0", NOW));
    assertEquals(fromPackument.eligibility("1.1.0", NOW), fromIndex.eligibility("1.1.0", NOW));
    assertEquals(fromPackument.visibleVersions(NOW), fromIndex.visibleVersions(NOW));
    assertEquals(
        fromPackument.versionsForTarball("demo-1.1.0.tgz"),
        fromIndex.versionsForTarball("demo-1.1.0.tgz"));
    assertEquals(fromPackument.nextMaturityAfter(NOW), fromIndex.nextMaturityAfter(NOW));
    assertEquals(fromPackument.hasCompletePublishTimes(), fromIndex.hasCompletePublishTimes());
  }

  @Test
  void zeroLeavesExistingBehaviorUnchangedEvenWithoutPublishTimes() {
    Map<String, Object> root = new LinkedHashMap<>();
    NpmMetadata.versions(root).put("1.0.0", version("1.0.0"));
    NpmMetadata.distTags(root).put("latest", "1.0.0");

    Map<String, Object> filtered = NpmMinimumReleaseAge.filter(root, NOW, 0);

    assertTrue(NpmMetadata.versions(filtered).containsKey("1.0.0"));
    assertEquals("1.0.0", NpmMetadata.distTags(filtered).get("latest"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void findsEveryVersionThatSharesATarballFilename() {
    Map<String, Object> root = packument(
        Map.of("1.0.0", "2026-07-18T12:00:00Z", "2.0.0", "2026-07-19T11:30:00Z"),
        Map.of("latest", "2.0.0"));
    Map<String, Object> second = (Map<String, Object>) NpmMetadata.versions(root).get("2.0.0");
    second.put("dist", new LinkedHashMap<>(Map.of(
        "tarball", "https://registry.npmjs.org/demo/-/demo-1.0.0.tgz")));

    assertEquals(
        List.of("1.0.0", "2.0.0"),
        NpmMinimumReleaseAge.versionsForTarball(root, "demo-1.0.0.tgz"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void mismatchedVersionDocumentIdentityFailsClosed() {
    Map<String, Object> root = packument(
        Map.of("1.0.0", "2026-07-17T12:00:00Z"),
        Map.of("latest", "1.0.0"));
    ((Map<String, Object>) NpmMetadata.versions(root).get("1.0.0"))
        .put("version", "9.9.9");
    NpmMinimumReleaseAge.Analysis analysis = NpmMinimumReleaseAge.analyze(root, 60);

    assertFalse(analysis.eligibility("1.0.0", NOW).eligible());
    assertFalse(analysis.hasCompletePublishTimes());
    assertEquals(List.of("1.0.0"), analysis.versionsForTarball("demo-1.0.0.tgz"));
  }

  private static Map<String, Object> packument(
      Map<String, String> publishTimes,
      Map<String, String> distTags) {
    Map<String, Object> root = new LinkedHashMap<>();
    publishTimes.keySet().stream().sorted().forEach(
        version -> NpmMetadata.versions(root).put(version, version(version)));
    root.put(NpmMetadata.TIME, new LinkedHashMap<>(publishTimes));
    root.put(NpmMetadata.DIST_TAGS, new LinkedHashMap<>(distTags));
    return root;
  }

  private static Map<String, Object> version(String version) {
    return new LinkedHashMap<>(Map.of(
        "name", "demo",
        "version", version,
        "dist", Map.of("tarball", "https://registry.npmjs.org/demo/-/demo-" + version + ".tgz")));
  }
}
