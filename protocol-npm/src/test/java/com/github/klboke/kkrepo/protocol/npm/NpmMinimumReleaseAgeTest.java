package com.github.klboke.kkrepo.protocol.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
  void disabledAnalysisCoversKnownAndUnknownVersionsWithoutTransitions() {
    Map<String, Object> root = new LinkedHashMap<>();
    NpmMetadata.versions(root).put("1.0.0", version("1.0.0"));
    NpmMetadata.distTags(root).put("latest", "1.0.0");
    NpmMinimumReleaseAge.Analysis analysis = NpmMinimumReleaseAge.analyze(root, 0);

    assertTrue(analysis.eligibility("1.0.0", null).eligible());
    assertFalse(analysis.eligibility("missing", NOW).eligible());
    assertEquals(Map.of("latest", "1.0.0"), analysis.filteredDistTags(root, NOW));
    assertEquals(List.of("1.0.0"), analysis.visibleVersions(null));
    assertEquals(1, analysis.visibilityGeneration(null));
    assertTrue(analysis.nextMaturityAfter(NOW).isEmpty());
    assertFalse(analysis.crossedMaturityBoundary(Instant.EPOCH, NOW));
    assertEquals(1, analysis.versionCount());
    assertTrue(analysis.cacheWeight() >= 1);
  }

  @Test
  void malformedVersionShapesAndPublishTimesFailClosedWithoutInventingTarballs() {
    Map<String, Object> root = new LinkedHashMap<>();
    Map<String, Object> versions = NpmMetadata.versions(root);
    versions.put("1.0.0", "not-an-object");
    versions.put("1.1.0", new LinkedHashMap<>(Map.of(
        "version", "1.1.0", "dist", "not-an-object")));
    versions.put("1.2.0", new LinkedHashMap<>(Map.of(
        "version", "1.2.0", "dist", Map.of())));
    versions.put("1.3.0", version("1.3.0"));
    root.put(NpmMetadata.TIME, new LinkedHashMap<>(Map.of(
        "1.0.0", "2026-07-17T12:00:00Z",
        "1.1.0", "2026-07-17T12:00:00Z",
        "1.2.0", "",
        "1.3.0", "not-a-time")));

    NpmMinimumReleaseAge.ReleaseIndex index = NpmMinimumReleaseAge.index(root);
    NpmMinimumReleaseAge.Analysis analysis = NpmMinimumReleaseAge.analyze(index, 60);

    assertTrue(analysis.eligibility("1.0.0", NOW).eligible());
    assertTrue(analysis.eligibility("1.1.0", NOW).eligible());
    assertFalse(analysis.eligibility("1.2.0", NOW).eligible());
    assertFalse(analysis.eligibility("1.3.0", NOW).eligible());
    assertTrue(analysis.versionsForTarball("anything.tgz").isEmpty());
    assertFalse(analysis.hasCompletePublishTimes());
  }

  @Test
  void overflowingAvailabilityAndNullEvaluationFailClosed() {
    NpmMinimumReleaseAge.Analysis overflow = NpmMinimumReleaseAge.analyze(
        new NpmMinimumReleaseAge.ReleaseIndex(List.of(
            new NpmMinimumReleaseAge.IndexedRelease(
                "1.0.0", Instant.MAX, null, "demo.tgz"))),
        1);
    NpmMinimumReleaseAge.Analysis ordinary = NpmMinimumReleaseAge.analyze(
        new NpmMinimumReleaseAge.ReleaseIndex(List.of(
            new NpmMinimumReleaseAge.IndexedRelease(
                "2.0.0", Instant.parse("2026-07-19T10:00:00Z"), null, null))),
        60);

    assertFalse(overflow.eligibility("1.0.0", NOW).eligible());
    assertEquals("invalid publish time", overflow.eligibility("1.0.0", NOW).reason());
    assertFalse(overflow.hasCompletePublishTimes());
    assertFalse(ordinary.eligibility("2.0.0", null).eligible());
    assertEquals("release has not reached the minimum age",
        ordinary.eligibility("2.0.0", null).reason());
  }

  @Test
  @SuppressWarnings("unchecked")
  void repairsStablePrereleaseAndCustomTagsUsingEligibleCompatibleVersions() {
    Map<String, Object> root = packument(
        Map.of(
            "1.0.0", "2026-07-17T12:00:00Z",
            "1.1.0", "2026-07-17T12:00:00Z",
            "1.2.0", "2026-07-19T11:30:01Z",
            "2.0.0-alpha.1", "2026-07-17T12:00:00Z",
            "2.0.0-beta.1", "2026-07-19T11:30:01Z",
            "999999999999999999.0.0-beta.1", "2026-07-19T11:30:01Z"),
        Map.of(
            "latest", "1.2.0",
            "next", "2.0.0-beta.1",
            "overflow", "999999999999999999.0.0-beta.1"));
    ((Map<String, Object>) NpmMetadata.versions(root).get("1.0.0"))
        .put("deprecated", "use 1.0.0");
    NpmMetadata.distTags(root).put("stable", "1.0.0");
    NpmMetadata.distTags(root).put("missing", null);

    Map<String, Object> tags = NpmMinimumReleaseAge.analyze(root, 60)
        .filteredDistTags(root, NOW);

    assertEquals("1.1.0", tags.get("latest"));
    assertEquals("2.0.0-alpha.1", tags.get("next"));
    assertEquals("2.0.0-alpha.1", tags.get("overflow"));
    assertEquals("1.0.0", tags.get("stable"));
    assertFalse(tags.containsKey("missing"));
  }

  @Test
  void handlesMissingTimeMapNullTarballAndEmptyReleaseIndex() {
    Map<String, Object> root = new LinkedHashMap<>();
    NpmMetadata.versions(root).put("1.0.0", version("1.0.0"));
    root.put(NpmMetadata.TIME, "not-an-object");
    NpmMinimumReleaseAge.Analysis analysis = NpmMinimumReleaseAge.analyze(root, 60);
    Map<String, Object> copy = NpmMetadata.deepCopy(root);

    assertFalse(analysis.eligibility("1.0.0", NOW).eligible());
    assertSame(copy, analysis.filterPreparedCopy(copy, NOW));
    assertEquals("not-an-object", copy.get(NpmMetadata.TIME));
    assertEquals(0, analysis.visibilityGeneration(null));
    assertTrue(analysis.nextMaturityAfter(null).isEmpty());
    assertTrue(analysis.versionsForTarball(null).isEmpty());
    assertTrue(new NpmMinimumReleaseAge.ReleaseIndex(null).releases().isEmpty());
    assertEquals(1, NpmMinimumReleaseAge.analyze(
        new NpmMinimumReleaseAge.ReleaseIndex(null), 60).cacheWeight());
  }

  @Test
  void validatesIndexedReleaseIdentity() {
    assertThrows(IllegalArgumentException.class,
        () -> new NpmMinimumReleaseAge.IndexedRelease(null, null, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new NpmMinimumReleaseAge.IndexedRelease(" ", null, null, null));
    assertNull(new NpmMinimumReleaseAge.IndexedRelease(
        "1.0.0", null, null, null).tarballName());
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

  @Test
  void nullPublishDataAndLeapSecondTimestampFailOrParseDeterministically() {
    NpmMinimumReleaseAge.Analysis missing = NpmMinimumReleaseAge.analyze(
        new NpmMinimumReleaseAge.ReleaseIndex(List.of(
            new NpmMinimumReleaseAge.IndexedRelease("1.0.0", null, null, null))),
        60);
    Map<String, Object> leapSecond = packument(
        Map.of("2.0.0", "2016-12-31T23:59:60Z"), Map.of("latest", "2.0.0"));

    assertEquals("invalid publish time", missing.eligibility("1.0.0", NOW).reason());
    assertFalse(missing.hasCompletePublishTimes());
    assertTrue(NpmMinimumReleaseAge.analyze(leapSecond, 60)
        .eligibility("2.0.0", NOW).eligible());
  }

  @Test
  void tagRepairHandlesLegacyVersionDocumentsAndNonSemverTargets() {
    Map<String, Object> root = new LinkedHashMap<>();
    NpmMetadata.versions(root).put("1.0.0-alpha.1", "legacy-version-document");
    NpmMetadata.versions(root).put("2.0.0-alpha.1", version("2.0.0-alpha.1"));
    NpmMetadata.versions(root).put("release-channel", version("release-channel"));
    root.put(NpmMetadata.TIME, new LinkedHashMap<>(Map.of(
        "1.0.0-alpha.1", "2026-07-17T12:00:00Z",
        "2.0.0-alpha.1", "2026-07-17T12:00:00Z",
        "release-channel", "2026-07-19T11:30:00Z")));
    root.put(NpmMetadata.DIST_TAGS, new LinkedHashMap<>(Map.of(
        "next", "release-channel")));

    Map<String, Object> tags = NpmMinimumReleaseAge.analyze(root, 60)
        .filteredDistTags(root, NOW);

    assertEquals("2.0.0-alpha.1", tags.get("next"));
  }

  @Test
  void maturityBoundaryChecksEveryNullAndOrderingGuard() {
    NpmMinimumReleaseAge.Analysis analysis = NpmMinimumReleaseAge.analyze(
        packument(Map.of("1.0.0", "2026-07-19T11:00:00Z"), Map.of("latest", "1.0.0")),
        60);

    assertFalse(analysis.crossedMaturityBoundary(null, NOW));
    assertFalse(analysis.crossedMaturityBoundary(Instant.EPOCH, null));
    assertFalse(analysis.crossedMaturityBoundary(NOW, NOW.minusSeconds(1)));
    assertFalse(analysis.crossedMaturityBoundary(NOW, NOW));
    assertEquals(0, analysis.visibilityGeneration(null));
    assertEquals(0, NpmMinimumReleaseAge.analyze(
        new NpmMinimumReleaseAge.ReleaseIndex(List.of()), 60).visibilityGeneration(NOW));
  }

  @Test
  @SuppressWarnings("unchecked")
  void tagRepairTreatsBlankDeprecationAsActiveAndHandlesNoEligibleCandidate() {
    Map<String, Object> root = packument(
        Map.of(
            "1.0.0", "2026-07-17T12:00:00Z",
            "2.0.0", "2026-07-17T12:00:00Z",
            "3.0.0", "2026-07-19T11:30:00Z"),
        Map.of("latest", "3.0.0", "three", "3.0.0"));
    ((Map<String, Object>) NpmMetadata.versions(root).get("2.0.0"))
        .put("deprecated", " ");

    Map<String, Object> tags = NpmMinimumReleaseAge.analyze(root, 60)
        .filteredDistTags(root, NOW);

    assertEquals("2.0.0", tags.get("latest"));
    assertFalse(tags.containsKey("three"));

    Map<String, Object> allImmature = packument(
        Map.of("4.0.0", "2026-07-19T11:30:00Z"), Map.of("latest", "4.0.0"));
    assertTrue(NpmMinimumReleaseAge.analyze(allImmature, 60)
        .filteredDistTags(allImmature, NOW).isEmpty());
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
