package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.protocol.conda.CondaMediaTypes;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
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
  void spoolCodecDoesNotRequirePojoReflectionInNativeImages() throws Exception {
    ObjectMapper reflectionRestricted = new ObjectMapper();
    reflectionRestricted.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
    CondaMetadataCodec nativeSafeCodec = new CondaMetadataCodec(reflectionRestricted);
    CondaRegistryDao.PackageRecord expected = record(
        "demo-1.0-0.tar.bz2", "1.0", "0", 0, "tar.bz2", Instant.EPOCH);

    try (CondaMetadataCodec.RecordSourceFile snapshot = nativeSafeCodec.snapshotRecordSource(
        "linux-64", (format, visitor) -> {
          if ("tar.bz2".equals(format)) visitor.accept(expected);
        })) {
      ArrayList<CondaRegistryDao.PackageRecord> replayed = new ArrayList<>();
      snapshot.records().visit("tar.bz2", replayed::add);
      assertEquals(List.of(expected.filename()),
          replayed.stream().map(CondaRegistryDao.PackageRecord::filename).toList());
      assertEquals("Demo package", replayed.getFirst().metadata().get("summary"));
      assertEquals(0L, ((Number) replayed.getFirst().metadata().get("timestamp")).longValue());
    }

    byte[] upstream = ("{\"packages\":{\"demo-1.0-0.tar.bz2\":"
        + recordJson("demo", "1.0", "0", 0, ".tar.bz2") + "}}")
        .getBytes(StandardCharsets.UTF_8);
    try (CondaMetadataCodec.ProxyInventoryFile inventory = nativeSafeCodec.parseRepodataFile(
        new ByteArrayInputStream(upstream), SHA256, 1, "main", "linux-64", Instant.EPOCH)) {
      ArrayList<CondaRegistryDao.PackageRecord> replayed = new ArrayList<>();
      inventory.records().visit(replayed::add);
      assertEquals(List.of("demo-1.0-0.tar.bz2"),
          replayed.stream().map(CondaRegistryDao.PackageRecord::filename).toList());
    }
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

  @Test
  void decodesEverySupportedRepodataEncodingAndClosesFailedInputs() throws Exception {
    byte[] json = "{\"packages\":{}}".getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream plain = new ByteArrayInputStream(json);
    assertSame(plain, codec.decodeRepodata(plain, CondaPath.Encoding.JSON));
    try (InputStream decoded = codec.decodeRepodata(
        new ByteArrayInputStream(compressBzip2(json)), CondaPath.Encoding.BZIP2)) {
      assertArrayEquals(json, decoded.readAllBytes());
    }
    try (InputStream decoded = codec.decodeRepodata(
        new ByteArrayInputStream(Zstd.compress(json)), CondaPath.Encoding.ZSTD)) {
      assertArrayEquals(json, decoded.readAllBytes());
    }

    CloseTrackingInputStream unsupported = new CloseTrackingInputStream(new byte[0], false);
    assertThrows(IOException.class,
        () -> codec.decodeRepodata(unsupported, CondaPath.Encoding.NONE));
    assertTrue(unsupported.closed);
    CloseTrackingInputStream invalidBzip = new CloseTrackingInputStream(new byte[] {1}, true);
    assertThrows(IOException.class,
        () -> codec.decodeRepodata(invalidBzip, CondaPath.Encoding.BZIP2));
    assertTrue(invalidBzip.closed);
  }

  @Test
  void rejectsMalformedRootCollectionsRemovedEntriesAndDuplicateNames() {
    for (String invalid : List.of(
        "[]",
        "{} {}",
        "{\"packages\":{},\"packages\":{}}",
        "{\"packages\":[]}",
        "{\"packages.conda\":[]}",
        "{\"removed\":{}}",
        "{\"removed\":[1]}",
        "{\"removed\":[\"not-a-package\"]}",
        "{\"packages\":{\"demo-1.0-0.tar.bz2\":{},\"demo-1.0-0.tar.bz2\":{}}}",
        "{\"packages\":{\"bad.txt\":{}}}",
        "{\"packages\":{\"demo-1.0-0.conda\":{}}}",
        "{\"packages.conda\":{\"demo-1.0-0.tar.bz2\":{}}}",
        "{\"packages\":{\"demo-1.0-0.tar.bz2\":null}}")) {
      assertBadRepodata(invalid);
    }

    CondaMetadataCodec.ProxyInventory empty = codec.parseRepodata(
        "{\"packages\":null,\"packages.conda\":null,\"removed\":null,\"ignored\":[1]}"
            .getBytes(StandardCharsets.UTF_8),
        1, "main", "noarch", Instant.EPOCH);
    assertTrue(empty.records().isEmpty());
  }

  @Test
  void validatesAllPackageCoordinateIntegrityAndMetadataFieldShapes() {
    for (String record : List.of(
        "{}",
        validRecord().replace("\"name\":\"demo\"", "\"name\":1"),
        validRecord().replace("\"name\":\"demo\"", "\"name\":\"bad--name\""),
        validRecord().replace("\"version\":\"1.0\"", "\"version\":\"\""),
        validRecord().replace("\"version\":\"1.0\"", "\"version\":\"1+bad\""),
        validRecord().replace("\"build\":\"0\"", "\"build\":\"bad-build\""),
        validRecord().replace("\"build_number\":0", "\"build_number\":-1"),
        validRecord().replace("\"build_number\":0", "\"build_number\":\"0\""),
        validRecord().replace("\"size\":12", "\"size\":0"),
        validRecord().replace("\"size\":12", "\"size\":1.5"),
        validRecord().replace("\"md5\":\"" + MD5 + "\",", "")
            .replace(",\"sha256\":\"" + SHA256 + "\"", ""),
        validRecord().replace(SHA256, "x".repeat(64)),
        validRecord().replace(MD5, "x".repeat(32)),
        validRecord().replace("\"subdir\":\"linux-64\"", "\"subdir\":1"),
        validRecord().replace("\"subdir\":\"linux-64\"", "\"subdir\":\"osx-64\""),
        validRecord().replace("\"depends\":[\"python\"]", "\"depends\":1"),
        validRecord().replace("\"depends\":[\"python\"]", "\"depends\":[\"\"]"),
        validRecord().replace("\"features\":\"mkl\"", "\"features\":1"),
        validRecord().replace("\"features\":\"mkl\"", "\"features\":[\"\"]"))) {
      assertBadRecord(record);
    }

    String md5Only = validRecord().replace(",\"sha256\":\"" + SHA256 + "\"", "")
        .replace(MD5, MD5.toUpperCase());
    CondaRegistryDao.PackageRecord accepted = codec.parseRepodata(
        ("{\"packages\":{\"demo-1.0-0.tar.bz2\":" + md5Only + "}}")
            .getBytes(StandardCharsets.UTF_8),
        1, "main", "linux-64", null).records().getFirst();
    assertEquals(MD5, accepted.md5());
    assertTrue(accepted.sha256() == null);
    assertTrue(accepted.indexedAt().isAfter(Instant.EPOCH));
  }

  @Test
  void rendersNullInputsNoneEncodingAndSanitizedNestedMetadata() throws Exception {
    assertTrue(mapper.readTree(codec.emptyNotices().body()).path("notices").isEmpty());
    CondaMetadataCodec.Rendered empty = codec.renderRepodata(
        "noarch", null, null, CondaPath.Encoding.NONE);
    JsonNode emptyRoot = mapper.readTree(empty.body());
    assertTrue(emptyRoot.path("packages").isEmpty());
    assertEquals(CondaMediaTypes.JSON, empty.contentType());

    LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
    nested.put("base_url", "https://secret.invalid");
    nested.put("children", List.of(Map.of(
        "download_url", "https://secret.invalid/package", "kept", true)));
    CondaRegistryDao.PackageRecord withoutChecksums = new CondaRegistryDao.PackageRecord(
        null, 1, "main", "noarch", "demo-1.0-0.conda", "demo", "1.0", "0", 0,
        "conda", nested, SHA256, null, null, 12, null, null,
        CondaRegistryDao.SOURCE_HOSTED, 1, Instant.EPOCH, Instant.EPOCH);
    JsonNode rendered = mapper.readTree(codec.renderRepodata(
        "noarch", List.of(withoutChecksums), List.of(), CondaPath.Encoding.JSON).body())
        .path("packages.conda").path(withoutChecksums.filename());
    assertFalse(rendered.has("md5"));
    assertFalse(rendered.has("sha256"));
    assertFalse(rendered.has("base_url"));
    assertFalse(rendered.path("children").get(0).has("download_url"));
    assertTrue(rendered.path("children").get(0).path("kept").booleanValue());

    assertThrows(MavenExceptions.MavenNotFoundException.class, () -> codec.renderRepodata(
        "noarch", List.of(), List.of(), CondaPath.Encoding.MSGPACK_ZSTD));
    assertThrows(MavenExceptions.MavenNotFoundException.class, () -> {
      try (OutputStream ignored = codec.encodedOutput(
          new ByteArrayOutputStream(), CondaPath.Encoding.MSGPACK_ZSTD)) {
      }
    });
  }

  @Test
  void mergesMaterializedRawAndRecordSourcesByRepositoryPriority() throws Exception {
    CondaRegistryDao.PackageRecord preferred = record(
        "demo-1.0-0.tar.bz2", "1.0", "0", 0, "tar.bz2", Instant.EPOCH);
    CondaRegistryDao.PackageRecord modern = record(
        "modern-2.0-1.conda", "2.0", "1", 1, "conda", Instant.EPOCH);
    CondaMetadataCodec.RecordSource records = (format, visitor) -> {
      if ("tar.bz2".equals(format)) visitor.accept(preferred);
      if ("conda".equals(format)) visitor.accept(modern);
    };
    byte[] fallback = ("""
        {"packages":{"demo-1.0-0.tar.bz2":{"summary":"fallback"},
          "raw-1.0-0.tar.bz2":{"summary":"raw","base_url":"https://hidden.invalid"}},
         "packages.conda":{},"removed":["old-1.0-0.tar.bz2","demo-1.0-0.tar.bz2"]}
        """).getBytes(StandardCharsets.UTF_8);
    CondaRegistryDao.Tombstone tombstone = new CondaRegistryDao.Tombstone(
        1, "main", "linux-64", "gone-1.0-0.conda", "delete", 1, Instant.EPOCH);

    try (CondaMetadataCodec.RenderedFile file = codec.renderMergedRepodataFile(
        "linux-64",
        List.of(CondaMetadataCodec.MergeSource.records(records),
            CondaMetadataCodec.MergeSource.raw(fallback.length,
                () -> new ByteArrayInputStream(fallback))),
        List.of(tombstone), CondaPath.Encoding.JSON)) {
      JsonNode root = mapper.readTree(file.path().toFile());
      assertEquals("Demo package",
          root.path("packages").path(preferred.filename()).path("summary").textValue());
      assertFalse(root.path("packages").path("raw-1.0-0.tar.bz2").has("base_url"));
      assertTrue(root.path("packages.conda").has(modern.filename()));
      assertEquals(List.of("gone-1.0-0.conda", "old-1.0-0.tar.bz2"),
          mapper.convertValue(root.path("removed"), List.class));
    }

    assertThrows(IllegalStateException.class, () -> codec.renderMergedRepodataFile(
        "linux-64", List.of(CondaMetadataCodec.MergeSource.raw(2,
            () -> new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8)))),
        List.of(), CondaPath.Encoding.JSON));
    assertThrows(IllegalStateException.class, () -> codec.renderMergedRepodataFile(
        "linux-64", List.of(CondaMetadataCodec.MergeSource.raw(20,
            () -> new ByteArrayInputStream(
                "{\"packages\":[]}".getBytes(StandardCharsets.UTF_8)))),
        List.of(), CondaPath.Encoding.JSON));
    assertThrows(IllegalStateException.class, () -> codec.renderMergedRepodataFile(
        "linux-64", List.of(CondaMetadataCodec.MergeSource.raw(20,
            () -> new ByteArrayInputStream(
                "{\"removed\":{}}".getBytes(StandardCharsets.UTF_8)))),
        List.of(), CondaPath.Encoding.JSON));

    for (CondaPath.Encoding encoding : List.of(
        CondaPath.Encoding.BZIP2, CondaPath.Encoding.ZSTD)) {
      try (CondaMetadataCodec.RenderedFile file = codec.renderMergedRepodataFile(
          "linux-64", List.of(CondaMetadataCodec.MergeSource.records(records)),
          List.of(tombstone), encoding)) {
        byte[] encoded = Files.readAllBytes(file.path());
        byte[] decoded = encoding == CondaPath.Encoding.BZIP2
            ? decompressBzip2(encoded)
            : decompressZstd(encoded);
        JsonNode root = mapper.readTree(decoded);
        assertTrue(root.path("packages").has(preferred.filename()));
        assertTrue(root.path("packages.conda").has(modern.filename()));
      }
    }
  }

  @Test
  void spooledMergeKeepsTheFirstRecordAndSuppressesVisibleTombstones() throws Exception {
    CondaRegistryDao.PackageRecord legacy = record(
        "demo-1.0-0.tar.bz2", "1.0", "0", 0, "tar.bz2", Instant.EPOCH);
    CondaMetadataCodec.RecordSource records = (format, visitor) -> {
      if ("tar.bz2".equals(format)) visitor.accept(legacy);
    };
    byte[] raw = ("{\"removed\":[\"" + legacy.filename() + "\"]}")
        .getBytes(StandardCharsets.UTF_8);

    try (CondaMetadataCodec.RenderedFile file = codec.renderMergedRepodataFile(
        "linux-64",
        List.of(
            CondaMetadataCodec.MergeSource.records(records),
            CondaMetadataCodec.MergeSource.records(records),
            CondaMetadataCodec.MergeSource.raw(
                Long.MAX_VALUE, () -> new ByteArrayInputStream(raw))),
        List.of(), CondaPath.Encoding.JSON)) {
      JsonNode root = mapper.readTree(file.path().toFile());
      assertTrue(root.path("packages").has(legacy.filename()));
      assertTrue(root.path("removed").isEmpty());
    }
  }

  @Test
  void channeldataHandlesNullRecordsTieBreakersAndRejectsOutOfOrderSources()
      throws Exception {
    assertTrue(mapper.readTree(codec.renderChanneldata(null).body()).path("packages").isEmpty());
    CondaRegistryDao.PackageRecord base = record(
        "demo-1.0-a.conda", "1.0", "a", 1, "conda", Instant.EPOCH);
    CondaRegistryDao.PackageRecord newerBuild = record(
        "demo-1.0-b.conda", "1.0", "b", 1, "conda", Instant.EPOCH.plusSeconds(1));
    JsonNode latest = mapper.readTree(codec.renderChanneldata(List.of(base, newerBuild)).body())
        .path("packages").path("demo");
    assertEquals("demo-1.0-b.conda", latest.path("reference_package").textValue());

    try (CondaMetadataCodec.RenderedFile file = codec.renderChanneldataFile(visitor -> {
      visitor.accept(null);
      visitor.accept(namedRecord("zeta", "zeta-1.0-0.conda"));
      visitor.accept(namedRecord("alpha", "alpha-1.0-0.conda"));
    })) {
      throw new AssertionError("out-of-order channeldata unexpectedly rendered: " + file.path());
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("Failed rendering Conda channeldata"));
    }
  }

  @Test
  void rejectsEmptyAndMalformedSanitizedMetadataAndSnapshotsSourceFailures() {
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> codec.sanitizeJson(new byte[0]));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> codec.sanitizeJson("{\"base_url\":".getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalStateException.class, () -> codec.snapshotRecordSource(
        "linux-64", (format, visitor) -> {
          throw new IOException("source failed");
        }));

    assertEquals(
        codec.fingerprint(Map.of("kept", List.of(Map.of("value", 1)))),
        codec.fingerprint(Map.of(
            "base_url", "hidden", "kept", List.of(Map.of("download_url", "hidden", "value", 1)))));
  }

  @Test
  void coversStreamingFailureCleanupAndOversizedRecordValidation() {
    assertBadRepodata("{\"packages\":");
    assertThrows(IllegalStateException.class, () -> codec.renderRepodataFile(
        "noarch", (format, visitor) -> {
          throw new IOException("record stream failed");
        }, List.of(), CondaPath.Encoding.JSON));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> codec.renderMergedRepodataFile(
            "noarch", List.of(), List.of(), CondaPath.Encoding.MSGPACK_ZSTD));

    String oversized = validRecord().replace(
        "\"features\":\"mkl\"",
        "\"features\":\"mkl\",\"summary\":\"" + "x".repeat(270_000) + "\"");
    assertBadRecord(oversized);

    Object broken = new Object() {
      public String getValue() {
        throw new IllegalStateException("cannot encode");
      }
    };
    assertThrows(IllegalArgumentException.class,
        () -> codec.fingerprint(Map.of("broken", broken)));
  }

  @Test
  void validatesEveryPackageBaseUrlShapeAndMissingRequiredIntegers() {
    for (String invalid : List.of(
        "{\"info\":[]}",
        "{\"info\":{\"base_url\":1}}",
        "{\"info\":{\"base_url\":\" \"}}",
        "{\"info\":{\"base_url\":\"//bad_host/packages/\"}}",
        "{\"info\":{\"base_url\":\"http://[invalid\"}}")) {
      assertBadRepodata(invalid);
    }
    assertBadRecord(validRecord().replace("\"build_number\":0,", ""));
    assertBadRecord(validRecord().replace("\"version\":\"1.0\"", "\"version\":\"bad!\""));
  }

  @Test
  void rejectsMalformedDiskBackedGroupSourcesAndExercisesRawRangeCompaction() throws Exception {
    for (String invalid : List.of(
        "[]",
        "{} {}",
        "{\"packages\":{},\"packages\":{}}",
        "{\"packages\":[]}",
        "{\"packages\":{\"bad.txt\":{}}}",
        "{\"packages\":{\"demo-1.0-0.tar.bz2\":null}}",
        "{\"packages\":{\"demo-1.0-0.tar.bz2\":{},\"demo-1.0-0.tar.bz2\":{}}}",
        "{\"removed\":[1]}")) {
      assertBadSpoolMerge(invalid);
    }
    for (String invalid : List.of(
        "{\"packages\":{\"bad.txt\":{}}}",
        "{\"removed\":[1]}")) {
      assertBadMaterializedMerge(invalid);
    }
    assertBadSpoolMerge("{\"packages\":{\"demo-1.0-0.tar.bz2\":{");
    assertBadSpoolMerge("""
        {"packages":{"demo-1.0-0.tar.bz2":{
          "base_url":"https://hidden.invalid","summary":"%s"}}}
        """.formatted("x".repeat(270_000)));

    String safeThenSanitized = """
        {"packages":{
          "safe-1.0-0.tar.bz2":{"summary":"a\\\"b\\\\c"},
          "remote-1.0-0.tar.bz2":{"base_url":"https://hidden.invalid","value":1}},
         "removed":["safe-1.0-0.tar.bz2","gone-1.0-0.tar.bz2"]}
        """;
    try (CondaMetadataCodec.RenderedFile file = codec.renderMergedRepodataFile(
        "linux-64",
        List.of(CondaMetadataCodec.MergeSource.raw(
            Long.MAX_VALUE,
            () -> new ByteArrayInputStream(safeThenSanitized.getBytes(StandardCharsets.UTF_8)))),
        List.of(new CondaRegistryDao.Tombstone(
            1, "main", "linux-64", "deleted-1.0-0.conda", "delete", 1, Instant.EPOCH)),
        CondaPath.Encoding.JSON)) {
      JsonNode root = mapper.readTree(file.path().toFile());
      assertEquals("a\"b\\c",
          root.path("packages").path("safe-1.0-0.tar.bz2").path("summary").textValue());
      assertFalse(root.path("packages").path("remote-1.0-0.tar.bz2").has("base_url"));
      assertEquals(List.of("deleted-1.0-0.conda", "gone-1.0-0.tar.bz2"),
          mapper.convertValue(root.path("removed"), List.class));
    }
  }

  @Test
  void detectsCorruptInventoryAndRenderSpools() throws Exception {
    for (String corrupt : List.of(
        "{}",
        "[1]",
        "[{}] {}",
        "[{",
        "[{\"unknown\":1}]",
        "[{\"metadata\":[]}]",
        "[{\"filename\":1}]",
        "[{\"buildNumber\":\"x\"}]")) {
      try (CondaMetadataCodec.ProxyInventoryFile inventory = codec.parseRepodataFile(
          new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
          SHA256, 1, "main", "noarch", Instant.EPOCH)) {
        Files.writeString(inventory.path(), corrupt);
        assertThrows(IllegalStateException.class,
            () -> inventory.records().visit(ignored -> { }), corrupt);
      }
    }

    for (String corrupt : List.of("{}", "[1]", "[{}] {}")) {
      try (CondaMetadataCodec.RecordSourceFile source = codec.snapshotRecordSource(
          "noarch", (format, visitor) -> { })) {
        Files.writeString(source.path(), corrupt);
        assertThrows(IOException.class,
            () -> source.records().visit("conda", ignored -> { }), corrupt);
      }
    }
  }

  @Test
  void channeldataFlushesPackagesAndUsesTimestampThenFilenameTieBreakers() throws Exception {
    JsonNode differentNames = mapper.readTree(codec.renderChanneldata(List.of(
        namedRecord("zeta", "zeta-1.0-0.conda"),
        namedRecord("alpha", "alpha-1.0-0.conda"))).body());
    assertTrue(differentNames.path("packages").has("alpha"));
    assertTrue(differentNames.path("packages").has("zeta"));

    CondaRegistryDao.PackageRecord older = record(
        "demo-1.0-0.tar.bz2", "1.0", "0", 0, "tar.bz2", Instant.EPOCH);
    CondaRegistryDao.PackageRecord newer = record(
        "demo-1.0-0.conda", "1.0", "0", 0, "conda", Instant.EPOCH.plusSeconds(1));
    JsonNode timestampWinner = mapper.readTree(codec.renderChanneldata(List.of(older, newer)).body());
    assertEquals(newer.filename(), timestampWinner.path("packages").path("demo")
        .path("reference_package").textValue());

    CondaRegistryDao.PackageRecord filenameFirst = namedRecord("same", "same-1.0-0.conda");
    CondaRegistryDao.PackageRecord filenameLast = new CondaRegistryDao.PackageRecord(
        null, 1, "main", "linux-64", "same-1.0-0.tar.bz2", "same", "1.0", "0", 0,
        "tar.bz2", Map.of(), SHA256, MD5, SHA256, 12, null, null,
        CondaRegistryDao.SOURCE_HOSTED, 1, Instant.EPOCH, Instant.EPOCH);
    JsonNode filenameWinner = mapper.readTree(
        codec.renderChanneldata(List.of(filenameFirst, filenameLast)).body());
    assertEquals(filenameLast.filename(), filenameWinner.path("packages").path("same")
        .path("reference_package").textValue());
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

  private void assertBadRepodata(String value) {
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> codec.parseRepodata(
        value.getBytes(StandardCharsets.UTF_8), 1, "main", "linux-64", Instant.EPOCH), value);
  }

  private void assertBadRecord(String value) {
    assertBadRepodata("{\"packages\":{\"demo-1.0-0.tar.bz2\":" + value + "}}");
  }

  private void assertBadSpoolMerge(String value) {
    assertThrows(IllegalStateException.class, () -> codec.renderMergedRepodataFile(
        "linux-64",
        List.of(CondaMetadataCodec.MergeSource.raw(
            Long.MAX_VALUE,
            () -> new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)))),
        List.of(), CondaPath.Encoding.JSON), value);
  }

  private void assertBadMaterializedMerge(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    assertThrows(IllegalStateException.class, () -> codec.renderMergedRepodataFile(
        "linux-64",
        List.of(CondaMetadataCodec.MergeSource.raw(
            bytes.length, () -> new ByteArrayInputStream(bytes))),
        List.of(), CondaPath.Encoding.JSON), value);
  }

  private static String validRecord() {
    return """
        {"name":"demo","version":"1.0","build":"0","build_number":0,"size":12,
         "subdir":"linux-64","md5":"%s","sha256":"%s","depends":["python"],
         "constrains":null,"track_features":null,"features":"mkl"}
        """.formatted(MD5, SHA256);
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

  private static CondaRegistryDao.PackageRecord namedRecord(String name, String filename) {
    return new CondaRegistryDao.PackageRecord(
        null, 1, "main", "linux-64", filename, name, "1.0", "0", 0,
        "conda", Map.of(), SHA256, MD5, SHA256, 12, 10L, 20L,
        CondaRegistryDao.SOURCE_HOSTED, 1, Instant.EPOCH, Instant.EPOCH);
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

  private static byte[] compressBzip2(byte[] body) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (BZip2CompressorOutputStream compressed = new BZip2CompressorOutputStream(output)) {
      compressed.write(body);
    }
    return output.toByteArray();
  }

  private static final class CloseTrackingInputStream extends ByteArrayInputStream {
    private final boolean failClose;
    private boolean closed;

    private CloseTrackingInputStream(byte[] body, boolean failClose) {
      super(body);
      this.failClose = failClose;
    }

    @Override
    public void close() throws IOException {
      closed = true;
      if (failClose) throw new IOException("close failed");
      super.close();
    }
  }
}
