package com.github.klboke.kkrepo.protocol.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

class HelmProtocolTest {
  private static final int CONCURRENT_PARSE_COUNT = 32;

  @Test
  void parsesChartYamlFromPackage() throws Exception {
    HelmChartMetadata metadata = new HelmChartPackageParser()
        .parse(new ByteArrayInputStream(chartPackage("demo", "1.2.3")));

    assertEquals("demo", metadata.name());
    assertEquals("1.2.3", metadata.version());
    assertEquals("v2", metadata.apiVersion());
  }

  @Test
  void parsesChartYamlFromHelmPackageWithGzipExtraHeader() throws Exception {
    HelmChartMetadata metadata = new HelmChartPackageParser()
        .parse(new ByteArrayInputStream(gzipWithExtraHeader(chartTar("demo", "1.2.3"))));

    assertEquals("demo", metadata.name());
    assertEquals("1.2.3", metadata.version());
    assertEquals("v2", metadata.apiVersion());
  }

  @Test
  void ignoresMetadataFilesThatOnlyEndWithChartYaml() throws Exception {
    byte[] bytes = chartPackageWithEntry("demo/._Chart.yaml", "not valid utf8 \u0000");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new HelmChartPackageParser().parse(new ByteArrayInputStream(bytes)));

    assertEquals("Chart.yaml not found in Helm chart package", error.getMessage());
  }

  @Test
  void rejectsChartYamlThatIsNotAMapping() throws Exception {
    byte[] bytes = chartPackageWithEntry("demo/Chart.yaml", "- not-a-mapping\n");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new HelmChartPackageParser().parse(new ByteArrayInputStream(bytes)));

    assertEquals("Chart.yaml is not a YAML mapping", error.getMessage());
  }

  @Test
  void concurrentlyParsesPackagesWithOneSharedParser() throws Exception {
    HelmChartPackageParser parser = new HelmChartPackageParser();
    List<byte[]> charts = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_PARSE_COUNT; i++) {
      charts.add(chartPackage("demo-" + i, "1.2." + i));
    }
    CountDownLatch ready = new CountDownLatch(CONCURRENT_PARSE_COUNT);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(CONCURRENT_PARSE_COUNT)) {
      List<Future<?>> parses = new ArrayList<>();
      for (int worker = 0; worker < CONCURRENT_PARSE_COUNT; worker++) {
        int workerIndex = worker;
        parses.add(executor.submit(() -> {
          ready.countDown();
          if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Concurrent Helm parses did not start together");
          }
          for (int iteration = 0; iteration < 25; iteration++) {
            int chartIndex = (workerIndex + iteration) % charts.size();
            HelmChartMetadata metadata = parser.parse(
                new ByteArrayInputStream(charts.get(chartIndex)));
            assertEquals("demo-" + chartIndex, metadata.name());
            assertEquals("1.2." + chartIndex, metadata.version());
          }
          return null;
        }));
      }
      try {
        assertTrue(ready.await(5, TimeUnit.SECONDS), "All Helm parser workers should be ready");
      } finally {
        start.countDown();
      }
      for (Future<?> parse : parses) {
        parse.get(15, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void rewritesProxyIndexUrlsToNexusLocalChartNames() {
    byte[] upstream = """
        apiVersion: v1
        entries:
          demo:
            - apiVersion: v2
              name: demo
              version: 1.2.3
              urls:
                - charts/demo-1.2.3.tgz
        generated: "2026-05-20T00:00:00Z"
        """.getBytes(StandardCharsets.UTF_8);

    HelmIndex.RewriteResult rewritten = HelmIndex.rewriteProxyIndex(
        upstream, "https://example.test/helm");

    List<HelmIndex.Entry> entries = HelmIndex.entries(rewritten.body());
    assertEquals(1, entries.size());
    assertEquals(List.of("demo-1.2.3.tgz"), entries.get(0).urls());
    assertEquals("https://example.test/helm/charts/demo-1.2.3.tgz",
        rewritten.remoteUrlsByLocalPath().get("demo-1.2.3.tgz"));
    assertTrue(new String(rewritten.body(), StandardCharsets.UTF_8).contains("demo-1.2.3.tgz"));
  }

  @Test
  void streamsHostedIndexAsValidYaml() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    HelmIndex.writeHosted(List.of(
            new HelmIndex.ChartRecord(
                "demo",
                "1.2.3",
                "v2",
                "Demo chart",
                "1.2",
                null,
                Instant.parse("2026-05-20T01:02:03Z"),
                "sha256-demo",
                List.of("demo-1.2.3.tgz"),
                List.of("https://example.test/source"),
                List.of(Map.of("name", "ops", "email", "ops@example.test")))),
        Instant.parse("2026-05-20T00:00:00Z"),
        out);

    byte[] body = out.toByteArray();
    List<HelmIndex.Entry> entries = HelmIndex.entries(body);
    String yaml = new String(body, StandardCharsets.UTF_8);

    assertEquals(1, entries.size());
    assertEquals("demo", entries.get(0).name());
    assertEquals("1.2.3", entries.get(0).version());
    assertEquals(List.of("demo-1.2.3.tgz"), entries.get(0).urls());
    assertTrue(yaml.contains("generated: \"2026-05-20T00:00:00Z\""));
    assertTrue(yaml.contains("digest: \"sha256-demo\""));
  }

  @Test
  void streamsEmptyHostedIndexAsValidYaml() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    HelmIndex.writeHosted(List.of(), Instant.parse("2026-05-20T00:00:00Z"), out);

    assertEquals(List.of(), HelmIndex.entries(out.toByteArray()));
    assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).contains("entries: {}"));
  }

  private static byte[] chartPackage(String name, String version) throws Exception {
    return gzip(chartTar(name, version));
  }

  private static byte[] chartTar(String name, String version) throws Exception {
    ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(tarBytes)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      putEntry(tar, name + "/Chart.yaml", """
          apiVersion: v2
          name: %s
          version: %s
          """.formatted(name, version));
      putEntry(tar, name + "/templates/configmap.yaml", """
          apiVersion: v1
          kind: ConfigMap
          metadata:
            name: demo
          """);
    }
    return tarBytes.toByteArray();
  }

  private static byte[] chartPackageWithEntry(String entryName, String body) throws Exception {
    ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(tarBytes)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      putEntry(tar, entryName, body);
    }
    return gzip(tarBytes.toByteArray());
  }

  private static byte[] gzip(byte[] body) throws IOException {
    ByteArrayOutputStream gzipBytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBytes)) {
      gzip.write(body);
    }
    return gzipBytes.toByteArray();
  }

  private static byte[] gzipWithExtraHeader(byte[] body) throws IOException {
    byte[] gzip = gzip(body);
    byte[] extra = "+aHR0cHM6Ly95b3V0dS5iZS96OVV6MWljandyTQo=Helm".getBytes(StandardCharsets.US_ASCII);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x1f);
    out.write(0x8b);
    out.write(8);
    out.write(4);
    out.write(new byte[]{0, 0, 0, 0}, 0, 4);
    out.write(0);
    out.write(255);
    out.write(extra.length & 0xff);
    out.write((extra.length >>> 8) & 0xff);
    out.write(extra, 0, extra.length);
    out.write(gzip, 10, gzip.length - 10);
    return out.toByteArray();
  }

  private static void putEntry(TarArchiveOutputStream tar, String name, String body) throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }
}
