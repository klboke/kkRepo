package com.github.klboke.kkrepo.protocol.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
          ignored: not-a-list
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
    assertEquals(3, result.remoteUrlsByLocalPath().size());
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
