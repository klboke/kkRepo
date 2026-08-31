package com.github.klboke.kkrepo.protocol.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HelmIndexTest {
  @Test
  void buildsSortedHostedIndexAndFiltersInvalidCoordinates() {
    byte[] body = HelmIndex.buildHosted(List.of(
        chart("zeta", "1.0.0"),
        chart("demo", "1.0.0"),
        chart("demo", "2.0.0"),
        chart("", "3.0.0"),
        chart("ignored", null),
        chart("legacy", "latest"),
        chart("unsafe:chart", "1.0.0")),
        Instant.parse("2026-07-13T00:00:00Z"));

    assertEquals(List.of(
        new HelmIndex.Entry("demo", "2.0.0", List.of("demo-2.0.0.tgz")),
        new HelmIndex.Entry("demo", "1.0.0", List.of("demo-1.0.0.tgz")),
        new HelmIndex.Entry("zeta", "1.0.0", List.of("zeta-1.0.0.tgz"))),
        HelmIndex.entries(body));
    assertTrue(text(body).contains("generated: \"2026-07-13T00:00:00Z\""));
  }

  @Test
  void writesOptionalFieldsDefaultsAndEscapedStrings() {
    HelmIndex.ChartRecord chart = new HelmIndex.ChartRecord(
        "demo",
        "1.0.0",
        " ",
        "quoted \"value\"\nnext",
        "1.2",
        "https://example.test/icon.png",
        Instant.parse("2026-07-13T01:02:03Z"),
        "sha256-demo",
        Arrays.asList("demo-1.0.0.tgz", null, " "),
        List.of("https://example.test/source"),
        Arrays.asList(null, Map.of(),
            Map.of("name", "ops", "email", "ops@example.test", "blank", " ")));

    String yaml = text(HelmIndex.buildHosted(List.of(chart), null));

    assertTrue(yaml.contains("apiVersion: \"v1\""));
    assertTrue(yaml.contains("description: \"quoted \\\"value\\\"\\nnext\""));
    assertTrue(yaml.contains("created: \"2026-07-13T01:02:03Z\""));
    assertTrue(yaml.contains("digest: \"sha256-demo\""));
    assertTrue(yaml.contains("name: \"ops\""));
    assertTrue(yaml.contains("email: \"ops@example.test\""));
    assertFalse(yaml.contains("blank:"));
    assertEquals(1, HelmIndex.entries(yaml.getBytes(StandardCharsets.UTF_8)).size());
  }

  @Test
  void rewritesRelativeAbsoluteProvenanceAndFallbackUrls() {
    byte[] upstream = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.2.3
              urls:
                - charts/demo-original.tgz
                - https://cdn.example.test/demo-original.tgz.prov
                - https://cdn.example.test/demo-original.zip
          fallback:
            - apiVersion: v1
              name: fallback
              version: 2.0.0
              urls:
                - fallback.tgz
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.RewriteResult result = HelmIndex.rewriteProxyIndex(
        upstream, "https://repo.example.test/helm");

    assertEquals(
        "https://repo.example.test/helm/charts/demo-original.tgz",
        result.remoteUrlsByLocalPath().get("demo-1.2.3.tgz"));
    assertEquals(
        "https://cdn.example.test/demo-original.tgz.prov",
        result.remoteUrlsByLocalPath().get("demo-1.2.3.tgz.prov"));
    assertEquals(
        "https://repo.example.test/helm/fallback.tgz",
        result.remoteUrlsByLocalPath().get("fallback-2.0.0.tgz"));
    assertEquals(
        "https://repo.example.test/helm/fallback.tgz.prov",
        result.remoteUrlsByLocalPath().get("fallback-2.0.0.tgz.prov"));
    assertEquals(4, result.remoteUrlsByLocalPath().size());
    assertEquals(
        List.of(
            new HelmIndex.Entry("demo", "1.2.3", List.of("demo-1.2.3.tgz")),
            new HelmIndex.Entry("fallback", "2.0.0", List.of("fallback-2.0.0.tgz"))),
        HelmIndex.entries(result.body()));
  }

  @Test
  void proxyRewriteKeepsTheFirstReleaseWhenCoordinatesGenerateTheSamePath() {
    byte[] upstream = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1-2.0
              digest: first-digest
              urls: [charts/first.tgz]
          demo-1:
            - apiVersion: v2
              name: demo-1
              version: '2.0'
              digest: colliding-digest
              urls: [charts/colliding.tgz]
          safe:
            - apiVersion: v2
              name: safe
              version: 3.0.0
              digest: safe-digest
              urls: [charts/safe.tgz]
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.RewriteResult rewritten = HelmIndex.rewriteProxyIndex(
        upstream, "https://repo.example.test/root/");

    assertEquals(List.of(
        new HelmIndex.Entry("demo", "1-2.0", List.of("demo-1-2.0.tgz")),
        new HelmIndex.Entry("safe", "3.0.0", List.of("safe-3.0.0.tgz"))),
        HelmIndex.entries(rewritten.body()));
    assertEquals(
        "https://repo.example.test/root/charts/first.tgz",
        rewritten.remoteUrlsByLocalPath().get("demo-1-2.0.tgz"));
    assertFalse(text(rewritten.body()).contains("colliding-digest"));
    assertFalse(rewritten.remoteUrlsByLocalPath().containsValue(
        "https://repo.example.test/root/charts/colliding.tgz"));
  }

  @Test
  void mergesGroupIndexesInMemberOrderAndKeepsUniqueReleases() {
    byte[] first = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              appVersion: first-member
              urls:
                - https://first.example.test/charts/original.tgz
            - apiVersion: v2
              name: demo
              version: 0.9.0
              urls:
                - demo-0.9.0.tgz
        """.getBytes(StandardCharsets.UTF_8);
    byte[] second = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              appVersion: second-member
              urls:
                - demo-1.0.0.tgz
            - apiVersion: v2
              name: demo
              version: 2.0.0
              urls:
                - charts/demo-current.tgz
          other:
            - apiVersion: v2
              name: other
              version: 3.0.0
              urls:
                - other-3.0.0.tgz
        """.getBytes(StandardCharsets.UTF_8);

    byte[] merged = HelmIndex.mergeGroupIndexes(
        List.of(first, second), Instant.parse("2026-08-30T00:00:00Z"));

    assertEquals(List.of(
        new HelmIndex.Entry("demo", "1.0.0", List.of("demo-1.0.0.tgz")),
        new HelmIndex.Entry("demo", "0.9.0", List.of("demo-0.9.0.tgz")),
        new HelmIndex.Entry("demo", "2.0.0", List.of("demo-2.0.0.tgz")),
        new HelmIndex.Entry("other", "3.0.0", List.of("other-3.0.0.tgz"))),
        HelmIndex.entries(merged));
    assertTrue(text(merged).contains("appVersion: first-member"));
    assertFalse(text(merged).contains("second-member"));
    assertFalse(text(merged).contains("https://first.example.test"));
    assertTrue(text(merged).contains("generated: '2026-08-30T00:00:00Z'"));
  }

  @Test
  void letsLaterMembersWinWhenEarlierReleaseHasNoChartArchiveUrl() {
    byte[] unusable = """
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              appVersion: unusable
              urls: ["http://[/demo.tgz", demo.zip, demo.tgz.prov]
        """.getBytes(StandardCharsets.UTF_8);
    byte[] usable = """
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              appVersion: usable
              urls: [charts/original.tgz, ignored.zip]
        """.getBytes(StandardCharsets.UTF_8);

    byte[] merged = HelmIndex.mergeGroupIndexes(List.of(unusable, usable), Instant.EPOCH);

    assertEquals(
        List.of(new HelmIndex.Entry("demo", "1.0.0", List.of("demo-1.0.0.tgz"))),
        HelmIndex.entries(merged));
    assertTrue(text(merged).contains("appVersion: usable"));
    assertFalse(text(merged).contains("appVersion: unusable"));
    assertFalse(text(merged).contains("ignored.zip"));
  }

  @Test
  void ignoresEmptyAndMalformedGroupEntries() {
    byte[] malformed = """
        entries:
          ignored: not-a-list
          empty: []
        """.getBytes(StandardCharsets.UTF_8);

    byte[] merged = HelmIndex.mergeGroupIndexes(
        Arrays.asList(null, new byte[0], "[]".getBytes(StandardCharsets.UTF_8), malformed), null);

    assertEquals(List.of(), HelmIndex.entries(merged));
    assertTrue(text(merged).contains("generated:"));
  }

  @Test
  void groupMergeDropsCoordinatesThatWouldCreateAmbiguousLocalUrls() {
    byte[] unsafe = """
        entries:
          'demo?channel':
            - apiVersion: v2
              name: 'demo?channel'
              version: 1.0.0
              urls: [demo.tgz]
          'demo:channel':
            - apiVersion: v2
              name: 'demo:channel'
              version: 1.0.0
              urls: [demo.tgz]
          demo:
            - apiVersion: v2
              name: demo
              version: '1.0.0%2fescape'
              urls: [demo.tgz]
          safe:
            - apiVersion: v2
              name: safe
              version: 1.0.0+build
              urls: [safe.tgz]
        """.getBytes(StandardCharsets.UTF_8);

    byte[] merged = HelmIndex.mergeGroupIndexes(List.of(unsafe), Instant.EPOCH);

    assertEquals(
        List.of(new HelmIndex.Entry(
            "safe", "1.0.0+build", List.of("safe-1.0.0+build.tgz"))),
        HelmIndex.entries(merged));
  }

  @Test
  void groupMergeKeepsTheFirstReleaseWhenDifferentCoordinatesGenerateTheSamePath() {
    byte[] first = """
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1-2.0
              digest: first-digest
              urls: [charts/first.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    byte[] second = """
        entries:
          demo-1:
            - apiVersion: v2
              name: demo-1
              version: 2.0
              digest: colliding-digest
              urls: [charts/colliding.tgz]
          safe:
            - apiVersion: v2
              name: safe
              version: 3.0.0
              digest: safe-digest
              urls: [charts/safe.tgz]
        """.getBytes(StandardCharsets.UTF_8);

    byte[] merged = HelmIndex.mergeGroupIndexes(List.of(first, second), Instant.EPOCH);

    assertEquals(List.of(
        new HelmIndex.Entry("demo", "1-2.0", List.of("demo-1-2.0.tgz")),
        new HelmIndex.Entry("safe", "3.0.0", List.of("safe-3.0.0.tgz"))),
        HelmIndex.entries(merged));
    HelmIndex.Release selected = HelmIndex.releaseForPath(merged, "demo-1-2.0.tgz")
        .orElseThrow();
    assertEquals("demo", selected.name());
    assertEquals("1-2.0", selected.version());
    assertEquals("first-digest", selected.digest());
    assertFalse(text(merged).contains("colliding-digest"));
  }

  @Test
  void derivesProvenanceBeforeChartUrlQueryAndFragment() {
    byte[] upstream = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              urls:
                - charts/demo.tgz?download=1#release
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.RewriteResult rewritten = HelmIndex.rewriteProxyIndex(
        upstream, "https://repo.example.test/helm");

    assertEquals(
        "https://repo.example.test/helm/charts/demo.tgz.prov?download=1#release",
        rewritten.remoteUrlsByLocalPath().get("demo-1.0.0.tgz.prov"));
  }

  @Test
  void resolvesChartAndDerivedProvenanceToTheExactAdvertisedRelease() {
    byte[] selected = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              digest: BBBB
              urls: [charts/original-name.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    HelmIndex.ValidatedIndex validated = HelmIndex.parseValidatedIndex(selected);
    HelmIndex.Release release = validated.releaseForPath("demo-1.0.0.tgz")
        .orElseThrow();

    assertEquals(
        new HelmIndex.Release("demo", "1.0.0", "BBBB", List.of("demo-1.0.0.tgz")),
        release);
    assertEquals(
        release,
        validated.releaseForPath("/demo-1.0.0.tgz.prov").orElseThrow());
    assertTrue(validated.containsRelease(release, "demo-1.0.0.tgz"));
    assertTrue(HelmIndex.containsRelease(selected, release, "demo-1.0.0.tgz"));
    assertTrue(HelmIndex.containsRelease(selected, release, "demo-1.0.0.tgz.prov"));
  }

  @Test
  void rejectsSameCoordinatesWhenMemberDigestDoesNotMatchTheGroupWinner() {
    byte[] selected = """
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              digest: digest-b
              urls: [demo-1.0.0.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    byte[] conflicting = """
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              digest: digest-a
              urls: [demo-1.0.0.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    HelmIndex.Release release = HelmIndex.releaseForPath(selected, "demo-1.0.0.tgz")
        .orElseThrow();

    assertFalse(HelmIndex.containsRelease(conflicting, release, "demo-1.0.0.tgz"));
  }

  @Test
  void treatsOptionalSha256DigestPrefixesAsTheSameRelease() {
    byte[] prefixed = """
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              digest: sha256:BBBB
              urls: [demo-1.0.0.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    byte[] bare = """
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              digest: bbbb
              urls: [demo-1.0.0.tgz]
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.Release prefixedRelease = HelmIndex.releaseForPath(
        prefixed, "demo-1.0.0.tgz").orElseThrow();
    HelmIndex.Release bareRelease = HelmIndex.releaseForPath(
        bare, "demo-1.0.0.tgz").orElseThrow();

    assertTrue(HelmIndex.containsRelease(bare, prefixedRelease, "demo-1.0.0.tgz"));
    assertTrue(HelmIndex.containsRelease(prefixed, bareRelease, "demo-1.0.0.tgz"));
  }

  @Test
  void releaseLookupSkipsInvalidCoordinatesAndHandlesAbsentPathsWithoutDigest() {
    byte[] body = """
        entries:
          ignored: not-a-list
          broken:
            - name: ""
              version: ""
              urls: [broken.tgz]
          demo:
            - name: demo
              version: 1.0.0
              urls: [charts/original.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    HelmIndex.Release expected = new HelmIndex.Release(
        "demo", "1.0.0", null, List.of("demo-1.0.0.tgz"));

    assertTrue(HelmIndex.containsRelease(body, expected, "demo-1.0.0.tgz"));
    assertFalse(HelmIndex.containsRelease(body, null, "demo-1.0.0.tgz"));
    assertTrue(HelmIndex.releaseForPath(body, "missing-1.0.0.tgz").isEmpty());
    assertTrue(HelmIndex.releaseForPath(body, null).isEmpty());
  }

  @Test
  void rejectsUnserviceableUrlsAndKeepsTolerantReadHelpers() {
    byte[] malformedUrl = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              urls:
                - "http://[/demo.tgz"
        """.getBytes(StandardCharsets.UTF_8);

    assertThrows(
        IllegalArgumentException.class,
        () -> HelmIndex.rewriteProxyIndex(malformedUrl, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> HelmIndex.validateIndex(malformedUrl));
    assertEquals(List.of(), HelmIndex.entries("[]".getBytes(StandardCharsets.UTF_8)));
    assertEquals(List.of(), HelmIndex.entries("entries: []".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void dropsMalformedChartReferencesWhenAResolvableAlternativeExists() {
    byte[] mixedUrls = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              urls:
                - "http://[/demo.tgz"
                - charts/demo.tgz
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.RewriteResult rewritten = HelmIndex.rewriteProxyIndex(
        mixedUrls, "https://repo.example.test/root/");

    assertEquals(
        List.of(new HelmIndex.Entry("demo", "1.0.0", List.of("demo-1.0.0.tgz"))),
        HelmIndex.entries(rewritten.body()));
    assertEquals(
        "https://repo.example.test/root/charts/demo.tgz",
        rewritten.remoteUrlsByLocalPath().get("demo-1.0.0.tgz"));
    assertFalse(rewritten.remoteUrlsByLocalPath().containsValue("http://[/demo.tgz"));
  }

  @Test
  void rejectsUnsupportedAbsoluteChartUrlSchemes() {
    for (String url : List.of(
        "file:///tmp/demo.tgz",
        "oci://registry.example.test/demo.tgz",
        "https:demo.tgz",
        "http:/demo.tgz")) {
      byte[] body = ("apiVersion: v1\n"
          + "entries: {demo: [{apiVersion: v2, name: demo, version: 1.0.0, "
          + "urls: ['%s']}]}")
          .formatted(url)
          .getBytes(StandardCharsets.UTF_8);

      assertThrows(IllegalArgumentException.class, () -> HelmIndex.validateIndex(body));
      assertThrows(
          IllegalArgumentException.class,
          () -> HelmIndex.rewriteProxyIndex(body, "https://repo.example.test/"));
    }
  }

  @Test
  void dropsUnsupportedAbsoluteChartUrlsWhenAnHttpAlternativeExists() {
    byte[] body = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              urls:
                - file:///tmp/demo.tgz
                - https://cdn.example.test/demo.tgz
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.RewriteResult rewritten = HelmIndex.rewriteProxyIndex(
        body, "https://repo.example.test/");

    assertEquals(
        "https://cdn.example.test/demo.tgz",
        rewritten.remoteUrlsByLocalPath().get("demo-1.0.0.tgz"));
    assertFalse(rewritten.remoteUrlsByLocalPath().containsValue("file:///tmp/demo.tgz"));
  }

  @Test
  void rejectsUrlsThatTheOutboundRequestPolicyCannotFetch() {
    for (String url : List.of(
        "https://user:pass@cdn.example.test/demo.tgz",
        "https://cdn.example.test:0/demo.tgz",
        "https://cdn.example.test:65536/demo.tgz")) {
      byte[] body = ("apiVersion: v1\n"
          + "entries: {demo: [{apiVersion: v2, name: demo, version: 1.0.0, "
          + "urls: ['%s']}]}"
          .formatted(url)).getBytes(StandardCharsets.UTF_8);

      assertThrows(IllegalArgumentException.class, () -> HelmIndex.validateIndex(body));
      assertThrows(
          IllegalArgumentException.class,
          () -> HelmIndex.rewriteProxyIndex(body, "https://repo.example.test/"));
    }

    byte[] withFallback = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              urls:
                - https://user:pass@cdn.example.test/rejected.tgz
                - https://cdn.example.test:0/rejected.tgz
                - https://cdn.example.test/accepted.tgz
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.RewriteResult rewritten = HelmIndex.rewriteProxyIndex(
        withFallback, "https://repo.example.test/");

    assertEquals(
        "https://cdn.example.test/accepted.tgz",
        rewritten.remoteUrlsByLocalPath().get("demo-1.0.0.tgz"));
    assertEquals(2, rewritten.remoteUrlsByLocalPath().size());
  }

  @Test
  void validatesIndexAndReleaseTimestampsBeforeRepublishingThem() {
    byte[] valid = """
        apiVersion: v1
        generated: '2026-08-30T10:20:30.123456789-06:00'
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              created: 2026-08-30T10:20:30.123Z
              urls: [demo.tgz]
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.validateIndex(valid);
    HelmIndex.rewriteProxyIndex(valid, "https://repo.example.test/");

    byte[] malformedRelease = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              created: yesterday
              appVersion: malformed
              urls: [demo.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    byte[] validFallback = """
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              created: '2026-08-30T10:20:30+06:00'
              appVersion: valid
              urls: [demo.tgz]
        """.getBytes(StandardCharsets.UTF_8);

    assertThrows(IllegalArgumentException.class, () -> HelmIndex.validateIndex(malformedRelease));
    assertThrows(
        IllegalArgumentException.class,
        () -> HelmIndex.rewriteProxyIndex(malformedRelease, "https://repo.example.test/"));
    String merged = text(HelmIndex.mergeGroupIndexes(
        List.of(malformedRelease, validFallback), Instant.EPOCH));
    assertTrue(merged.contains("appVersion: valid"));
    assertFalse(merged.contains("appVersion: malformed"));

    byte[] malformedGenerated = """
        apiVersion: v1
        generated: yesterday
        entries: {}
        """.getBytes(StandardCharsets.UTF_8);
    assertThrows(IllegalArgumentException.class, () -> HelmIndex.validateIndex(malformedGenerated));
  }

  @Test
  void validatesBooleanReleaseFlagsBeforeRepublishingThem() {
    byte[] valid = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              deprecated: true
              removed: false
              urls: [demo.tgz]
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.validateIndex(valid);
    String rewritten = text(HelmIndex.rewriteProxyIndex(
        valid, "https://repo.example.test/").body());
    assertTrue(rewritten.contains("deprecated: true"));
    assertTrue(rewritten.contains("removed: false"));

    byte[] validFallback = """
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              deprecated: false
              removed: false
              appVersion: valid
              urls: [demo.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    for (String field : List.of("deprecated", "removed")) {
      byte[] malformed = ("""
          apiVersion: v1
          entries:
            demo:
              - apiVersion: v2
                name: demo
                version: 1.0.0
                %s: not-a-boolean
                appVersion: malformed
                urls: [demo.tgz]
          """.formatted(field)).getBytes(StandardCharsets.UTF_8);

      assertThrows(IllegalArgumentException.class, () -> HelmIndex.validateIndex(malformed));
      assertThrows(
          IllegalArgumentException.class,
          () -> HelmIndex.rewriteProxyIndex(malformed, "https://repo.example.test/"));
      String merged = text(HelmIndex.mergeGroupIndexes(
          List.of(malformed, validFallback), Instant.EPOCH));
      assertTrue(merged.contains("appVersion: valid"));
      assertFalse(merged.contains("appVersion: malformed"));
    }
  }

  @Test
  void validatesTheCompleteHelmIndexAndChartVersionFieldSchema() {
    byte[] valid = """
        apiVersion: v1
        generated: '2026-08-30T12:00:00Z'
        serverInfo: {contextPath: /charts}
        publicKeys: [signing-key]
        annotations: {owner: platform}
        entries:
          demo:
            - apiVersion: v2
              name: demo
              home: https://example.test/demo
              sources: [https://example.test/source]
              version: 1.0.0
              description: Demo chart
              keywords: [demo]
              maintainers:
                - name: Example
                  email: team@example.test
                  url: https://example.test/team
              icon: https://example.test/icon.png
              condition: demo.enabled
              tags: demo
              appVersion: 2.0.0
              deprecated: false
              annotations: {category: test}
              kubeVersion: '>=1.30.0'
              dependencies:
                - name: child
                  version: '^1.0.0'
                  repository: https://example.test/charts
                  condition: child.enabled
                  tags: [child]
                  enabled: true
                  import-values: [data, {child: exports, parent: imports}]
                  alias: child-alias
              type: application
              urls: [demo.tgz]
              created: '2026-08-30T12:00:00Z'
              removed: false
              digest: abc123
              checksum: legacy-checksum
              engine: legacy-engine
              tillerVersion: legacy-tiller
              url: legacy-url
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.validateIndex(valid);
    String rewritten = text(HelmIndex.rewriteProxyIndex(
        valid, "https://repo.example.test/").body());
    assertTrue(rewritten.contains("digest: abc123"));
    assertTrue(rewritten.contains("name: child"));

    byte[] validFallback = """
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              appVersion: valid
              urls: [demo.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    for (String invalidField : List.of(
        "digest: [abc]",
        "home: [https://example.test]",
        "sources: source",
        "sources: [source, {bad: value}]",
        "annotations: []",
        "annotations: {key: [value]}",
        "maintainers: maintainer",
        "maintainers: [null]",
        "maintainers: [{name: [bad]}]",
        "maintainers: [{unknown: value}]",
        "dependencies: dependency",
        "dependencies: [null]",
        "dependencies: [{enabled: nope}]",
        "dependencies: [{tags: tag}]",
        "dependencies: [{import-values: value}]",
        "dependencies: [{unknown: value}]",
        "type: [application]",
        "created: [today]",
        "unknownField: value")) {
      byte[] malformed = ("""
          apiVersion: v1
          entries:
            demo:
              - apiVersion: v2
                name: demo
                version: 1.0.0
                %s
                appVersion: malformed
                urls: [demo.tgz]
          """.formatted(invalidField)).getBytes(StandardCharsets.UTF_8);

      assertThrows(IllegalArgumentException.class, () -> HelmIndex.validateIndex(malformed));
      assertThrows(
          IllegalArgumentException.class,
          () -> HelmIndex.rewriteProxyIndex(malformed, "https://repo.example.test/"));
      String merged = text(HelmIndex.mergeGroupIndexes(
          List.of(malformed, validFallback), Instant.EPOCH));
      assertTrue(merged.contains("appVersion: valid"));
      assertFalse(merged.contains("appVersion: malformed"));
    }

    for (String invalidRootField : List.of(
        "publicKeys: signing-key",
        "annotations: []",
        "annotations: {owner: [platform]}",
        "serverInfo: []",
        "serverInfo: {1: value}",
        "unknownRootField: value")) {
      byte[] malformed = ("apiVersion: v1\nentries: {}\n" + invalidRootField + "\n")
          .getBytes(StandardCharsets.UTF_8);

      assertThrows(IllegalArgumentException.class, () -> HelmIndex.validateIndex(malformed));
      assertThrows(
          IllegalArgumentException.class,
          () -> HelmIndex.rewriteProxyIndex(malformed, "https://repo.example.test/"));
    }
  }

  @Test
  void reportsMalformedYamlThroughTheProtocolErrorBoundary() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> HelmIndex.entries("entries: [".getBytes(StandardCharsets.UTF_8)));

    assertEquals("Invalid Helm index YAML", error.getMessage());
  }

  @Test
  void rejectsMissingOrUnsupportedClassicIndexApiVersions() {
    for (String invalid : List.of(
        "entries: {}\n",
        "apiVersion: v2\nentries: {}\n",
        "apiVersion: ''\nentries: {}\n")) {
      byte[] body = invalid.getBytes(StandardCharsets.UTF_8);

      assertThrows(IllegalArgumentException.class, () -> HelmIndex.validateIndex(body));
      assertThrows(
          IllegalArgumentException.class,
          () -> HelmIndex.rewriteProxyIndex(body, "https://repo.example.test/"));
    }
  }

  @Test
  void rejectsMissingOrUnsupportedReleaseChartApiVersions() {
    for (String releaseApiVersion : List.of("", "''", "v3")) {
      String field = releaseApiVersion.isEmpty()
          ? ""
          : "apiVersion: " + releaseApiVersion + ", ";
      byte[] body = ("apiVersion: v1\n"
          + "entries: {demo: [{%sname: demo, version: 1.0.0, urls: [demo.tgz]}]}"
              .formatted(field)).getBytes(StandardCharsets.UTF_8);

      assertThrows(IllegalArgumentException.class, () -> HelmIndex.validateIndex(body));
      assertThrows(
          IllegalArgumentException.class,
          () -> HelmIndex.rewriteProxyIndex(body, "https://repo.example.test/"));
    }
  }

  @Test
  void acceptsDefaultApplicationAndLibraryChartTypes() {
    for (String typeField : List.of("", "type: '', ", "type: application, ", "type: library, ")) {
      byte[] body = ("apiVersion: v1\n"
          + "entries: {demo: [{apiVersion: v2, %sname: demo, version: 1.0.0, "
          + "urls: [demo.tgz]}]}")
          .formatted(typeField)
          .getBytes(StandardCharsets.UTF_8);

      HelmIndex.validateIndex(body);
    }
  }

  @Test
  void rejectsUnsupportedReleaseChartTypesAndLetsALaterValidMemberWin() {
    for (String type : List.of("plugin", "Application", " ")) {
      byte[] body = ("apiVersion: v1\n"
          + "entries: {demo: [{apiVersion: v2, type: '%s', name: demo, version: 1.0.0, "
          + "urls: [demo.tgz]}]}")
          .formatted(type)
          .getBytes(StandardCharsets.UTF_8);

      assertThrows(IllegalArgumentException.class, () -> HelmIndex.validateIndex(body));
      assertThrows(
          IllegalArgumentException.class,
          () -> HelmIndex.rewriteProxyIndex(body, "https://repo.example.test/"));
    }

    byte[] invalid = """
        entries:
          demo:
            - apiVersion: v2
              type: plugin
              name: demo
              version: 1.0.0
              appVersion: invalid
              urls: [demo.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    byte[] valid = """
        entries:
          demo:
            - apiVersion: v2
              type: application
              name: demo
              version: 1.0.0
              appVersion: valid
              urls: [demo.tgz]
        """.getBytes(StandardCharsets.UTF_8);

    String merged = text(HelmIndex.mergeGroupIndexes(List.of(invalid, valid), Instant.EPOCH));

    assertTrue(merged.contains("appVersion: valid"));
    assertFalse(merged.contains("appVersion: invalid"));
  }

  @Test
  void rejectsSchemaInvalidProxyIndexesWithoutChangingTolerantReadHelpers() {
    for (String invalidBody : List.of(
        "[]",
        "not-a-helm-index",
        "{}",
        "entries: []",
        "entries: {demo: error}",
        "entries: {demo: [error]}",
        "entries: {demo: [{}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, urls: [demo-1.0.0.tgz]}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: 1.0.0}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: 1.0.0, urls: []}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: 1.0.0, urls: error}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: 1.0.0, urls: [false]}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: 1.0.0, urls: [{nested: value}]}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: 1.0.0, urls: [demo.zip, demo.tgz.prov]}]}",
        "entries: {alias: [{apiVersion: v2, name: demo, version: 1.0.0, urls: [demo.tgz]}]}",
        "entries: {'../private': [{apiVersion: v2, name: demo, version: 1.0.0, urls: [demo.tgz]}]}",
        "entries: {'demo?channel': [{apiVersion: v2, name: demo, version: 1.0.0, urls: [demo.tgz]}]}",
        "entries: {'demo:channel': [{apiVersion: v2, name: 'demo:channel', version: 1.0.0, urls: [demo.tgz]}]}",
        "entries: {demo: [{apiVersion: v2, name: 'demo#fragment', version: 1.0.0, urls: [demo.tgz]}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: '1.0.0%2fescape', urls: [demo.tgz]}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: '1.0.0%5cescape', urls: [demo.tgz]}]}",
        "entries: {demo: [{apiVersion: v2, name: '../private', version: 1.0.0, urls: [demo.tgz]}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: '1.0.0/escape', urls: [demo.tgz]}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: latest, urls: [demo.tgz]}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: 1.2.3.4, urls: [demo.tgz]}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: '1.2.3-01', urls: [demo.tgz]}]}",
        "entries: {demo: [{apiVersion: v2, name: demo, version: '18446744073709551616', urls: [demo.tgz]}]}")) {
      String invalid = invalidBody.startsWith("entries:")
          ? "apiVersion: v1\n" + invalidBody
          : invalidBody;
      assertThrows(
          IllegalArgumentException.class,
          () -> HelmIndex.rewriteProxyIndex(
              invalid.getBytes(StandardCharsets.UTF_8), "https://repo.example.test/"));
      assertThrows(
          IllegalArgumentException.class,
          () -> HelmIndex.validateIndex(invalid.getBytes(StandardCharsets.UTF_8)));
    }

    assertEquals(List.of(), HelmIndex.entries("entries: []".getBytes(StandardCharsets.UTF_8)));
    HelmIndex.validateIndex("apiVersion: v1\nentries: {}\n".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void acceptsTheCoercibleSemanticVersionsSupportedByHelm() {
    for (String version : List.of(
        "1", "1.2", "v1.2", "01.002.0003", "1.2.3-alpha.1+build.5")) {
      byte[] index = ("apiVersion: v1\n"
          + "entries: {demo: [{apiVersion: v2, name: demo, version: '%s', urls: [demo.tgz]}]}"
          .formatted(version)).getBytes(StandardCharsets.UTF_8);

      HelmIndex.validateIndex(index);
    }
  }

  @Test
  void cannotPublishTwoEntryKeysAsTheSameRepositoryLocalChartUrl() {
    byte[] mismatched = """
        apiVersion: v1
        entries:
          alias:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              digest: alias-digest
              urls: [alias.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    byte[] genuine = """
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.0.0
              digest: genuine-digest
              urls: [demo.tgz]
        """.getBytes(StandardCharsets.UTF_8);

    assertThrows(IllegalArgumentException.class, () -> HelmIndex.validateIndex(mismatched));
    assertEquals(
        List.of(new HelmIndex.Entry("demo", "1.0.0", List.of("demo-1.0.0.tgz"))),
        HelmIndex.entries(HelmIndex.mergeGroupIndexes(
            List.of(mismatched, genuine), Instant.parse("2026-08-30T00:00:00Z"))));
  }

  @Test
  void readsFallbackNamesAndSkipsNonMappingVersions() {
    byte[] body = """
        entries:
          demo:
            - version: 1.0.0
              urls:
                - demo.tgz
                - ""
                - null
            - not-a-map
        """.getBytes(StandardCharsets.UTF_8);

    assertEquals(
        List.of(new HelmIndex.Entry("demo", "1.0.0", List.of("demo.tgz"))),
        HelmIndex.entries(body));
  }

  private static HelmIndex.ChartRecord chart(String name, String version) {
    return new HelmIndex.ChartRecord(
        name,
        version,
        "v2",
        null,
        null,
        null,
        null,
        null,
        name == null || version == null ? List.of() : List.of(name + "-" + version + ".tgz"),
        null,
        null);
  }

  private static String text(byte[] body) {
    return new String(body, StandardCharsets.UTF_8);
  }
}
