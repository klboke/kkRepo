package com.github.klboke.kkrepo.server.yum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.RepositoryIndexRebuildDaoAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class YumServiceTest {
  @Test
  void buildsRepomdForPrimaryFilelistsAndOtherMetadata() {
    YumService.MetadataFile primary = YumService.gzipMetadata(YumService.primaryXml(List.of(
        new YumService.RpmAsset("packages/demo-1.0-1.noarch.rpm",
            metadata("demo", "1.0", "1", "noarch"), "abc123", 1234,
            Instant.parse("2026-06-09T00:00:00Z")))));
    YumService.MetadataFile filelists = YumService.gzipMetadata(YumService.filelistsXml(List.of()));
    YumService.MetadataFile other = YumService.gzipMetadata(YumService.otherXml(List.of()));

    String repomd = YumService.repomdXml(List.of(
        new YumService.RepomdEntry("primary", "repodata/" + primary.fileName(), primary),
        new YumService.RepomdEntry("filelists", "repodata/" + filelists.fileName(), filelists),
        new YumService.RepomdEntry("other", "repodata/" + other.fileName(), other)));

    assertTrue(repomd.contains("<data type=\"primary\">"));
    assertTrue(repomd.contains("<data type=\"filelists\">"));
    assertTrue(repomd.contains("<data type=\"other\">"));
    assertTrue(repomd.contains("<open-checksum type=\"sha256\">"));
  }

  @Test
  void parsesRpmCoordinateFromFileName() {
    YumService.RpmCoordinate coordinate = YumService.rpmCoordinate("packages/demo-lib-1.2.3-4.x86_64.rpm");

    assertEquals("demo-lib", coordinate.name());
    assertEquals("1.2.3", coordinate.version());
    assertEquals("4", coordinate.release());
    assertEquals("x86_64", coordinate.arch());
  }

  @Test
  void primaryMetadataUsesParsedRpmCoordinate() {
    String primary = YumService.primaryXml(List.of(new YumService.RpmAsset(
        "packages/demo-lib-1.2.3-4.x86_64.rpm",
        metadata("demo-lib", "1.2.3", "4", "x86_64"), "abc123", 1234,
        Instant.parse("2026-06-09T00:00:00Z"))));

    assertTrue(primary.contains("<name>demo-lib</name>"));
    assertTrue(primary.contains("<arch>x86_64</arch>"));
    assertTrue(primary.contains("<version epoch=\"0\" ver=\"1.2.3\" rel=\"4\"/>"));
  }

  @Test
  void rpmPutReturnsOkForSuccessfulHostedWriteLikeNexus() {
    RawHostedService hosted = mock(RawHostedService.class);
    AssetDao assetDao = mock(AssetDao.class);
    RecordingIndexRebuildDao indexRebuildDao = new RecordingIndexRebuildDao();
    YumService service = new YumService(hosted, null, null, assetDao, null, indexRebuildDao);
    RepositoryRuntime runtime = hosted(1L);
    String path = "Packages/demo-1.2.3-4.x86_64.rpm";
    when(hosted.putWithAttributes(eq(runtime), eq(path), any(InputStream.class),
        eq("application/x-rpm"), anyMap(), eq("tester"), eq("127.0.0.1")))
        .thenReturn(MavenResponse.created());

    MavenResponse response = service.put(runtime, path,
        new ByteArrayInputStream(rpmFixture()), null, "tester", "127.0.0.1");

    assertEquals(200, response.status());
    verify(hosted).putWithAttributes(eq(runtime), eq(path), any(InputStream.class),
        eq("application/x-rpm"), anyMap(), eq("tester"), eq("127.0.0.1"));
    assertEquals(List.of("1:" + RepositoryIndexRebuildDao.YUM_METADATA + ":"),
        indexRebuildDao.enqueues);
  }

  @Test
  void proxyReadDelegatesToRawProxyForLazyBackfill() throws Exception {
    RawProxyService proxy = mock(RawProxyService.class);
    YumService service = new YumService(null, proxy, null, null, null, null);
    RepositoryRuntime runtime = proxy(21L);
    byte[] bytes = "rpm".getBytes(StandardCharsets.UTF_8);
    String path = "Packages/demo-1.0-1.noarch.rpm";
    when(proxy.get(eq(runtime), eq(path), eq(false)))
        .thenReturn(MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
            "application/x-rpm", "sha1", Instant.parse("2026-06-09T00:00:00Z")));

    MavenResponse response = service.get(runtime, path, false);

    assertEquals(200, response.status());
    assertEquals("application/x-rpm", response.contentType());
    assertEquals(bytes.length, response.contentLength());
    verify(proxy).get(eq(runtime), eq(path), eq(false));
  }

  @Test
  void rpmMetadataSurvivesBlobAttributeJsonRoundTrip() throws Exception {
    // Mirrors how blob attributes are persisted: numbers come back as Integer/Long and
    // booleans as Boolean after a Jackson Map<String,Object> round-trip, so fromAttributes
    // must normalize those types back to the original RpmMetadata.
    RpmMetadata original = new RpmMetadata(
        "demo", "1.2.3", "4", "7", "x86_64",
        "Demo package", "Demo package description", "https://example.invalid/demo",
        "MIT", "Example", "Development/Tools", "builder", "demo-1.2.3-4.src.rpm", "packager",
        1710000000L, 4096L, 1024L, 1376L, 9216L,
        List.of(new RpmDependency("demo", "EQ", "7", "1.2.3", "4", false)),
        List.of(new RpmDependency("/bin/sh", "", "", "", "", true)),
        List.of(new RpmDependency("old-demo", "LT", "1", "1.0", "1", false)),
        List.of(new RpmDependency("older-demo", "LT", "1", "0.9", "1", false)),
        List.of(new RpmFile("/usr/bin/demo", "", "digest-bin"),
            new RpmFile("/etc/demo", "dir", "")));

    ObjectMapper mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(original.toAttributes());
    Map<String, Object> roundTripped = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    RpmMetadata restored = RpmMetadata.fromAttributes(roundTripped);

    assertEquals(original, restored);
    assertEquals(1710000000L, restored.buildTime());
    assertEquals(1376L, restored.headerStart());
    assertEquals(9216L, restored.headerEnd());
    assertTrue(restored.requires().getFirst().pre());
  }

  @Test
  void parsesRpmHeaderMetadataAndWritesDependencyAndFilelistsXml() {
    RpmMetadata metadata = RpmMetadataParser.parse(rpmFixture());

    assertEquals("demo", metadata.name());
    assertEquals("1.2.3", metadata.version());
    assertEquals("4", metadata.release());
    assertEquals("7", metadata.epoch());
    assertEquals("x86_64", metadata.arch());
    assertEquals(2048, metadata.archiveSize());
    assertEquals(1, metadata.provides().size());
    assertEquals("demo", metadata.provides().getFirst().name());
    assertEquals(2, metadata.requires().size());
    assertEquals("/bin/sh", metadata.requires().getFirst().name());
    assertTrue(metadata.requires().get(1).pre());
    assertTrue(metadata.headerStart() > 0);
    assertTrue(metadata.headerEnd() > metadata.headerStart());

    YumService.RpmAsset rpm = new YumService.RpmAsset("packages/demo-1.2.3-4.x86_64.rpm",
        metadata, "abc123", 1234, Instant.parse("2026-06-09T00:00:00Z"));
    String primary = YumService.primaryXml(List.of(rpm));
    String filelists = YumService.filelistsXml(List.of(rpm));

    assertTrue(primary.contains("<rpm:header-range start=\"" + metadata.headerStart()));
    assertTrue(primary.contains("<rpm:provides>"));
    assertTrue(primary.contains("<rpm:requires>"));
    assertTrue(primary.contains("<rpm:entry name=\"/bin/sh\"/>"));
    assertTrue(primary.contains(
        "<rpm:entry name=\"pre-required\" flags=\"EQ\" epoch=\"1\" ver=\"2.0\" rel=\"3\" pre=\"1\"/>"));
    assertTrue(primary.contains("<file>/usr/bin/demo</file>"));
    assertTrue(filelists.contains("<file>/usr/bin/demo</file>"));
    assertTrue(filelists.contains("<file type=\"dir\">/etc/demo</file>"));
    assertTrue(RpmMetadataParser.isPrimaryFile("/usr/lib/sendmail"));
  }

  @Test
  void rpmMetadataAttributesSurviveJsonRoundTrip() throws Exception {
    RpmMetadata metadata = RpmMetadataParser.parse(rpmFixture());
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> roundTripped = mapper.readValue(
        mapper.writeValueAsString(metadata.toAttributes()),
        new TypeReference<Map<String, Object>>() {
        });

    RpmMetadata restored = RpmMetadata.fromAttributes(roundTripped);

    assertEquals(metadata.name(), restored.name());
    assertEquals(metadata.version(), restored.version());
    assertEquals(metadata.epoch(), restored.epoch());
    assertEquals(metadata.headerStart(), restored.headerStart());
    assertEquals(metadata.archiveSize(), restored.archiveSize());
    assertEquals(metadata.requires().size(), restored.requires().size());
    assertTrue(restored.requires().get(1).pre());
    assertEquals(metadata.files().size(), restored.files().size());
  }

  @Test
  void groupRepomdAggregatesHostedMemberRpms() throws Exception {
    YumService service = new YumService(null, null, null, new FakeAssetDao(List.of(
        asset(11L, 101L, "a/demo-1.0-1.noarch.rpm"),
        asset(12L, 102L, "b/tool-2.0-3.x86_64.rpm"))), null, null);

    MavenResponse response = service.get(group(hosted(11L), hosted(12L)), "repodata/repomd.xml", false);
    String repomd = body(response);

    assertEquals(200, response.status());
    assertTrue(repomd.contains("<data type=\"primary\">"));
    assertTrue(repomd.contains("<data type=\"filelists\">"));
    assertTrue(repomd.contains("<data type=\"other\">"));
  }

  @Test
  void groupMetadataLazyParsesRpmHeaderWithRangeReadAndBackfillsAttributes() throws Exception {
    FakeAssetDao assetDao = new FakeAssetDao(
        List.of(asset(11L, 101L, "Packages/demo-1.2.3-4.x86_64.rpm")),
        Map.of(101L, blob(101L, Map.of())));
    RangeBlobStorage storage = new RangeBlobStorage(rpmFixture());
    YumService service = new YumService(null, null, null, assetDao, new SingleBlobStorageRegistry(storage), null);

    MavenResponse response = service.get(group(hosted(11L)), "repodata/repomd.xml", false);

    assertEquals(200, response.status());
    assertEquals(0L, storage.lastStart);
    assertEquals(RpmMetadataParser.defaultRangeReadBytes(), storage.lastLength);
    Map<String, Object> attrs = assetDao.updatedAttributes(101L);
    assertEquals("demo", attrs.get("yum.rpm.name"));
    assertEquals("7", attrs.get("yum.rpm.epoch"));
    assertTrue(((Number) attrs.get("yum.rpm.headerStart")).longValue() > 0);
    assertTrue(attrs.get("yum.rpm.requires") instanceof List<?> requires && requires.size() == 2);
    assertTrue(attrs.get("yum.rpm.files") instanceof List<?> files && files.size() == 2);
  }

  @Test
  void groupPrimaryMetadataFallsBackToRpmCoordinateWhenBlobHeaderCannotBeRead() throws Exception {
    YumService service = new YumService(null, null, null, new FakeAssetDao(List.of(
        asset(11L, 101L, "Packages/fallback-demo-2.0-3.noarch.rpm"))), null, null);

    String repomd = body(service.get(group(hosted(11L)), "repodata/repomd.xml", false));
    String primaryHref = metadataHref(repomd, "primary");
    String primary = gunzip(service.get(group(hosted(11L)), primaryHref, false));

    assertTrue(primary.contains("<name>fallback-demo</name>"));
    assertTrue(primary.contains("<arch>noarch</arch>"));
    assertTrue(primary.contains("<version epoch=\"0\" ver=\"2.0\" rel=\"3\"/>"));
    assertTrue(primary.contains("<rpm:header-range start=\"0\" end=\"0\"/>"));
  }

  private static String body(MavenResponse response) throws Exception {
    try (InputStream body = response.body()) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String gunzip(MavenResponse response) throws Exception {
    try (InputStream body = response.body();
         GZIPInputStream gzip = new GZIPInputStream(body)) {
      return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String metadataHref(String repomd, String type) {
    String dataStart = "<data type=\"" + type + "\">";
    int data = repomd.indexOf(dataStart);
    int location = repomd.indexOf("<location href=\"", data);
    int hrefStart = location + "<location href=\"".length();
    int hrefEnd = repomd.indexOf('"', hrefStart);
    return repomd.substring(hrefStart, hrefEnd);
  }

  private static RepositoryRuntime group(RepositoryRuntime... members) {
    return runtime(1L, RepositoryType.GROUP, List.of(members));
  }

  private static RepositoryRuntime hosted(long id) {
    return runtime(id, RepositoryType.HOSTED, null, List.of());
  }

  private static RepositoryRuntime proxy(long id) {
    return runtime(id, RepositoryType.PROXY, "https://example.invalid/yum/", List.of());
  }

  private static RepositoryRuntime runtime(long id, RepositoryType type, List<RepositoryRuntime> members) {
    return runtime(id, type, null, members);
  }

  private static RepositoryRuntime runtime(
      long id, RepositoryType type, String remoteUrl, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id,
        "yum-" + id,
        RepositoryFormat.YUM,
        type,
        "yum-" + type.name().toLowerCase(),
        true,
        1L,
        null,
        null,
        null,
        true,
        remoteUrl,
        1440,
        1440,
        true,
        null,
        members);
  }

  private static AssetRecord asset(long repositoryId, long blobId, String path) {
    return new AssetRecord(
        repositoryId * 100 + blobId,
        repositoryId,
        null,
        blobId,
        RepositoryFormat.YUM,
        path,
        new byte[] {1},
        path,
        "yum",
        "application/x-rpm",
        1234L,
        null,
        Instant.parse("2026-06-09T00:00:00Z"),
        Map.of());
  }

  private static AssetBlobRecord blob(long blobId, Map<String, Object> attributes) {
    return new AssetBlobRecord(
        blobId,
        1L,
        "blob://bucket/rpm-object",
        new byte[] {1},
        "rpm-object",
        new byte[] {1},
        "sha1",
        "sha256-" + blobId,
        "md5",
        1234L,
        "application/x-rpm",
        "test",
        "127.0.0.1",
        Instant.parse("2026-06-09T00:00:00Z"),
        Instant.parse("2026-06-09T00:00:00Z"),
        attributes);
  }

  private static RpmMetadata metadata(String name, String version, String release, String arch) {
    return RpmMetadata.fallback(new YumService.RpmCoordinate(name, version, release, arch),
        Instant.parse("2026-06-09T00:00:00Z"));
  }

  private static byte[] rpmFixture() {
    try {
      ByteArrayOutputStream rpm = new ByteArrayOutputStream();
      putInt(rpm, 0xedabeedb);
      rpm.write(new byte[92]);
      rpm.write(header(List.of()));
      while (rpm.size() % 8 != 0) {
        rpm.write(0);
      }
      rpm.write(header(List.of(
          Entry.string(1000, "demo"),
          Entry.string(1001, "1.2.3"),
          Entry.string(1002, "4"),
          Entry.int32(1003, 7),
          Entry.i18n(1004, "Demo package"),
          Entry.i18n(1005, "Demo package description"),
          Entry.int32(1006, 1710000000),
          Entry.string(1007, "builder"),
          Entry.int32(1009, 4096),
          Entry.string(1011, "Example"),
          Entry.string(1014, "MIT"),
          Entry.string(1015, "packager"),
          Entry.i18n(1016, "Development/Tools"),
          Entry.string(1020, "https://example.invalid/demo"),
          Entry.string(1022, "x86_64"),
          Entry.int16Array(1030, List.of(0100755, 0040755)),
          Entry.stringArray(1035, List.of("digest-bin", "digest-etc")),
          Entry.int32Array(1037, List.of(0, 0)),
          Entry.string(1044, "demo-1.2.3-4.src.rpm"),
          Entry.int32(1046, 1024),
          Entry.stringArray(1047, List.of("demo")),
          Entry.int32Array(1112, List.of(8)),
          Entry.stringArray(1113, List.of("7:1.2.3-4")),
          Entry.stringArray(1049, List.of("rpmlib(CompressedFileNames)", "/usr/bin/demo", "/bin/sh",
              "pre-required")),
          Entry.int32Array(1048, List.of(8, 0, 0, 520)),
          Entry.stringArray(1050, List.of("0:0-0", "", "", "1:2.0-3")),
          Entry.stringArray(1054, List.of("old-demo")),
          Entry.int32Array(1053, List.of(10)),
          Entry.stringArray(1055, List.of("1:1.0-1")),
          Entry.stringArray(1090, List.of("older-demo")),
          Entry.int32Array(1114, List.of(10)),
          Entry.stringArray(1115, List.of("1:0.9-1")),
          Entry.int32Array(1116, List.of(0, 1)),
          Entry.stringArray(1117, List.of("demo", "demo")),
          Entry.stringArray(1118, List.of("/usr/bin/", "/etc/")),
          Entry.int64(271, 2048),
          Entry.int64(5009, 4096),
          Entry.int32(5011, 8))));
      return rpm.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] header(List<Entry> entries) throws IOException {
    List<Entry> sorted = entries.stream().sorted(Comparator.comparingInt(Entry::tag)).toList();
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    List<Index> indexes = new ArrayList<>();
    for (Entry entry : sorted) {
      indexes.add(new Index(entry.tag(), entry.type(), data.size(), entry.count()));
      data.write(entry.value());
    }
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    putInt(header, 0x8eade801);
    putInt(header, 0);
    putInt(header, sorted.size());
    putInt(header, data.size());
    for (Index index : indexes) {
      putInt(header, index.tag());
      putInt(header, index.type());
      putInt(header, index.offset());
      putInt(header, index.count());
    }
    header.write(data.toByteArray());
    return header.toByteArray();
  }

  private static void putInt(ByteArrayOutputStream out, int value) throws IOException {
    out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array());
  }

  private record Index(int tag, int type, int offset, int count) {
  }

  private record Entry(int tag, int type, byte[] value, int count) {
    static Entry string(int tag, String value) {
      return new Entry(tag, 6, stringBytes(value), 1);
    }

    static Entry i18n(int tag, String value) {
      return new Entry(tag, 9, stringBytes(value), 1);
    }

    static Entry stringArray(int tag, List<String> values) {
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String value : values) {
          out.write(stringBytes(value));
        }
        return new Entry(tag, 8, out.toByteArray(), values.size());
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    static Entry int16Array(int tag, List<Integer> values) {
      ByteBuffer buffer = ByteBuffer.allocate(values.size() * 2).order(ByteOrder.BIG_ENDIAN);
      values.forEach(value -> buffer.putShort((short) value.intValue()));
      return new Entry(tag, 3, buffer.array(), values.size());
    }

    static Entry int32(int tag, int value) {
      return int32Array(tag, List.of(value));
    }

    static Entry int32Array(int tag, List<Integer> values) {
      ByteBuffer buffer = ByteBuffer.allocate(values.size() * 4).order(ByteOrder.BIG_ENDIAN);
      values.forEach(buffer::putInt);
      return new Entry(tag, 4, buffer.array(), values.size());
    }

    static Entry int64(int tag, long value) {
      ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value);
      return new Entry(tag, 5, buffer.array(), 1);
    }

    private static byte[] stringBytes(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      byte[] result = new byte[bytes.length + 1];
      System.arraycopy(bytes, 0, result, 0, bytes.length);
      return result;
    }
  }

  private static final class FakeAssetDao extends AssetDaoAdapter {
    private final List<AssetRecord> assets;
    private final Map<Long, AssetBlobRecord> blobs;
    private final Map<Long, Map<String, Object>> updatedAttributes = new LinkedHashMap<>();

    FakeAssetDao(List<AssetRecord> assets) {
      this(assets, Map.of());
    }

    FakeAssetDao(List<AssetRecord> assets, Map<Long, AssetBlobRecord> blobs) {
      super(null, null);
      this.assets = assets;
      this.blobs = blobs;
    }

    @Override
    public List<AssetRecord> listAssetsByPrefix(long repositoryId, String pathPrefix) {
      return assets.stream()
          .filter(asset -> asset.repositoryId() == repositoryId)
          .toList();
    }

    @Override
    public Optional<AssetBlobRecord> findBlobById(long assetBlobId) {
      return Optional.of(blob(assetBlobId));
    }

    @Override
    public Map<Long, AssetBlobRecord> findBlobsByIds(java.util.Collection<Long> assetBlobIds) {
      Map<Long, AssetBlobRecord> result = new LinkedHashMap<>();
      for (Long assetBlobId : assetBlobIds) {
        if (assetBlobId != null) {
          result.put(assetBlobId, blob(assetBlobId));
        }
      }
      return result;
    }

    private AssetBlobRecord blob(long assetBlobId) {
      if (blobs.containsKey(assetBlobId)) {
        return blobs.get(assetBlobId);
      }
      return new AssetBlobRecord(
          assetBlobId,
          1L,
          "blob",
          new byte[] {1},
          "object",
          new byte[] {1},
          "sha1",
          "sha256-" + assetBlobId,
          "md5",
          1234L,
          "application/x-rpm",
          "test",
          "127.0.0.1",
          Instant.parse("2026-06-09T00:00:00Z"),
          Instant.parse("2026-06-09T00:00:00Z"),
          Map.of());
    }

    @Override
    public int updateBlobAttributes(long blobId, Map<String, Object> attributes) {
      updatedAttributes.put(blobId, attributes);
      return 1;
    }

    Map<String, Object> updatedAttributes(long blobId) {
      return updatedAttributes.get(blobId);
    }
  }

  private static final class RecordingIndexRebuildDao extends RepositoryIndexRebuildDaoAdapter {
    private final List<String> enqueues = new ArrayList<>();

    RecordingIndexRebuildDao() {
      super(null);
    }

    @Override
    public void enqueue(long repositoryId, String indexKind) {
      enqueues.add(repositoryId + ":" + indexKind + ":");
    }
  }

  private static final class SingleBlobStorageRegistry extends BlobStorageRegistry {
    private final BlobStorage storage;

    SingleBlobStorageRegistry(BlobStorage storage) {
      super(null, null, null, null, false);
      this.storage = storage;
    }

    @Override
    public BlobStorage forBlobStoreId(long blobStoreId) {
      return storage;
    }
  }

  private static final class RangeBlobStorage implements BlobStorage {
    private final byte[] bytes;
    private long lastStart = -1L;
    private long lastLength = -1L;

    RangeBlobStorage(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      return new BlobReference("bucket", "rpm-object", sha256, size);
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      return Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public Optional<InputStream> getRange(BlobReference reference, long start, long length) {
      lastStart = start;
      lastLength = length;
      return Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public boolean exists(BlobReference reference) {
      return true;
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
    }
  }
}
