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
        chart("ignored", null)),
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
            - name: demo
              version: 1.2.3
              urls:
                - charts/demo-original.tgz
                - https://cdn.example.test/demo-original.tgz.prov
            - urls:
                - fallback.tgz
            - name: empty
              version: 1.0.0
              urls: []
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
        result.remoteUrlsByLocalPath().get("fallback.tgz"));
    assertEquals(
        "https://repo.example.test/helm/fallback.tgz.prov",
        result.remoteUrlsByLocalPath().get("fallback.tgz.prov"));
    assertEquals(4, result.remoteUrlsByLocalPath().size());
  }

  @Test
  void mergesGroupIndexesInMemberOrderAndKeepsUniqueReleases() {
    byte[] first = """
        apiVersion: v1
        entries:
          demo:
            - name: demo
              version: 1.0.0
              appVersion: first-member
              urls:
                - https://first.example.test/charts/original.tgz
            - name: demo
              version: 0.9.0
              urls:
                - demo-0.9.0.tgz
        """.getBytes(StandardCharsets.UTF_8);
    byte[] second = """
        apiVersion: v1
        entries:
          demo:
            - name: demo
              version: 1.0.0
              appVersion: second-member
              urls:
                - demo-1.0.0.tgz
            - name: demo
              version: 2.0.0
              urls:
                - charts/demo-current.tgz
          other:
            - name: other
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
            - name: demo
              version: 1.0.0
              appVersion: unusable
              urls: [demo.zip, demo.tgz.prov]
        """.getBytes(StandardCharsets.UTF_8);
    byte[] usable = """
        entries:
          demo:
            - name: demo
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
  void derivesProvenanceBeforeChartUrlQueryAndFragment() {
    byte[] upstream = """
        entries:
          demo:
            - name: demo
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
        entries:
          demo:
            - name: demo
              version: 1.0.0
              digest: BBBB
              urls: [charts/original-name.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    HelmIndex.Release release = HelmIndex.releaseForPath(selected, "demo-1.0.0.tgz")
        .orElseThrow();

    assertEquals(
        new HelmIndex.Release("demo", "1.0.0", "BBBB", List.of("demo-1.0.0.tgz")),
        release);
    assertEquals(
        release,
        HelmIndex.releaseForPath(selected, "/demo-1.0.0.tgz.prov").orElseThrow());
    assertTrue(HelmIndex.containsRelease(selected, release, "demo-1.0.0.tgz"));
    assertTrue(HelmIndex.containsRelease(selected, release, "demo-1.0.0.tgz.prov"));
  }

  @Test
  void rejectsSameCoordinatesWhenMemberDigestDoesNotMatchTheGroupWinner() {
    byte[] selected = """
        entries:
          demo:
            - name: demo
              version: 1.0.0
              digest: digest-b
              urls: [demo-1.0.0.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    byte[] conflicting = """
        entries:
          demo:
            - name: demo
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
            - name: demo
              version: 1.0.0
              digest: sha256:BBBB
              urls: [demo-1.0.0.tgz]
        """.getBytes(StandardCharsets.UTF_8);
    byte[] bare = """
        entries:
          demo:
            - name: demo
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
  void toleratesMalformedAndNonMappingIndexes() {
    byte[] malformedUrl = """
        entries:
          demo:
            - urls:
                - "http://["
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.RewriteResult rewritten = HelmIndex.rewriteProxyIndex(malformedUrl, null);
    assertEquals("http://[", rewritten.remoteUrlsByLocalPath().get("["));
    assertEquals(List.of(), HelmIndex.entries("[]".getBytes(StandardCharsets.UTF_8)));
    assertEquals(List.of(), HelmIndex.entries("entries: []".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void reportsMalformedYamlThroughTheProtocolErrorBoundary() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> HelmIndex.entries("entries: [".getBytes(StandardCharsets.UTF_8)));

    assertEquals("Invalid Helm index YAML", error.getMessage());
  }

  @Test
  void rejectsSchemaInvalidProxyIndexesWithoutChangingTolerantReadHelpers() {
    for (String invalid : List.of(
        "[]",
        "not-a-helm-index",
        "{}",
        "entries: []",
        "entries: {demo: error}",
        "entries: {demo: [error]}",
        "entries: {demo: [{urls: error}]}",
        "entries: {demo: [{urls: [{nested: value}]}]}")) {
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
