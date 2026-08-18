package com.github.klboke.kkrepo.server.raw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RawProtocolCacheTest {
  private static final String PATH = "org/model/resolve/" + "a".repeat(40) + "/config.json";

  @Test
  void findsAndServesPublishedAssetsThroughTheSharedReader() {
    Fixture fixture = fixture();
    when(fixture.assetDao.findAssetByPath(1L, PATH)).thenReturn(Optional.of(asset()));
    when(fixture.assetDao.findBlobById(2L)).thenReturn(Optional.of(blob()));
    MavenResponse expected = MavenResponse.noBody(200, 4, "application/json", "sha1", Instant.EPOCH);
    when(fixture.reader.serveSnapshot(any(), eq(true), eq(PATH), eq("inline")))
        .thenReturn(expected);

    assertTrue(fixture.cache.find(runtime(), PATH).isPresent());
    assertEquals(expected, fixture.cache.serve(runtime(), PATH, true, "inline"));
    verify(fixture.reader).serveSnapshot(any(), eq(true), eq(PATH), eq("inline"));
  }

  @Test
  void rejectsServingMissingAssetsAndRepositoriesWithoutBlobStores() {
    Fixture fixture = fixture();
    assertThrows(IllegalStateException.class,
        () -> fixture.cache.serve(runtime(), "missing", false, null));
    assertThrows(IllegalStateException.class, () -> fixture.cache.storeHidden(
        runtimeWithoutBlobStore(), PATH, new ByteArrayInputStream(new byte[0]),
        "application/json", Map.of()));
  }

  @Test
  void storesHiddenAndVerifiedAssetsWithSanitizedAttributes() {
    Fixture fixture = fixture();
    RawAssetWriter.Stored stored = stored(null);
    when(fixture.writer.writeHidden(
        any(), eq(fixture.storage), eq(1L), eq(PATH), any(), eq("application/json"),
        any(), eq("proxy"), eq(null), eq(false)))
        .thenReturn(stored);
    when(fixture.writer.writeVerifiedWithComponentAtBrowsePathIfAbsent(
        any(), eq(fixture.storage), eq(1L), eq(PATH), any(), eq("application/json"),
        eq(Map.of()), any(), eq("proxy"), eq(null), any(), eq("logical/config.json"),
        eq(4L), eq("sha256"), eq(null)))
        .thenReturn(stored);

    RawProtocolCache.StoredAsset hidden = fixture.cache.storeHidden(
        runtime(), PATH, new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
        "application/json", Map.of("role", "metadata"));
    RawProtocolCache.StoredAsset verified = fixture.cache.storeVerifiedImmutable(
        runtime(), PATH, new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
        "application/json", Map.of("fileKind", "OTHER"),
        component(), "logical/config.json", 4L, "sha256", null);

    assertEquals(11L, hidden.assetId());
    assertEquals(22L, verified.componentId());
    verify(fixture.writer).writeVerifiedWithComponentAtBrowsePathIfAbsent(
        any(), eq(fixture.storage), eq(1L), eq(PATH), any(), eq("application/json"),
        eq(Map.of()), eq(Map.of("path", PATH, "fileKind", "OTHER")),
        eq("proxy"), eq(null), any(), eq("logical/config.json"),
        eq(4L), eq("sha256"), eq(null));
  }

  @Test
  void failsClosedWhenWriterReturnsAnIncompleteBinding() {
    Fixture fixture = fixture();
    when(fixture.writer.writeHidden(any(), any(), anyLong(), anyString(), any(), anyString(),
        any(), anyString(), any(), anyBoolean())).thenReturn(null);

    assertThrows(IllegalStateException.class, () -> fixture.cache.storeHidden(
        runtime(), PATH, new ByteArrayInputStream(new byte[0]), "application/json", Map.of()));
  }

  @Test
  void servesFirstFillHeadFromVerifiedSnapshotAndDeletesStagingFile(@TempDir Path temporary)
      throws Exception {
    Fixture fixture = fixture();
    Path responseFile = Files.writeString(temporary.resolve("body"), "body");
    RawProtocolCache.StoredAsset stored = storedAsset(responseFile);

    MavenResponse response = fixture.cache.serveStored(runtime(), stored, true, "inline");

    assertEquals(200, response.status());
    assertFalse(response.hasBody());
    assertEquals(4L, response.contentLength());
    assertFalse(Files.exists(responseFile));
    verify(fixture.reader).beforeRead(11L, 3L, 1L);
  }

  @Test
  void streamsFirstFillBodyAndFallsBackToReaderWhenNoStagingFileExists(@TempDir Path temporary)
      throws Exception {
    Fixture fixture = fixture();
    Path responseFile = Files.writeString(temporary.resolve("body"), "body");
    MavenResponse response = fixture.cache.serveStored(
        runtime(), storedAsset(responseFile), false, "");
    try (var body = response.body()) {
      assertEquals("body", new String(body.readAllBytes(), StandardCharsets.UTF_8));
    }
    assertEquals("attachment", response.headers().get("Content-Disposition"));

    RawProtocolCache.StoredAsset durable = storedAsset(null);
    MavenResponse expected = MavenResponse.noBody(200);
    when(fixture.reader.serveSnapshot(any(), eq(false), eq(PATH), eq("attachment")))
        .thenReturn(expected);
    assertEquals(expected, fixture.cache.serveStored(runtime(), durable, false, "attachment"));
  }

  @Test
  void rejectsCrossRepositorySnapshotsAndDeletesBodiesOnPolicyFailure(@TempDir Path temporary)
      throws Exception {
    Fixture fixture = fixture();
    Path wrongBody = Files.writeString(temporary.resolve("wrong"), "body");
    CachedAssetMetadata wrong = new CachedAssetMetadata(
        11L, 2L, 22L, 3L, RepositoryFormat.HUGGINGFACE, PATH, "config.json", "OTHER",
        "application/json", 4L, Instant.EPOCH, Map.of(),
        CachedAssetMetadata.of(asset(), blob()).blob());
    assertThrows(IllegalArgumentException.class, () -> fixture.cache.serveStored(
        runtime(), new RawProtocolCache.StoredAsset(
            11L, 22L, 3L, "sha256", 4L, "application/json", wrong, wrongBody),
        false, "attachment"));
    assertFalse(Files.exists(wrongBody));

    Path deniedBody = Files.writeString(temporary.resolve("denied"), "body");
    doThrow(new IllegalStateException("denied"))
        .when(fixture.reader).beforeRead(11L, 3L, 1L);
    assertThrows(IllegalStateException.class, () -> fixture.cache.serveStored(
        runtime(), storedAsset(deniedBody), false, "attachment"));
    assertFalse(Files.exists(deniedBody));
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    RawAssetWriter writer = mock(RawAssetWriter.class);
    RawAssetReader reader = mock(RawAssetReader.class);
    when(storages.forBlobStoreId(1L)).thenReturn(storage);
    AssetMetadataCache metadata = new AssetMetadataCache(mock(SharedCache.class), false, 0, 0);
    return new Fixture(
        assetDao, storages, storage, writer, reader, new RawProtocolCache(
            assetDao, storages, writer, reader, metadata));
  }

  private static RawAssetWriter.Stored stored(Path responseFile) {
    return new RawAssetWriter.Stored(
        asset(), blob(), new RawAssetWriter.Digests("md5", "sha1", "sha256", "sha512", 4L),
        true, responseFile);
  }

  private static RawProtocolCache.StoredAsset storedAsset(Path responseFile) {
    return new RawProtocolCache.StoredAsset(
        11L, 22L, 3L, "sha256", 4L, "application/json",
        CachedAssetMetadata.of(asset(), blob()), responseFile);
  }

  private static AssetRecord asset() {
    return new AssetRecord(
        11L, 1L, 22L, 3L, RepositoryFormat.HUGGINGFACE, PATH, null,
        "config.json", "OTHER", "application/json", 4L, null, Instant.EPOCH,
        Map.of("path", PATH));
  }

  private static AssetBlobRecord blob() {
    return new AssetBlobRecord(
        3L, 1L, "blob://bucket/object", null, "object", null,
        "sha1", "sha256", "md5", 4L, "application/json", "proxy", null,
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private static ComponentRecord component() {
    return new ComponentRecord(
        22L, 1L, RepositoryFormat.HUGGINGFACE, "org", "model", "a".repeat(40),
        "model-revision", new byte[32], Map.of(), Instant.EPOCH);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "hf", RepositoryFormat.HUGGINGFACE, RepositoryType.PROXY, "huggingface-proxy",
        true, 1L, null, null, null, true, "https://huggingface.co", 1440, 60, true,
        null, List.of());
  }

  private static RepositoryRuntime runtimeWithoutBlobStore() {
    return new RepositoryRuntime(
        1L, "hf", RepositoryFormat.HUGGINGFACE, RepositoryType.PROXY, "huggingface-proxy",
        true, null, null, null, null, true, "https://huggingface.co", 1440, 60, true,
        null, List.of());
  }

  private record Fixture(
      AssetDao assetDao,
      BlobStorageRegistry storages,
      BlobStorage storage,
      RawAssetWriter writer,
      RawAssetReader reader,
      RawProtocolCache cache) {
  }
}
