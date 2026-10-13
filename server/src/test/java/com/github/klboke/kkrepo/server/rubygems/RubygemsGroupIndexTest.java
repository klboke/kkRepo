package com.github.klboke.kkrepo.server.rubygems;

import static org.junit.jupiter.api.Assertions.*;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class RubygemsGroupIndexTest {
  private final RepositoryRuntime hosted = runtime(1, RepositoryType.HOSTED, List.of());
  private final RepositoryRuntime proxy = runtime(2, RepositoryType.PROXY, List.of());
  private final RepositoryRuntime group = runtime(3, RepositoryType.GROUP, List.of(hosted, proxy));
  private final Map<String, byte[]> responses = new LinkedHashMap<>();
  private final List<String> requests = new ArrayList<>();

  @Test
  void coldProxyIndexesAreVisibleWithoutDownloadingAnyGem() throws Exception {
    put(proxy, "names", "created_at: opaque\n---\npublic\n");
    put(hosted, "names", "private\n");
    put(proxy, "info/public", "---\n2.0.0 dep:~> 1|checksum:abc,ruby:>= 2.7,created_at:2026-01-01T00:00:00Z\n");
    String info = text(index().get("info/public", false));
    put(proxy, "versions", "---\npublic 2.0.0 " + md5(info) + "\n");
    put(hosted, "versions", "---\n");
    assertEquals("---\nprivate\npublic\n", text(index().get("names", false)));
    assertTrue(text(index().get("versions", false)).contains("public 2.0.0 " + md5(info)));
    assertTrue(info.contains("ruby:>= 2.7,created_at:2026-01-01T00:00:00Z"));
    assertTrue(requests.stream().noneMatch(path -> path.contains("gems/")));
  }

  @Test
  void namesWithoutHeaderPreserveNamesEndingInDashes() throws Exception {
    put(hosted, "names", "demo---\nprivate\n");
    put(proxy, "names", "created_at: opaque\r\n---\r\npublic\r\n");
    assertEquals("---\ndemo---\nprivate\npublic\n", text(index().get("names", false)));
  }

  @Test
  void duplicateCoordinatesUseFirstMemberAndVersionsChecksumMatchesMergedInfo() throws Exception {
    put(hosted, "info/demo", "---\n1.0.0 private:~> 1|checksum:first\n");
    put(proxy, "info/demo", "---\n1.0.0 public:~> 2|checksum:second\n2.0.0 |checksum:third\n");
    put(hosted, "versions", "---\ndemo 1.0.0 " + md5(textFor(hosted, "info/demo")) + "\n");
    put(proxy, "versions", "---\ndemo 1.0.0,2.0.0 " + md5(textFor(proxy, "info/demo")) + "\n");
    String info = text(index().get("info/demo", false));
    assertEquals("---\n1.0.0 private:~> 1|checksum:first\n2.0.0 |checksum:third\n", info);
    assertEquals("---\ndemo 1.0.0,2.0.0 " + md5(info) + "\n", text(index().get("versions", false)));
    MavenResponse head = index().get("info/demo", true);
    assertFalse(head.hasBody());
    assertEquals(info.getBytes(StandardCharsets.UTF_8).length, head.contentLength());
    assertEquals('"' + md5(info) + '"', head.etag());
  }

  @Test
  void appendOnlyVersionsApplyYanksAndRepublishesWithinEachMember() throws Exception {
    put(hosted, "versions", "---\n");
    put(proxy, "versions", "---\ndemo 1.0,2.0 " + "a".repeat(32)
        + "\ndemo -1.0,3.0 " + "b".repeat(32) + "\ndemo 1.0,-3.0 " + "c".repeat(32) + "\n");
    assertEquals("---\ndemo 2.0,1.0 " + "c".repeat(32) + "\n", text(index().get("versions", false)));
    assertTrue(requests.stream().noneMatch(path -> path.contains("info/")));
  }

  @Test
  void missingUpstreamChecksumIsRecomputedWithoutBreakingOtherGems() throws Exception {
    put(hosted, "versions", "---\n");
    put(proxy, "versions", "---\ndemo 1.0 \nremoved 2.0 \n");
    put(proxy, "info/demo", "---\n1.0 |checksum:abc\n");
    String info = text(index().get("info/demo", false));
    assertEquals("---\ndemo 1.0 " + md5(info) + "\n", text(index().get("versions", false)));
  }

  @Test
  void missingChecksumFieldWithoutTrailingSpaceIsRecomputed() throws Exception {
    put(hosted, "versions", "---\n");
    put(proxy, "versions", "---\ndemo 1.0\n");
    put(proxy, "info/demo", "---\n1.0 |checksum:abc\n");
    String info = text(index().get("info/demo", false));
    assertEquals("---\ndemo 1.0 " + md5(info) + "\n", text(index().get("versions", false)));
  }

  @Test
  void compactInfoAllowsVersionOnlyAndDependencyOnlyRows() throws Exception {
    put(hosted, "info/demo", "---\n1.0\n2.0 rack:>= 2\n");
    put(proxy, "info/demo", "---\n1.0 ignored:>= 9\n3.0-java |ruby:>= 2.7\n");
    put(hosted, "versions", "---\ndemo 1.0,2.0\n");
    put(proxy, "versions", "---\ndemo 1.0,3.0-java\n");
    String info = "---\n1.0\n2.0 rack:>= 2\n3.0-java |ruby:>= 2.7\n";
    assertEquals(info, text(index().get("info/demo", false)));
    assertEquals("---\ndemo 1.0,2.0,3.0-java " + md5(info) + "\n", text(index().get("versions", false)));
  }

  @Test
  void gemNamesEndingInLegacyIndexNamesKeepTheirEndpointRouting() throws Exception {
    String name = "demo-specs.4.8.gz";
    String info = "---\n1.0\n";
    put(hosted, "info/" + name, info);
    assertEquals(info, text(index().get("info/" + name, false)));
    String path = "api/v1/dependencies?gems=" + name;
    var row = dependency(name, "1.0", "rack", ">= 0");
    responses.put("1/" + path, RubygemsIndexMarshal.dependencies(List.of(row)));
    responses.put("2/" + path, RubygemsIndexMarshal.dependencies(List.of()));
    List<Map<String, Object>> rows = new ArrayList<>();
    try (InputStream input = index().get(path, false).body()) {
      RubygemsIndexMarshal.readDependencies(input, rows::add);
    }
    assertEquals(List.of(row), rows);
  }

  @Test
  void conflictingVersionsFallBackWhenAContributingInfoBecomesUnavailable() throws Exception {
    put(hosted, "versions", "---\ndemo 1.0 " + md5("---\n1.0 |checksum:a\n") + "\n");
    put(proxy, "versions", "---\ndemo 2.0 " + md5("---\n2.0 |checksum:b\n") + "\n");
    put(hosted, "info/demo", "---\n1.0 |checksum:a\n");
    assertEquals(404, index().get("versions", false).status());
    put(proxy, "info/demo", "---\n");
    assertEquals(404, index().get("versions", true).status());
    put(proxy, "info/demo", "---\n2.0 |checksum:b\n");
    assertTrue(text(index().get("versions", false)).contains("demo 1.0,2.0 "));
  }

  @Test
  void soleActiveContributorIsRequiredWhenAnotherMemberHasYankedEveryVersion() throws Exception {
    for (RepositoryRuntime active : List.of(hosted, proxy)) {
      responses.clear();
      RepositoryRuntime yanked = active == hosted ? proxy : hosted;
      put(active, "versions", "---\ndemo 1.0 " + md5("---\n1.0 |checksum:a\n") + "\n");
      put(yanked, "versions", "---\ndemo 2.0 " + "b".repeat(32)
          + "\ndemo -2.0 " + "c".repeat(32) + "\n");
      assertEquals(404, index().get("versions", false).status());
      put(active, "info/demo", "---\n");
      assertEquals(404, index().get("versions", true).status());
      String info = "---\n1.0 |checksum:a\n";
      put(active, "info/demo", info);
      assertEquals("---\ndemo 1.0 " + md5(info) + "\n", text(index().get("versions", false)));
    }
  }

  @Test
  void allYankedMembersDoNotRequireInfoOrResurrectVersions() throws Exception {
    put(hosted, "versions", "---\ndemo 1.0 " + "a".repeat(32)
        + "\ndemo -1.0 " + "b".repeat(32) + "\n");
    put(proxy, "versions", "---\ndemo 2.0 " + "c".repeat(32)
        + "\ndemo -2.0 " + "d".repeat(32) + "\n");
    put(hosted, "info/demo", "---\n1.0 |checksum:a\n");
    assertEquals("---\n", text(index().get("versions", false)));
    assertTrue(requests.stream().noneMatch(path -> path.contains("info/")));
  }

  @Test
  void conflictingInfoMustMatchActiveVersionsBeforePublishingItsChecksum() throws Exception {
    for (RepositoryRuntime active : List.of(hosted, proxy)) {
      responses.clear();
      RepositoryRuntime yanked = active == hosted ? proxy : hosted;
      put(active, "versions", "---\ndemo 1.0 " + md5("---\n1.0 |checksum:a\n") + "\n");
      put(yanked, "versions", "---\ndemo 2.0 " + "b".repeat(32)
          + "\ndemo -2.0 " + md5("---\n") + "\n");
      put(active, "info/demo", "---\n1.0 |checksum:a\n");
      put(yanked, "info/demo", "---\n2.0 |checksum:b\n");
      assertEquals(404, index().get("versions", false).status());
      put(yanked, "info/demo", "---\n");
      String info = text(index().get("info/demo", false));
      assertEquals("---\ndemo 1.0 " + md5(info) + "\n", text(index().get("versions", false)));
      // A partially yanked member or a stale info missing an active version is unsafe too.
      put(active, "versions", "---\ndemo 1.0,1.1 " + md5("---\n1.0 |checksum:a\n1.1 |checksum:c\n") + "\n");
      assertEquals(404, index().get("versions", true).status());
      put(active, "info/demo", "---\n1.0 |checksum:a\n1.1 |checksum:c\n");
      info = text(index().get("info/demo", false));
      assertEquals("---\ndemo 1.0,1.1 " + md5(info) + "\n", text(index().get("versions", false)));
    }
    // Recomputing a single source's missing checksum must not legitimize stale info either.
    responses.clear();
    put(hosted, "versions", "---\n");
    put(proxy, "versions", "---\ndemo 1.0\n");
    put(proxy, "info/demo", "---\n1.0 |checksum:a\n2.0 |checksum:b\n");
    assertEquals(404, index().get("versions", false).status());
  }

  @Test
  void nexusPlatformOmissionsRequireTheMembersExactInfoChecksum() throws Exception {
    String javaInfo = "---\n1.0-java |checksum:a\n";
    String rubyInfo = "---\n2.0 |checksum:b\n";
    put(hosted, "versions", "---\ndemo 2.0 " + md5(rubyInfo) + "\n");
    put(proxy, "versions", "---\ndemo 1.0 " + md5(javaInfo) + "\n");
    put(hosted, "info/demo", rubyInfo); put(proxy, "info/demo", javaInfo);
    String info = text(index().get("info/demo", false));
    assertEquals("---\ndemo 1.0-java,2.0 " + md5(info) + "\n", text(index().get("versions", false)));
    put(proxy, "versions", "---\ndemo 1.0 " + "a".repeat(32) + "\n");
    assertEquals(404, index().get("versions", false).status());
    // An explicitly advertised platform must never expand to a different platform.
    put(proxy, "versions", "---\ndemo 1.0-ruby " + md5(javaInfo) + "\n");
    assertEquals(404, index().get("versions", false).status());
  }

  @Test
  void equalVersionSetsStillRequireAnyAdvertisedMemberChecksum() throws Exception {
    for (RepositoryRuntime changed : List.of(hosted, proxy)) {
      responses.clear();
      RepositoryRuntime stable = changed == hosted ? proxy : hosted;
      String before = "---\n1.0 dep:>= 1|checksum:old\n";
      String after = "---\n1.0 dep:>= 2|checksum:new\n";
      String other = "---\n2.0 |checksum:other\n";
      put(changed, "versions", "---\ndemo 1.0 " + md5(before) + "\n");
      put(stable, "versions", "---\ndemo 2.0 " + md5(other) + "\n");
      put(changed, "info/demo", after); put(stable, "info/demo", other);
      assertEquals(404, index().get("versions", false).status());
      assertEquals(404, index().get("versions", true).status());
      // A genuinely omitted checksum can still be recomputed from matching version rows.
      put(changed, "versions", "---\ndemo 1.0\n");
      String info = text(index().get("info/demo", false));
      assertEquals("---\ndemo 1.0,2.0 " + md5(info) + "\n", text(index().get("versions", false)));
      put(changed, "versions", "---\ndemo 1.0 " + md5(after).toUpperCase(java.util.Locale.ROOT) + "\n");
      assertEquals("---\ndemo 1.0,2.0 " + md5(info) + "\n", text(index().get("versions", false)));
    }
  }

  @Test
  void legacyOnlyMemberDisablesPartialCompactVersions() {
    put(hosted, "versions", "---\nprivate 1.0 " + "a".repeat(32) + "\n");
    assertEquals(404, index().get("versions", false).status());
  }

  @Test
  void versionOrderingHandlesPrereleasesMixedSegmentsAndLargeNumbers() {
    assertTrue(RubygemsGroupIndex.compareVersions("20080902170143", "2.0") > 0);
    assertTrue(RubygemsGroupIndex.compareVersions("1.0.pre.2", "1.0") < 0);
    assertTrue(RubygemsGroupIndex.compareVersions("1.0.a10", "1.0.a2") > 0);
    assertEquals(0, RubygemsGroupIndex.compareVersions("1.0.0", "1"));
    assertEquals(0, RubygemsGroupIndex.compareVersions("1.0.0.a.0", "1.a"));
    assertTrue(RubygemsGroupIndex.compareVersions("1.0.0.a", "1.b") < 0);
  }

  @Test
  void identicalMirrorsDoNotFetchEveryGemInfo() throws Exception {
    String versions = "---\ndemo 1.0,2.0 " + "a".repeat(32) + "\n";
    put(hosted, "versions", versions); put(proxy, "versions", versions);
    assertEquals(versions, text(index().get("versions", false)));
    assertEquals(List.of("1/versions", "2/versions"), requests);
  }

  @Test
  void missingOptionalDependencyApiSignalsClientFallbackInsteadOfPartialResults() throws Exception {
    responses.put("1/api/v1/dependencies?gems=public", RubygemsIndexMarshal.dependencies(List.of()));
    assertEquals(404, index().get("api/v1/dependencies?gems=public", false).status());
  }

  @Test
  void dependencyApiMergesStringTuplesWithMemberPriority() throws Exception {
    var first = dependency("demo", "1.0", "private", "~> 1");
    var second = dependency("demo", "1.0", "public", "~> 2");
    var another = dependency("demo", "2.0", "public", ">= 2, < 3");
    responses.put("1/api/v1/dependencies?gems=demo", RubygemsIndexMarshal.dependencies(List.of(first)));
    responses.put("2/api/v1/dependencies?gems=demo", RubygemsIndexMarshal.dependencies(List.of(second, another)));
    List<Map<String, Object>> rows = new ArrayList<>();
    try (InputStream input = index().get("api/v1/dependencies?gems=demo", false).body()) {
      RubygemsIndexMarshal.readDependencies(input, rows::add);
    }
    assertEquals(List.of(first, another), rows);
  }

  @Test
  void acceptsLegacyHostedDependencyHashesAndOptionalNullRequirements() throws Exception {
    Map<String, Object> legacy = new LinkedHashMap<>();
    legacy.put("name", "demo"); legacy.put("number", "1.0"); legacy.put("platform", "ruby");
    legacy.put("dependencies", Map.of("rack", ">= 2, < 3")); legacy.put("ruby", null);
    byte[] bytes = RubygemsIndexMarshal.dependencies(List.of(legacy));
    List<Map<String, Object>> rows = new ArrayList<>();
    RubygemsIndexMarshal.readDependencies(new ByteArrayInputStream(bytes), rows::add);
    assertEquals(List.of(List.of("rack", ">= 2, < 3")), rows.getFirst().get("dependencies"));
    assertTrue(rows.getFirst().containsKey("ruby"));
    assertNull(rows.getFirst().get("ruby"));
  }

  @Test
  void legacyIndexRoundTripCrossesMultiByteArrayAndStringLengths() throws Exception {
    List<RubygemsIndexMarshal.Spec> expected = new ArrayList<>();
    for (int i = 0; i < 300; i++) expected.add(new RubygemsIndexMarshal.Spec("gem" + "a".repeat(130), "1.0." + i, "ruby"));
    byte[] bytes = RubygemsIndexMarshal.specs(expected);
    assertEquals(expected, specs(MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream", null, null)));
  }

  @Test
  void legacyIndexesMergeColdMembersAndKeepPlatforms() throws Exception {
    var a = new RubygemsIndexMarshal.Spec("demo", "1.0", "ruby");
    var b = new RubygemsIndexMarshal.Spec("demo", "2.0", "ruby");
    var c = new RubygemsIndexMarshal.Spec("demo", "1.0", "java");
    for (String path : List.of("specs.4.8.gz", "latest_specs.4.8.gz", "prerelease_specs.4.8.gz")) {
      responses.put("1/" + path, RubygemsIndexMarshal.specs(List.of(a)));
      responses.put("2/" + path, RubygemsIndexMarshal.specs(List.of(a, b, c)));
      var actual = specs(index().get(path, false));
      assertEquals(path.startsWith("latest") ? List.of(b, c) : List.of(a, b, c), actual);
    }
  }

  @Test
  void disjointMemberSpecsCannotExceedTheAggregateRowBudget() throws Exception {
    responses.put("1/specs.4.8.gz", RubygemsIndexMarshal.specs(List.of(
        new RubygemsIndexMarshal.Spec("a", "1", "ruby"), new RubygemsIndexMarshal.Spec("b", "1", "ruby"))));
    responses.put("2/specs.4.8.gz", RubygemsIndexMarshal.specs(List.of(
        new RubygemsIndexMarshal.Spec("c", "1", "ruby"), new RubygemsIndexMarshal.Spec("d", "1", "ruby"))));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> index(3, 1024).get("specs.4.8.gz", false));
    // The failed request must release its capacity slot for subsequent requests.
    assertEquals(4, specs(index().get("specs.4.8.gz", false)).size());
  }

  @Test
  void identicalSpecMirrorsConsumeTheAggregateBudgetOnlyOnce() throws Exception {
    var expected = List.of(new RubygemsIndexMarshal.Spec("a", "1", "ruby"),
        new RubygemsIndexMarshal.Spec("b", "1", "ruby"));
    byte[] body = RubygemsIndexMarshal.specs(expected);
    responses.put("1/specs.4.8.gz", body); responses.put("2/specs.4.8.gz", body);
    assertEquals(expected, specs(index(2, 100).get("specs.4.8.gz", false)));
  }

  @Test
  void disjointMemberSpecsCannotExceedTheAggregatePayloadBudget() throws Exception {
    responses.put("1/specs.4.8.gz", RubygemsIndexMarshal.specs(List.of(
        new RubygemsIndexMarshal.Spec("a".repeat(100), "1", "ruby"))));
    assertEquals(1, specs(index(10, 200).get("specs.4.8.gz", false)).size());
    responses.put("2/specs.4.8.gz", RubygemsIndexMarshal.specs(List.of(
        new RubygemsIndexMarshal.Spec("b".repeat(100), "1", "ruby"))));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> index(10, 200).get("specs.4.8.gz", false));
  }

  @Test
  void compactAndDependencyIndexesAlsoBoundRetainedAggregateRows() throws Exception {
    put(hosted, "names", "a\n"); put(proxy, "names", "b\n");
    put(hosted, "versions", "---\na 1 " + "a".repeat(32) + "\n");
    put(proxy, "versions", "---\nb 1 " + "b".repeat(32) + "\n");
    put(hosted, "info/demo", "---\n1 |checksum:a\n");
    put(proxy, "info/demo", "---\n2 |checksum:b\n");
    responses.put("1/api/v1/dependencies?gems=demo", RubygemsIndexMarshal.dependencies(
        List.of(dependency("demo", "1", "a", ">= 0"))));
    responses.put("2/api/v1/dependencies?gems=demo", RubygemsIndexMarshal.dependencies(
        List.of(dependency("demo", "2", "b", ">= 0"))));
    for (String path : List.of("names", "versions", "info/demo", "api/v1/dependencies?gems=demo")) {
      assertThrows(MavenExceptions.BadUpstreamException.class, () -> index(1, 1024).get(path, false), path);
    }
  }

  @Test
  void identicalInfoMirrorsPreserveOriginalBytesWithinTheBudget() throws Exception {
    String info = "created_at: opaque\n---\n1.0 |checksum:abc\n";
    put(hosted, "info/demo", info); put(proxy, "info/demo", info);
    assertEquals(info, text(index(1, 100).get("info/demo", false)));
  }

  @Test
  void mergingConflictingVersionsMustStillFitTheAggregatePayloadBudget() {
    put(hosted, "versions", "---\ndemo 1 " + "a".repeat(32) + "\nother 1 " + "b".repeat(32) + "\n");
    put(proxy, "versions", "---\ndemo 2 " + "c".repeat(32) + "\n");
    put(hosted, "info/demo", "---\n1 |checksum:a\n");
    put(proxy, "info/demo", "---\n2 |checksum:b\n");
    // Initial rows fit; replacing demo's version list with "1,2" crosses the byte budget.
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> index(10, 316).get("versions", false));
  }

  @Test
  void dependencyPayloadBudgetIncludesNestedRequirementStringsAndClosesInput() throws Exception {
    byte[] body = RubygemsIndexMarshal.dependencies(
        List.of(dependency("demo", "1", "dependency".repeat(50), ">= 0")));
    boolean[] closed = {false};
    var index = new RubygemsGroupIndex(group, (member, path) -> MavenResponse.ok(new ByteArrayInputStream(body) {
      @Override public void close() { closed[0] = true; }
    }, body.length, "application/octet-stream", null, null), 10, 200);
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> index.get("api/v1/dependencies?gems=demo", false));
    assertTrue(closed[0]);
  }

  @Test
  void readsGenuineRubyMarshalObjectAndSymbolLinks() throws Exception {
    try (InputStream raw = getClass().getResourceAsStream("/rubygems/specs-with-links.4.8.gz");
        InputStream input = new GZIPInputStream(raw)) {
      List<RubygemsIndexMarshal.Spec> rows = new ArrayList<>();
      RubygemsIndexMarshal.readSpecs(input, rows::add);
      assertEquals(List.of(new RubygemsIndexMarshal.Spec("demo", "1.0.0", "ruby"),
          new RubygemsIndexMarshal.Spec("demo", "1.0.0", "java"),
          new RubygemsIndexMarshal.Spec("other", "20080902170143", "ruby")), rows);
    }
  }

  @Test
  void rejectsUnknownRubyClassesTruncationCyclesAndOversizedAllocation() {
    for (byte[] invalid : List.of(new byte[] {4, 8, '[', 6, 'u'},
        new byte[] {4, 8, '[', 6, '@', 0}, new byte[] {4, 8, '[', 6, '[', 6},
        new byte[] {4, 8, '[', 4, -1, -1, -1, 127})) {
      assertThrows(Exception.class, () -> RubygemsIndexMarshal.readSpecs(new ByteArrayInputStream(invalid), row -> fail()));
    }
  }

  @Test
  void interruptedLargeIndexRequestReturnsRetryableResponseAndPreservesInterrupt() {
    for (String path : List.of("specs.4.8.gz", "versions", "names", "info/demo",
        "api/v1/dependencies", "api/v1/dependencies?gems=demo")) {
      Thread.currentThread().interrupt();
      try {
        MavenResponse response = index().get(path, false);
        assertEquals(503, response.status(), path);
        assertEquals("1", response.headers().get("Retry-After"));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void dependencyRequestsShareCapacityBeforeReadingAnyMember() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var secondStarted = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var first = new AtomicReference<MavenResponse>();
    var second = new AtomicReference<MavenResponse>();
    var failure = new AtomicReference<Throwable>();
    byte[] body = RubygemsIndexMarshal.dependencies(List.of(dependency("demo", "1", "rack", ">= 0")));
    var single = runtime(4, RepositoryType.GROUP, List.of(hosted));
    var index = new RubygemsGroupIndex(single, (member, path) -> {
      if (calls.incrementAndGet() == 1) {
        entered.countDown();
        try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timed out"); }
        catch (InterruptedException e) { throw new IllegalStateException(e); }
      }
      return MavenResponse.ok(new ByteArrayInputStream(body), body.length, "application/octet-stream", null, null);
    });
    Thread owner = Thread.startVirtualThread(() -> {
      try { first.set(index.get("api/v1/dependencies", false)); }
      catch (Throwable e) { failure.set(e); }
    });
    Thread waiter = null;
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      waiter = Thread.startVirtualThread(() -> {
        secondStarted.countDown();
        try { second.set(index.get("api/v1/dependencies?gems=demo", false)); }
        catch (Throwable e) { failure.set(e); }
      });
      assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
      waiter.interrupt();
      waiter.join(5000);
      assertFalse(waiter.isAlive());
      assertNull(failure.get());
      assertNotNull(second.get());
      assertEquals(503, second.get().status());
      assertEquals(1, calls.get(), "the waiting request must not allocate a member index");
    } finally {
      release.countDown();
      owner.join(5000);
      if (waiter != null && waiter.isAlive()) { waiter.interrupt(); waiter.join(5000); }
      if (first.get() != null) first.get().closeBodyIfOpen();
      if (second.get() != null) second.get().closeBodyIfOpen();
    }
    assertFalse(owner.isAlive());
    assertNull(failure.get());
    assertEquals(200, first.get().status());
  }

  @Test
  void closesRejectedAndConsumedMemberBodies() {
    boolean[] closed = {false};
    var index = new RubygemsGroupIndex(group, (member, path) -> MavenResponse.ok(new ByteArrayInputStream(new byte[0]) {
      @Override public void close() { closed[0] = true; }
    }, 0, "text/plain", null, null).withStatus(404));
    assertEquals(404, index.get("versions", false).status());
    assertTrue(closed[0]);
  }

  @Test
  void malformedUpstreamFailsWithGatewayErrorInsteadOfReturningCorruptIndex() {
    put(hosted, "versions", "---\n");
    put(proxy, "versions", "---\nbad\n");
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> index().get("versions", false));
  }

  private RubygemsGroupIndex index() {
    return index(RubygemsIndexMarshal.MAX_ROWS, RubygemsIndexMarshal.MAX_EXPANDED_BYTES);
  }
  private RubygemsGroupIndex index(int maxRows, long maxBytes) {
    return new RubygemsGroupIndex(group, (member, path) -> {
      String key = member.id() + "/" + path; requests.add(key);
      byte[] bytes = responses.get(key);
      return bytes == null ? MavenResponse.noBody(404)
          : MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream", null, null);
    }, maxRows, maxBytes);
  }
  private void put(RepositoryRuntime member, String path, String body) {
    responses.put(member.id() + "/" + path, body.getBytes(StandardCharsets.UTF_8));
  }
  private String textFor(RepositoryRuntime member, String path) {
    return new String(responses.get(member.id() + "/" + path), StandardCharsets.UTF_8);
  }
  private static String text(MavenResponse response) throws IOException {
    try (InputStream body = response.body()) { return new String(body.readAllBytes(), StandardCharsets.UTF_8); }
  }
  private static List<RubygemsIndexMarshal.Spec> specs(MavenResponse response) throws IOException {
    List<RubygemsIndexMarshal.Spec> rows = new ArrayList<>();
    try (InputStream raw = response.body(); InputStream input = new GZIPInputStream(raw)) {
      RubygemsIndexMarshal.readSpecs(input, rows::add);
    }
    return rows;
  }
  private static String md5(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
  private static Map<String, Object> dependency(String name, String version, String dep, String requirement) {
    return Map.of("name", name, "number", version, "platform", "ruby", "dependencies", List.of(List.of(dep, requirement)));
  }
  private static RepositoryRuntime runtime(long id, RepositoryType type, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(id, "ruby-" + id, RepositoryFormat.RUBYGEMS, type, "rubygems-" + type.name().toLowerCase(),
        true, 1L, null, null, null, true, "https://rubygems.org/", 1440, 1440, true, null, members);
  }
}
