package com.github.klboke.kkrepo.server.raw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RawAssetWriterTest {
  private static final String PATH = "docs/file.txt";

  @Test
  void writesNewAssetWithDigestsAttributesAndResponseBody() throws Exception {
    Fixture fixture = fixture();
    stubUploadedBlob(fixture);
    when(fixture.assetDao.findAssetByPath(1L, PATH)).thenReturn(Optional.empty());
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(22L);
    when(fixture.assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.of(11L));

    RawAssetWriter.Stored stored = fixture.writer.write(
        runtime(), fixture.storage, 1L, PATH,
        new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
        null, Map.of("remoteEtag", "etag"), "alice", "127.0.0.1", true);

    assertTrue(stored.created());
    assertEquals(11L, stored.asset().id());
    assertEquals("application/octet-stream", stored.asset().contentType());
    assertEquals(4L, stored.digests().size());
    assertNotNull(stored.digests().sha512());
    assertEquals("etag", stored.blob().attributes().get("remoteEtag"));
    assertEquals("body",
        new String(stored.openBody().readAllBytes(), StandardCharsets.UTF_8));
    verify(fixture.browseNodeDao).upsertPathAncestors(1L, PATH, 11L, 22L);
    verify(fixture.cache).evictAfterCommit(1L, PATH);
  }

  @Test
  void replacesExistingAssetAndMarksOldBlobDeleted() {
    Fixture fixture = fixture();
    stubUploadedBlob(fixture);
    AssetRecord existing = asset(11L, 33L);
    when(fixture.assetDao.findAssetByPath(1L, PATH)).thenReturn(Optional.of(existing));
    when(fixture.assetDao.findBlobById(33L)).thenReturn(Optional.of(blob(33L, "old")));
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(22L);

    RawAssetWriter.Stored stored = fixture.writer.write(
        runtime(), fixture.storage, 1L, PATH,
        new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
        "text/plain", Map.of(), "alice", "127.0.0.1");

    assertFalse(stored.created());
    assertEquals(2L, stored.asset().assetBlobId());
    verify(fixture.assetDao).updateAssetBlobBindingAndMetadata(
        eq(11L), eq(22L), eq(2L), eq("raw"), eq("text/plain"),
        eq(4L), any(Instant.class), eq(Map.of("path", PATH)));
    verify(fixture.assetDao).markBlobDeletedIfUnreferenced(33L, "asset replaced");
  }

  @Test
  void updatesConcurrentInsertWinner() {
    Fixture fixture = fixture();
    stubUploadedBlob(fixture);
    AssetRecord winner = asset(12L, 44L);
    when(fixture.assetDao.findAssetByPath(1L, PATH))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(fixture.assetDao.findBlobById(44L)).thenReturn(Optional.of(blob(44L, "winner")));
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(22L);
    when(fixture.assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.empty());

    RawAssetWriter.Stored stored = fixture.writer.write(
        runtime(), fixture.storage, 1L, PATH,
        new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
        "text/plain", Map.of(), "alice", "127.0.0.1");

    assertFalse(stored.created());
    assertEquals(12L, stored.asset().id());
    verify(fixture.assetDao).markBlobDeletedIfUnreferenced(44L, "asset replaced");
  }

  @Test
  void reusesLiveBlobAndRecoversDeletedBlobWithExtraAttributes() {
    Fixture reusable = fixture();
    AssetBlobRecord live = blob(5L, "shared");
    when(reusable.assetDao.findReusableBlobBySha256(eq(1L), anyString(), eq(4L)))
        .thenReturn(Optional.of(live));
    when(reusable.assetDao.findAssetByPath(1L, PATH)).thenReturn(Optional.empty());
    when(reusable.componentDao.upsertReturningId(any())).thenReturn(22L);
    when(reusable.assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.of(11L));

    RawAssetWriter.Stored reused = reusable.writer.write(
        runtime(), reusable.storage, 1L, PATH,
        new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
        "text/plain", Map.of(), "alice", "127.0.0.1");

    assertEquals(5L, reused.blob().id());
    verify(reusable.storage, never()).putFile(anyString(), anyString(), any(), anyString());

    Fixture recovered = fixture();
    BlobReference uploaded = new BlobReference("bucket", "uploaded", "sha256", 4L);
    when(recovered.storage.putFile(eq("raw"), eq(PATH), any(Path.class), anyString()))
        .thenReturn(uploaded);
    when(recovered.assetDao.recoverDeletedBlobBySha256(eq(1L), anyString(), eq(4L)))
        .thenReturn(Optional.of(live));
    when(recovered.assetDao.findAssetByPath(1L, PATH)).thenReturn(Optional.empty());
    when(recovered.componentDao.upsertReturningId(any())).thenReturn(22L);
    when(recovered.assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.of(11L));
    when(recovered.assetDao.hasLiveBlobForObjectKeyHash(eq(1L), any())).thenReturn(false);

    RawAssetWriter.Stored restored = recovered.writer.write(
        runtime(), recovered.storage, 1L, PATH,
        new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
        "text/plain", Map.of("remoteEtag", "etag"), "proxy", "upstream");

    assertEquals("etag", restored.blob().attributes().get("remoteEtag"));
    assertNotNull(restored.blob().attributes().get("sha512"));
    verify(recovered.assetDao).updateBlobAttributes(eq(5L), any());
    verify(recovered.storage).delete(uploaded);
  }

  @Test
  void cleansUploadedBlobWhenMetadataPersistenceFails() {
    Fixture fixture = fixture();
    BlobReference uploaded = new BlobReference("bucket", "uploaded", "sha256", 4L);
    when(fixture.assetDao.findReusableBlobBySha256(eq(1L), anyString(), eq(4L)))
        .thenReturn(Optional.empty());
    when(fixture.storage.putFile(eq("raw"), eq(PATH), any(Path.class), anyString()))
        .thenReturn(uploaded);
    when(fixture.assetDao.findAssetByPath(1L, PATH)).thenReturn(Optional.empty());
    when(fixture.assetDao.insertBlobOrFindExisting(any()))
        .thenThrow(new IllegalStateException("database unavailable"));
    when(fixture.assetDao.hasLiveBlobForObjectKeyHash(eq(1L), any())).thenReturn(false);

    assertThrows(IllegalStateException.class, () -> fixture.writer.write(
        runtime(), fixture.storage, 1L, PATH,
        new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
        "text/plain", Map.of(), "alice", "127.0.0.1"));

    verify(fixture.storage).delete(uploaded);
  }

  @Test
  void writesUnindexedAssetWithoutCreatingAComponent() {
    Fixture fixture = fixture();
    stubUploadedBlob(fixture);
    when(fixture.assetDao.findAssetByPath(1L, PATH)).thenReturn(Optional.empty());
    when(fixture.assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.of(11L));

    RawAssetWriter.Stored stored = fixture.writer.writeUnindexed(
        runtime(), fixture.storage, 1L, PATH,
        new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
        "text/plain", Map.of(), "terraform", null, false);

    assertEquals(null, stored.asset().componentId());
    verifyNoInteractions(fixture.componentDao);
    verify(fixture.browseNodeDao).upsertPathAncestors(1L, PATH, 11L, null);
  }

  @Test
  void writesAssetAgainstAnExplicitLogicalComponent() {
    Fixture fixture = fixture();
    stubUploadedBlob(fixture);
    when(fixture.assetDao.findAssetByPath(1L, PATH)).thenReturn(Optional.empty());
    when(fixture.assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.of(11L));
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(77L);
    ComponentRecord component = new ComponentRecord(
        null, 1L, RepositoryFormat.RAW, "docs", "logical", "1.0.0", "logical",
        new byte[32], Map.of("browsePath", "docs/logical/1.0.0"), Instant.now());

    RawAssetWriter.Stored stored = fixture.writer.writeWithComponent(
        runtime(), fixture.storage, 1L, PATH,
        new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)),
        "text/plain", Map.of(), "terraform", null, component, false);

    assertEquals(77L, stored.asset().componentId());
    verify(fixture.componentDao).upsertReturningId(component);
    ArgumentCaptor<AssetRecord> inserted = ArgumentCaptor.forClass(AssetRecord.class);
    verify(fixture.assetDao).tryInsertAsset(inserted.capture());
    assertEquals(77L, inserted.getValue().componentId());
    verify(fixture.browseNodeDao).upsertPathAncestors(1L, PATH, 11L, 77L);
  }

  @Test
  void deleteMissingAssetIsNoOp() {
    Fixture fixture = fixture();

    assertEquals(0, fixture.writer.deleteAsset(runtime(), fixture.storage, "missing"));

    verifyNoInteractions(fixture.componentDao, fixture.browseNodeDao, fixture.cache);
  }

  @Test
  void deletesAssetMetadataAndUnreferencedBlob() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime();
    AssetRecord asset = new AssetRecord(
        11L, runtime.id(), 22L, 33L, RepositoryFormat.RAW, "docs/file.txt", null,
        "file.txt", "raw", "text/plain", 4L, null, Instant.EPOCH, Map.of());
    when(fixture.assetDao.findAssetByPath(runtime.id(), "docs/file.txt"))
        .thenReturn(Optional.of(asset));

    assertEquals(1, fixture.writer.deleteAsset(
        runtime, fixture.storage, "docs/file.txt"));

    verify(fixture.browseNodeDao).deleteByAssetId(11L);
    verify(fixture.assetDao).deleteAssetById(11L);
    verify(fixture.assetDao).markBlobDeletedIfUnreferenced(33L, "asset unlinked");
    verify(fixture.componentDao).deleteIfNoAssets(22L);
    verify(fixture.cache).evictAfterCommit(runtime.id(), "docs/file.txt");
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    ComponentDao componentDao = mock(ComponentDao.class);
    BrowseNodeDao browseNodeDao = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    BlobStorage storage = mock(BlobStorage.class);
    return new Fixture(
        assetDao,
        componentDao,
        browseNodeDao,
        cache,
        storage,
        new RawAssetWriter(assetDao, componentDao, browseNodeDao, cache, null));
  }

  private static void stubUploadedBlob(Fixture fixture) {
    when(fixture.assetDao.findReusableBlobBySha256(eq(1L), anyString(), anyLong()))
        .thenReturn(Optional.empty());
    when(fixture.storage.putFile(eq("raw"), anyString(), any(Path.class), anyString()))
        .thenAnswer(invocation -> new BlobReference(
            "bucket", invocation.getArgument(1), invocation.getArgument(3), 4L));
    when(fixture.assetDao.insertBlobOrFindExisting(any()))
        .thenAnswer(invocation -> ((AssetBlobRecord) invocation.getArgument(0)).withId(2L));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw", true, 1L,
        "ALLOW", null, null, true, null, 60, 60, true, "ATTACHMENT", List.of());
  }

  private static AssetRecord asset(long id, long blobId) {
    return new AssetRecord(
        id, 1L, 22L, blobId, RepositoryFormat.RAW, PATH, null,
        "file.txt", "raw", "text/plain", 4L, null, Instant.EPOCH, Map.of());
  }

  private static AssetBlobRecord blob(long id, String objectKey) {
    return new AssetBlobRecord(
        id, 1L, "blob://bucket/" + objectKey, null, objectKey, null,
        "sha1", "sha256", "md5", 4L, "text/plain", "user", "127.0.0.1",
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private record Fixture(
      AssetDao assetDao,
      ComponentDao componentDao,
      BrowseNodeDao browseNodeDao,
      AssetMetadataCache cache,
      BlobStorage storage,
      RawAssetWriter writer) {
  }
}
