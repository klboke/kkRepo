package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.protocol.conda.CondaMediaTypes;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.junit.jupiter.api.Test;

class CondaMetadataCodecTest {
  private static final String MD5 = "0123456789abcdef0123456789abcdef";
  private static final String SHA256 = "a".repeat(64);
  private final ObjectMapper mapper = new ObjectMapper();
  private final CondaMetadataCodec codec = new CondaMetadataCodec(mapper);

  @Test
  void parsesLegacyAndV2UpstreamCollectionsAndRemovesRemoteBaseUrl() throws Exception {
    byte[] repodata = ("""
        {
          "info":{"subdir":"linux-64","base_url":"../package-pool/"},
          "packages":{"demo-1.0-0.tar.bz2":%s},
          "packages.conda":{"demo-2.0-py_1.conda":%s}
        }
        """).formatted(
            recordJson("demo", "1.0", "0", 0, ".tar.bz2"),
            recordJson("demo", "2.0", "py_1", 1, ".conda"))
        .getBytes(StandardCharsets.UTF_8);

    CondaMetadataCodec.ProxyInventory inventory = codec.parseRepodata(
        repodata, 7, "main", "linux-64", Instant.parse("2026-08-07T00:00:00Z"));

    assertEquals(2, inventory.records().size());
    assertEquals(64, inventory.metadataSha256().length());
    assertEquals("../package-pool/", inventory.packageBaseUrl());
    CondaRegistryDao.PackageRecord modern = inventory.records().get(1);
    assertEquals("conda", modern.archiveFormat());
    assertEquals("linux-64", modern.metadata().get("subdir"));
    assertFalse(modern.metadata().containsKey("base_url"));
    assertFalse(modern.metadata().containsKey("download_url"));
    assertEquals(SHA256, modern.sha256());
  }

  @Test
  void proxyInventorySpoolIsReplayableAndDeletedOnClose() {
    byte[] repodata = ("""
        {
          "packages":{"demo-1.0-0.tar.bz2":%s},
          "packages.conda":{"demo-2.0-py_1.conda":%s},
          "removed":["old-0.1-0.tar.bz2"]
        }
        """).formatted(
            recordJson("demo", "1.0", "0", 0, ".tar.bz2"),
            recordJson("demo", "2.0", "py_1", 1, ".conda"))
        .getBytes(StandardCharsets.UTF_8);
    Path spool;
    try (CondaMetadataCodec.ProxyInventoryFile inventory = codec.parseRepodataFile(
        new ByteArrayInputStream(repodata), SHA256, 7, "main", "linux-64", Instant.EPOCH)) {
      spool = inventory.path();
      assertTrue(Files.exists(spool));
      assertEquals(2, inventory.recordCount());
      assertEquals(List.of("old-0.1-0.tar.bz2"), inventory.removed());
      ArrayList<CondaRegistryDao.PackageRecord> first = new ArrayList<>();
      ArrayList<CondaRegistryDao.PackageRecord> second = new ArrayList<>();
      inventory.records().visit(first::add);
      inventory.records().visit(second::add);
      assertEquals(first, second);
    }
    assertFalse(Files.exists(spool));
  }

  @Test
  void renderSourceSnapshotIsReplayableAndDeletedOnClose() throws Exception {
    CondaRegistryDao.PackageRecord legacy = record(
        "demo-1.0-0.tar.bz2", "1.0", "0", 0, "tar.bz2", Instant.EPOCH);
    CondaRegistryDao.PackageRecord modern = record(
        "demo-2.0-py_1.conda", "2.0", "py_1", 1, "conda", Instant.EPOCH);
    CondaMetadataCodec.RecordSource source = (archiveFormat, visitor) -> {
      if ("tar.bz2".equals(archiveFormat)) visitor.accept(legacy);
      if ("conda".equals(archiveFormat)) visitor.accept(modern);
    };
    Path spool;

    try (CondaMetadataCodec.RecordSourceFile snapshot =
             codec.snapshotRecordSource("linux-64", source)) {
      spool = snapshot.path();
      assertTrue(Files.exists(spool));
      ArrayList<CondaRegistryDao.PackageRecord> replayed = new ArrayList<>();
      snapshot.records().visit("tar.bz2", replayed::add);
      snapshot.records().visit("conda", replayed::add);
      assertEquals(List.of(legacy.filename(), modern.filename()),
          replayed.stream().map(CondaRegistryDao.PackageRecord::filename).toList());
    }

    assertFalse(Files.exists(spool));
  }

  @Test
  void validatesCep15PackageBaseUrls() {
    CondaMetadataCodec.ProxyInventory absolute = codec.parseRepodata(
        """
        {"info":{"base_url":"https://packages.example.invalid/conda/"}}
        """.getBytes(StandardCharsets.UTF_8), 1, "main", "linux-64", Instant.EPOCH);
    assertEquals(
        "https://packages.example.invalid/conda/", absolute.packageBaseUrl());

    assertThrows(MavenExceptions.BadUpstreamException.class, () -> codec.parseRepodata(
        """
        {"info":{"base_url":"https://packages.example.invalid/conda/?token=secret"}}
        """.getBytes(StandardCharsets.UTF_8), 1, "main", "linux-64", Instant.EPOCH));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> codec.parseRepodata(
        """
        {"info":{"base_url":"file:///tmp/packages/"}}
        """.getBytes(StandardCharsets.UTF_8), 1, "main", "linux-64", Instant.EPOCH));
  }

  @Test
  void acceptsHistoricalStringAndListFeatureMetadata() {
    String repodata = """
        {"packages":{"demo-1.0-0.tar.bz2":{
          "name":"demo","version":"1.0","build":"0","build_number":0,
          "size":12,"subdir":"linux-64","sha256":"%s",
          "track_features":"mkl@ blas","features":["mkl","blas"]
        }}}
        """.formatted(SHA256);

    CondaRegistryDao.PackageRecord record = codec.parseRepodata(
        repodata.getBytes(StandardCharsets.UTF_8), 1, "main", "linux-64", Instant.EPOCH)
        .records().getFirst();

    assertEquals("mkl@ blas", record.metadata().get("track_features"));
    assertEquals(List.of("mkl", "blas"), record.metadata().get("features"));
  }

  @Test
  void acceptsHistoricalEmptyTrackFeatures() {
    String repodata = """
        {"packages":{"nomkl-3.0-0.tar.bz2":{
          "name":"nomkl","version":"3.0","build":"0","build_number":0,
          "size":12,"subdir":"linux-64","sha256":"%s","track_features":""
        }}}
        """.formatted(SHA256);

    CondaRegistryDao.PackageRecord record = codec.parseRepodata(
        repodata.getBytes(StandardCharsets.UTF_8), 1, "main", "linux-64", Instant.EPOCH)
        .records().getFirst();

    assertEquals("", record.metadata().get("track_features"));
  }

  @Test
  void acceptsTheDocumentedLegacyDefaultsPackageNameWithoutAllowingVirtualNames() {
    String legacy = """
        {"packages":{"__anaconda_core_depends-2024.06-py310_mkl_0.tar.bz2":{
          "name":"__anaconda_core_depends","version":"2024.06",
          "build":"py310_mkl_0","build_number":0,"size":12,
          "subdir":"linux-64","sha256":"%s"
        }}}
        """.formatted(SHA256);

    CondaRegistryDao.PackageRecord record = codec.parseRepodata(
        legacy.getBytes(StandardCharsets.UTF_8), 1, "main", "linux-64", Instant.EPOCH)
        .records().getFirst();

    assertEquals("__anaconda_core_depends", record.name());

    String virtual = legacy.replace("__anaconda_core_depends", "__linux");
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> codec.parseRepodata(
        virtual.getBytes(StandardCharsets.UTF_8), 1, "main", "linux-64", Instant.EPOCH));
  }

  @Test
  void treatsEmptyUpstreamRepodataAsAnEmptyDictionary() {
    CondaMetadataCodec.ProxyInventory inventory = codec.parseRepodata(
        new byte[0], 7, "main", "noarch", Instant.parse("2026-08-07T00:00:00Z"));

    assertTrue(inventory.records().isEmpty());
    assertEquals(64, inventory.metadataSha256().length());
  }

  @Test
  void rejectsCoordinatesMissingIntegrityAndWrongSubdirs() {
    String mismatch = """
        {"packages":{"wrong-1.0-0.tar.bz2":%s}}
        """.formatted(recordJson("demo", "1.0", "0", 0, ".tar.bz2"));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> codec.parseRepodata(
        mismatch.getBytes(StandardCharsets.UTF_8), 1, "main", "linux-64", Instant.EPOCH));

    String noChecksum = """
        {"packages":{"demo-1.0-0.tar.bz2":{
          "name":"demo","version":"1.0","build":"0","build_number":0,
          "size":12,"subdir":"linux-64"
        }}}
        """;
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> codec.parseRepodata(
        noChecksum.getBytes(StandardCharsets.UTF_8), 1, "main", "linux-64", Instant.EPOCH));

    String wrongSubdir = """
        {"packages":{"demo-1.0-0.tar.bz2":%s}}
        """.formatted(recordJson("demo", "1.0", "0", 0, ".tar.bz2")
            .replace("linux-64", "osx-64"));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> codec.parseRepodata(
        wrongSubdir.getBytes(StandardCharsets.UTF_8), 1, "main", "linux-64", Instant.EPOCH));
  }

  @Test
  void rendersEquivalentJsonBzip2AndZstdMetadataWithTombstones() throws Exception {
    CondaRegistryDao.PackageRecord legacy = record(
        "demo-1.0-0.tar.bz2", "1.0", "0", 0, "tar.bz2", Instant.EPOCH);
    CondaRegistryDao.PackageRecord modern = record(
        "demo-2.0-py_1.conda", "2.0", "py_1", 1, "conda", Instant.EPOCH);
    List<CondaRegistryDao.Tombstone> removed = List.of(new CondaRegistryDao.Tombstone(
        1, "main", "linux-64", "old-0.1-0.tar.bz2", "delete", 3, Instant.EPOCH));

    CondaMetadataCodec.Rendered json = codec.renderRepodata(
        "linux-64", List.of(modern, legacy), removed, CondaPath.Encoding.JSON);
    CondaMetadataCodec.Rendered bz2 = codec.renderRepodata(
        "linux-64", List.of(modern, legacy), removed, CondaPath.Encoding.BZIP2);
    CondaMetadataCodec.Rendered zstd = codec.renderRepodata(
        "linux-64", List.of(modern, legacy), removed, CondaPath.Encoding.ZSTD);
    CondaMetadataCodec.Rendered repeated = codec.renderRepodata(
        "linux-64", List.of(legacy, modern), removed, CondaPath.Encoding.JSON);

    JsonNode expected = mapper.readTree(json.body());
    assertEquals(expected, mapper.readTree(decompressBzip2(bz2.body())));
    assertEquals(expected, mapper.readTree(decompressZstd(zstd.body())));
    assertTrue(expected.path("packages").has("demo-1.0-0.tar.bz2"));
    assertTrue(expected.path("packages.conda").has("demo-2.0-py_1.conda"));
    assertEquals(1, expected.path("info").path("repodata_version").intValue());
    assertEquals("old-0.1-0.tar.bz2", expected.path("removed").get(0).textValue());
    assertEquals(CondaMediaTypes.JSON, json.contentType());
    assertEquals(CondaMediaTypes.BZIP2, bz2.contentType());
    assertEquals(CondaMediaTypes.ZSTD, zstd.contentType());
    assertEquals(64, json.etag().length());
    assertArrayEquals(json.body(), repeated.body());
    assertEquals(json.etag(), repeated.etag());
  }

  @Test
  void channeldataUsesCondaVersionOrderInsteadOfTimestampOrder() throws Exception {
    CondaRegistryDao.PackageRecord olderVersionWithNewTimestamp = record(
        "demo-1.9-9.tar.bz2", "1.9", "9", 9, "tar.bz2",
        Instant.parse("2026-08-07T00:00:00Z"));
    CondaRegistryDao.PackageRecord newerVersionWithOldTimestamp = record(
        "demo-1.10-0.conda", "1.10", "0", 0, "conda",
        Instant.parse("2020-01-01T00:00:00Z"));

    JsonNode channeldata = mapper.readTree(codec.renderChanneldata(
        List.of(olderVersionWithNewTimestamp, newerVersionWithOldTimestamp)).body());

    assertEquals("1.10", channeldata.path("packages").path("demo").path("version").textValue());
    assertEquals("demo-1.10-0.conda",
        channeldata.path("packages").path("demo").path("reference_package").textValue());
  }

  @Test
  void sanitizesRemoteLocationsRecursively() throws Exception {
    CondaMetadataCodec.Rendered sanitized = codec.sanitizeJson("""
        {"base_url":"https://upstream.invalid", "nested":{
          "download_url":"https://upstream.invalid/package", "value":1}}
        """.getBytes(StandardCharsets.UTF_8));
    JsonNode root = mapper.readTree(sanitized.body());
    assertFalse(root.has("base_url"));
    assertFalse(root.path("nested").has("download_url"));
    assertEquals(1, root.path("nested").path("value").intValue());
  }

  @Test
  void oversizedGroupMetadataSpoolsEachRawMemberOnce() throws Exception {
    byte[] repodata = ("""
        {"packages":{"demo-1.0-0.tar.bz2":%s,
          "safe-3.0-0.tar.bz2":{"name":"safe","version":"3.0","build":"0",
            "build_number":0,"depends":[],"subdir":"linux-64","size":7}},
         "packages.conda":{"demo-2.0-py_1.conda":%s},
         "removed":["old-0.1-0.tar.bz2"]}
        """).formatted(
            recordJson("demo", "1.0", "0", 0, ".tar.bz2"),
            recordJson("demo", "2.0", "py_1", 1, ".conda"))
        .getBytes(StandardCharsets.UTF_8);
    AtomicInteger opens = new AtomicInteger();
    CondaMetadataCodec.MergeSource raw = CondaMetadataCodec.MergeSource.raw(
        Long.MAX_VALUE, () -> {
          opens.incrementAndGet();
          return new ByteArrayInputStream(repodata);
        });

    Path renderedPath;
    try (CondaMetadataCodec.RenderedFile rendered = codec.renderMergedRepodataFile(
        "linux-64", List.of(raw), List.of(), CondaPath.Encoding.JSON)) {
      renderedPath = rendered.path();
      String renderedJson = Files.readString(rendered.path());
      JsonNode root = mapper.readTree(rendered.path().toFile());
      assertTrue(root.path("packages").has("demo-1.0-0.tar.bz2"));
      assertEquals("safe", root.path("packages").path("safe-3.0-0.tar.bz2")
          .path("name").textValue());
      assertTrue(root.path("packages.conda").has("demo-2.0-py_1.conda"));
      assertFalse(root.path("packages").path("demo-1.0-0.tar.bz2").has("base_url"));
      assertFalse(root.path("packages.conda").path("demo-2.0-py_1.conda")
          .has("download_url"));
      assertEquals("old-0.1-0.tar.bz2", root.path("removed").get(0).textValue());
      assertFalse(renderedJson.contains("\n"));
      assertTrue(renderedJson.contains(
          "\"safe-3.0-0.tar.bz2\":{\"name\":\"safe\",\"version\":\"3.0\""));
      assertEquals(1, opens.get());
    }
    assertFalse(Files.exists(renderedPath));
  }

  @Test
  void buffersCompressedBytesBeforeWritingDigestOrFileSink() throws Exception {
    CountingOutputStream sink = new CountingOutputStream();

    try (OutputStream encoded = codec.encodedOutput(sink, CondaPath.Encoding.BZIP2)) {
      encoded.write(new byte[1024 * 1024]);
    }

    assertEquals(0, sink.singleByteWrites);
    assertTrue(sink.bulkWrites > 0);
    assertTrue(sink.bytes > 0);
  }

  private static String recordJson(
      String name, String version, String build, long buildNumber, String suffix) {
    return """
        {
          "name":"%s","version":"%s","build":"%s","build_number":%d,
          "size":12,"subdir":"linux-64","md5":"%s","sha256":"%s",
          "depends":["python >=3.12"],"base_url":"https://upstream.invalid/%s",
          "download_url":"https://upstream.invalid/package"
        }
        """.formatted(name, version, build, buildNumber, MD5, SHA256, suffix);
  }

  private static final class CountingOutputStream extends OutputStream {
    private int singleByteWrites;
    private int bulkWrites;
    private long bytes;

    @Override
    public void write(int value) {
      singleByteWrites++;
      bytes++;
    }

    @Override
    public void write(byte[] value, int offset, int length) throws IOException {
      bulkWrites++;
      bytes += length;
    }
  }

  private static CondaRegistryDao.PackageRecord record(
      String filename,
      String version,
      String build,
      long buildNumber,
      String archiveFormat,
      Instant timestamp) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("timestamp", timestamp.toEpochMilli());
    metadata.put("summary", "Demo package");
    return new CondaRegistryDao.PackageRecord(
        null, 1, "main", "linux-64", filename, "demo", version, build, buildNumber,
        archiveFormat, Map.copyOf(metadata), SHA256, MD5, SHA256, 12, 10L, 20L,
        CondaRegistryDao.SOURCE_HOSTED, 1, timestamp, timestamp);
  }

  private static byte[] decompressBzip2(byte[] body) throws Exception {
    try (BZip2CompressorInputStream input = new BZip2CompressorInputStream(
        new ByteArrayInputStream(body))) {
      return input.readAllBytes();
    }
  }

  private static byte[] decompressZstd(byte[] body) throws Exception {
    try (ZstdInputStream input = new ZstdInputStream(new ByteArrayInputStream(body))) {
      return input.readAllBytes();
    }
  }
}
