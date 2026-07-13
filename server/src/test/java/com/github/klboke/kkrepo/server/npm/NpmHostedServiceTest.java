package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class NpmHostedServiceTest {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
  };
  private static final NpmPackageId PACKAGE = NpmPackageId.parse("demo");

  @Test
  void publishesPackageRootAndAttachmentWithGeneratedRevision() throws Exception {
    Fixture fixture = fixture();
    when(fixture.assetDao.findAssetByPath(10L, "demo")).thenReturn(Optional.empty());
    when(fixture.assetDao.findAssetByPath(10L, "demo/-/demo-1.0.0.tgz"))
        .thenReturn(Optional.empty());
    String attachment = Base64.getEncoder()
        .encodeToString("tarball".getBytes(StandardCharsets.UTF_8));
    String body = """
        {
          "name":"demo",
          "versions":{"1.0.0":{"name":"demo","version":"1.0.0",
            "dist":{"tarball":"demo-1.0.0.tgz"}}},
          "dist-tags":{"latest":"1.0.0"},
          "_attachments":{"demo-1.0.0.tgz":{"data":"%s","content_type":"application/octet-stream"}}
        }
        """.formatted(attachment);

    assertEquals(200, fixture.service.putPackage(
        runtime("ALLOW", 7L), PACKAGE, null,
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
        "alice", "127.0.0.1").status());

    verify(fixture.writer).writeTarball(
        eq(runtime("ALLOW", 7L)), eq(fixture.storage), eq(7L), eq(PACKAGE),
        eq("1.0.0"), eq("demo-1.0.0.tgz"), any(), eq("application/octet-stream"),
        eq("alice"), eq("127.0.0.1"), eq(Map.of()));
    ArgumentCaptor<byte[]> json = ArgumentCaptor.forClass(byte[].class);
    verify(fixture.writer).writePackageRoot(
        eq(runtime("ALLOW", 7L)), eq(fixture.storage), eq(7L), eq(PACKAGE),
        json.capture(), eq("alice"), eq("127.0.0.1"));
    Map<String, Object> stored = fixture.mapper.readValue(json.getValue(), MAP);
    assertNotNull(stored.get("_rev"));
    assertEquals(null, stored.get("_attachments"));
  }

  @Test
  void rejectsPackageNameAndRevisionMismatches() {
    Fixture fixture = fixture();
    assertThrows(NpmExceptions.BadRequestException.class, () -> fixture.service.putPackage(
        runtime("ALLOW", 7L), PACKAGE, null,
        new ByteArrayInputStream("{\"name\":\"other\"}".getBytes(StandardCharsets.UTF_8)),
        "alice", null));

    stubPackageRoot(fixture, """
        {"name":"demo","_rev":"2-existing","versions":{},"dist-tags":{}}
        """);
    assertThrows(NpmExceptions.BadRequestException.class, () -> fixture.service.putPackage(
        runtime("ALLOW", 7L), PACKAGE, "1-stale",
        new ByteArrayInputStream("{\"name\":\"demo\"}".getBytes(StandardCharsets.UTF_8)),
        "alice", null));
    verify(fixture.writer, never()).writePackageRoot(
        any(), any(), anyLong(), any(), any(), anyString(), any());
  }

  @Test
  void validatesAttachmentsBeforeWritingAnyBlob() {
    Fixture fixture = fixture();
    when(fixture.assetDao.findAssetByPath(10L, "demo")).thenReturn(Optional.empty());
    String body = """
        {"name":"demo","versions":{},
         "_attachments":{"demo-1.0.0.tgz":{"data":"dGFyYmFsbA=="}}}
        """;

    assertThrows(NpmExceptions.BadRequestException.class, () -> fixture.service.putPackage(
        runtime("ALLOW", 7L), PACKAGE, null,
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
        "alice", null));

    verify(fixture.registry, never()).forBlobStoreId(anyLong());
    verify(fixture.writer, never()).writeTarball(
        any(), any(), anyLong(), any(), anyString(), anyString(), any(),
        anyString(), anyString(), any(), any());
  }

  @Test
  void enforcesHostedWriteAndDeletePolicies() {
    Fixture fixture = fixture();
    assertThrows(NpmExceptions.MethodNotAllowed.class, () -> fixture.service.putPackage(
        proxyRuntime(), PACKAGE, null,
        new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), "alice", null));
    assertThrows(NpmExceptions.WritePolicyDenied.class, () -> fixture.service.putPackage(
        runtime("DENY", 7L), PACKAGE, null,
        new ByteArrayInputStream("{\"name\":\"demo\"}".getBytes(StandardCharsets.UTF_8)),
        "alice", null));
    assertThrows(NpmExceptions.WritePolicyDenied.class,
        () -> fixture.service.deletePackage(runtime("DENY", 7L), PACKAGE, null));
    assertThrows(IllegalStateException.class, () -> fixture.service.putPackage(
        runtime("ALLOW", null), PACKAGE, null,
        new ByteArrayInputStream("{\"name\":\"demo\"}".getBytes(StandardCharsets.UTF_8)),
        "alice", null));
  }

  @Test
  void validatesDistTagsAndMultipartUploads() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime("ALLOW", 7L);
    assertThrows(NpmExceptions.BadRequestException.class, () -> fixture.service.putDistTag(
        runtime, PACKAGE, "latest",
        new ByteArrayInputStream("\"1.0.0\"".getBytes(StandardCharsets.UTF_8)),
        "alice", null));
    assertThrows(NpmExceptions.BadRequestException.class, () -> fixture.service.deleteDistTag(
        runtime, PACKAGE, "latest", "alice", null));
    assertThrows(NpmExceptions.BadRequestException.class,
        () -> fixture.service.uploadTarball(runtime, null, "alice", null));
    assertThrows(NpmExceptions.BadRequestException.class,
        () -> fixture.service.uploadTarball(
            runtime,
            new MockMultipartFile("asset", "demo.zip", "application/zip", new byte[] {1}),
            "alice", null));

    assertEquals(200, fixture.service.putDistTag(
        runtime, PACKAGE, "beta",
        new ByteArrayInputStream("\"1.0.0\"".getBytes(StandardCharsets.UTF_8)),
        "alice", null).status());
  }

  @Test
  void multipartTarballUploadCreatesVersionDistAndLatestTag() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime("ALLOW", 7L);
    when(fixture.assetDao.findAssetByPath(10L, "demo")).thenReturn(Optional.empty());
    when(fixture.assetDao.findAssetByPath(10L, "demo/-/demo-1.0.0.tgz"))
        .thenReturn(Optional.empty());
    when(fixture.writer.writeTarball(
        eq(runtime), eq(fixture.storage), eq(7L), eq(PACKAGE), eq("1.0.0"),
        eq("demo-1.0.0.tgz"), any(), eq("application/gzip"), eq("alice"), eq("127.0.0.1"),
        eq(Map.of())))
        .thenReturn(new NpmAssetWriter.Stored(
            null,
            null,
            new NpmAssetWriter.Digests("md5", "sha1-value", "sha256", "sha512", 123L),
            true,
            null));
    MockMultipartFile upload = new MockMultipartFile(
        "asset",
        "demo-1.0.0.tgz",
        "application/gzip",
        tarball("package/package.json", """
            {"name":"demo","version":"1.0.0","description":"demo package"}
            """));

    assertEquals(201, fixture.service.uploadTarball(
        runtime, upload, "alice", "127.0.0.1").status());

    ArgumentCaptor<byte[]> json = ArgumentCaptor.forClass(byte[].class);
    verify(fixture.writer).writePackageRoot(
        eq(runtime), eq(fixture.storage), eq(7L), eq(PACKAGE),
        json.capture(), eq("alice"), eq("127.0.0.1"));
    Map<String, Object> stored = fixture.mapper.readValue(json.getValue(), MAP);
    assertEquals("1.0.0", map(stored.get("dist-tags")).get("latest"));
    Map<String, Object> version = map(map(stored.get("versions")).get("1.0.0"));
    assertEquals("demo-1.0.0.tgz", map(version.get("dist")).get("tarball"));
    assertEquals("sha1-value", map(version.get("dist")).get("shasum"));
    assertNotNull(stored.get("_rev"));
  }

  @Test
  void multipartTarballRequiresPackageJsonNameAndVersion() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime("ALLOW", 7L);

    assertThrows(NpmExceptions.BadRequestException.class, () -> fixture.service.uploadTarball(
        runtime,
        new MockMultipartFile(
            "asset", "demo-1.0.0.tgz", "application/gzip",
            tarball("package/README.md", "readme")),
        "alice",
        null));
    assertThrows(NpmExceptions.BadRequestException.class, () -> fixture.service.uploadTarball(
        runtime,
        new MockMultipartFile(
            "asset", "demo-1.0.0.tgz", "application/gzip",
            tarball("package/package.json", "{\"name\":\"demo\"}")),
        "alice",
        null));

    verify(fixture.writer, never()).writeTarball(
        any(), any(), anyLong(), any(), anyString(), anyString(), any(),
        anyString(), anyString(), any(), any());
  }

  @Test
  void deletingTarballRemovesVersionAndTagsThatPointToIt() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime("ALLOW", 7L);
    stubPackageRoot(fixture, """
        {
          "name":"demo",
          "_rev":"2-existing",
          "versions":{
            "1.0.0":{"name":"demo","version":"1.0.0",
              "dist":{"tarball":"demo-1.0.0.tgz"}},
            "2.0.0":{"name":"demo","version":"2.0.0",
              "dist":{"tarball":"demo-2.0.0.tgz"}}
          },
          "dist-tags":{"latest":"2.0.0","beta":"2.0.0","stable":"1.0.0"}
        }
        """);
    when(fixture.writer.deletePath(
        runtime, fixture.storage, "demo/-/demo-2.0.0.tgz")).thenReturn(1);

    assertEquals(200, fixture.service.deleteTarball(
        runtime, PACKAGE, "demo-2.0.0.tgz", "2-existing").status());

    ArgumentCaptor<byte[]> json = ArgumentCaptor.forClass(byte[].class);
    verify(fixture.writer).writePackageRoot(
        eq(runtime), eq(fixture.storage), eq(7L), eq(PACKAGE),
        json.capture(), eq("system"), eq(null));
    Map<String, Object> stored = fixture.mapper.readValue(json.getValue(), MAP);
    assertEquals(List.of("1.0.0"), map(stored.get("versions")).keySet().stream().toList());
    assertEquals(Map.of("stable", "1.0.0"), map(stored.get("dist-tags")));
  }

  private static void stubPackageRoot(Fixture fixture, String json) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, 2L, 3L, RepositoryFormat.NPM, "demo", null,
        "demo", "package-root", "application/json", (long) json.length(),
        null, Instant.EPOCH, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        3L, 7L, "blob://bucket/demo", null, "demo", null,
        "sha1", "sha256", "md5", (long) json.length(), "application/json",
        "alice", null, Instant.EPOCH, Instant.EPOCH, Map.of());
    when(fixture.assetDao.findAssetByPath(10L, "demo")).thenReturn(Optional.of(asset));
    when(fixture.assetDao.findBlobById(3L)).thenReturn(Optional.of(blob));
    when(fixture.storage.get(any())).thenAnswer(invocation -> Optional.of(
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static byte[] tarball(String entryName, String content) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      byte[] body = content.getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry(entryName);
      entry.setSize(body.length);
      tar.putArchiveEntry(entry);
      tar.write(body);
      tar.closeArchiveEntry();
    }
    return bytes.toByteArray();
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    NpmAssetWriter writer = mock(NpmAssetWriter.class);
    BlobStorage storage = mock(BlobStorage.class);
    ObjectMapper mapper = new ObjectMapper();
    when(registry.forBlobStoreId(7L)).thenReturn(storage);
    return new Fixture(
        assetDao, registry, writer, storage, mapper,
        new NpmHostedService(assetDao, registry, writer, mapper, mock(AssetMetadataCache.class)));
  }

  private static RepositoryRuntime runtime(String writePolicy, Long blobStoreId) {
    return new RepositoryRuntime(
        10L, "npm-hosted", RepositoryFormat.NPM, RepositoryType.HOSTED, "npm-hosted", true,
        blobStoreId, writePolicy, null, null, true, null, null, null, null, null, List.of());
  }

  private static RepositoryRuntime proxyRuntime() {
    return new RepositoryRuntime(
        10L, "npm-proxy", RepositoryFormat.NPM, RepositoryType.PROXY, "npm-proxy", true,
        7L, null, null, null, true, "https://registry.npmjs.org/",
        60, 60, true, null, List.of());
  }

  private record Fixture(
      AssetDao assetDao,
      BlobStorageRegistry registry,
      NpmAssetWriter writer,
      BlobStorage storage,
      ObjectMapper mapper,
      NpmHostedService service) {
  }
}
