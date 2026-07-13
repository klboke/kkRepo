package com.github.klboke.kkrepo.server.goartifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

class GoAssetWriterTest {
  private static final String PATH = "example.com/acme/demo/@v/v1.2.3.zip";

  @Test
  void writesNewComponentAssetAndKeepsResponseBody() throws Exception {
    Fixture fixture = fixture();
    stubUploadedBlob(fixture);
    when(fixture.assetDao.findAssetByPath(10L, PATH)).thenReturn(Optional.empty());
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(3L);
    when(fixture.assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.of(11L));

    GoAssetWriter.Stored stored = fixture.writer.write(
        runtime(), fixture.storage, 7L, GoPath.parse(PATH),
        new ByteArrayInputStream("archive".getBytes(StandardCharsets.UTF_8)), Map.of(), true);

    assertEquals(11L, stored.asset().id());
    assertEquals("example.com/acme/demo", stored.asset().attributes().get("module"));
    assertEquals("v1.2.3", stored.asset().attributes().get("version"));
    assertEquals("archive",
        new String(stored.openBody().readAllBytes(), StandardCharsets.UTF_8));
    verify(fixture.componentDao).upsertReturningId(any());
    verify(fixture.browseNodeDao).upsertPathAncestors(10L, PATH, 11L, 3L);
    verify(fixture.cache).evictAfterCommit(10L, PATH);
  }

  @Test
  void updatesExistingAssetAndMarksPreviousBlobDeleted() {
    Fixture fixture = fixture();
    stubUploadedBlob(fixture);
    AssetRecord existing = asset(11L, 99L);
    when(fixture.assetDao.findAssetByPath(10L, PATH)).thenReturn(Optional.of(existing));
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(3L);

    GoAssetWriter.Stored stored = fixture.writer.write(
        runtime(), fixture.storage, 7L, GoPath.parse(PATH),
        new ByteArrayInputStream("archive".getBytes(StandardCharsets.UTF_8)), Map.of());

    assertEquals(2L, stored.asset().assetBlobId());
    verify(fixture.assetDao).updateAssetBlobBindingAndMetadata(
        eq(11L), eq(3L), eq(2L), eq("PACKAGE"), eq("application/zip"),
        eq(7L), any(Instant.class), any());
    verify(fixture.assetDao).markBlobDeletedIfUnreferenced(99L, "asset replaced");
  }

  @Test
  void recoversFromConcurrentAssetInsert() {
    Fixture fixture = fixture();
    stubUploadedBlob(fixture);
    AssetRecord winner = asset(12L, 88L);
    when(fixture.assetDao.findAssetByPath(10L, PATH))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(3L);
    when(fixture.assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.empty());

    GoAssetWriter.Stored stored = fixture.writer.write(
        runtime(), fixture.storage, 7L, GoPath.parse(PATH),
        new ByteArrayInputStream("archive".getBytes(StandardCharsets.UTF_8)), Map.of());

    assertEquals(12L, stored.asset().id());
    verify(fixture.assetDao).updateAssetBlobBindingAndMetadata(
        eq(12L), eq(3L), eq(2L), anyString(), anyString(), eq(7L), any(), any());
    verify(fixture.assetDao).markBlobDeletedIfUnreferenced(88L, "asset replaced");
  }

  @Test
  void reusesExistingBlobWithoutUploadingAndRecoversDeletedBlobForRemoteAttributes() {
    Fixture reusable = fixture();
    AssetBlobRecord blob = blob(5L, "object");
    when(reusable.assetDao.findReusableBlobBySha256(eq(7L), anyString(), eq(7L)))
        .thenReturn(Optional.of(blob));
    when(reusable.assetDao.findAssetByPath(10L, PATH)).thenReturn(Optional.empty());
    when(reusable.componentDao.upsertReturningId(any())).thenReturn(3L);
    when(reusable.assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.of(11L));

    GoAssetWriter.Stored reused = reusable.writer.write(
        runtime(), reusable.storage, 7L, GoPath.parse(PATH),
        new ByteArrayInputStream("archive".getBytes(StandardCharsets.UTF_8)), Map.of());
    assertEquals(5L, reused.blob().id());
    verify(reusable.storage, never()).putFile(anyString(), anyString(), any(), anyString());

    Fixture recovered = fixture();
    BlobReference uploaded = new BlobReference("bucket", "uploaded", "sha256", 7L);
    when(recovered.storage.putFile(eq("go"), eq(PATH), any(Path.class), anyString()))
        .thenReturn(uploaded);
    when(recovered.assetDao.recoverDeletedBlobBySha256(eq(7L), anyString(), eq(7L)))
        .thenReturn(Optional.of(blob));
    when(recovered.assetDao.findAssetByPath(10L, PATH)).thenReturn(Optional.empty());
    when(recovered.componentDao.upsertReturningId(any())).thenReturn(3L);
    when(recovered.assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.of(11L));
    when(recovered.assetDao.hasLiveBlobForObjectKeyHash(eq(7L), any())).thenReturn(false);

    GoAssetWriter.Stored restored = recovered.writer.write(
        runtime(), recovered.storage, 7L, GoPath.parse(PATH),
        new ByteArrayInputStream("archive".getBytes(StandardCharsets.UTF_8)),
        Map.of("remoteEtag", "etag"));

    assertEquals("etag", restored.blob().attributes().get("remoteEtag"));
    verify(recovered.assetDao).updateBlobAttributes(5L, Map.of("remoteEtag", "etag"));
    verify(recovered.storage).delete(uploaded);
  }

  @Test
  void cleansUploadedBlobWhenMetadataPersistenceFails() {
    Fixture fixture = fixture();
    BlobReference uploaded = new BlobReference("bucket", "uploaded", "sha256", 7L);
    when(fixture.assetDao.findReusableBlobBySha256(eq(7L), anyString(), eq(7L)))
        .thenReturn(Optional.empty());
    when(fixture.storage.putFile(eq("go"), eq(PATH), any(Path.class), anyString()))
        .thenReturn(uploaded);
    when(fixture.assetDao.findAssetByPath(10L, PATH)).thenReturn(Optional.empty());
    when(fixture.assetDao.insertBlobOrFindExisting(any()))
        .thenThrow(new IllegalStateException("database unavailable"));
    when(fixture.assetDao.hasLiveBlobForObjectKeyHash(eq(7L), any())).thenReturn(false);

    assertThrows(IllegalStateException.class, () -> fixture.writer.write(
        runtime(), fixture.storage, 7L, GoPath.parse(PATH),
        new ByteArrayInputStream("archive".getBytes(StandardCharsets.UTF_8)), Map.of()));

    verify(fixture.storage).delete(uploaded);
  }

  private static void stubUploadedBlob(Fixture fixture) {
    when(fixture.assetDao.findReusableBlobBySha256(eq(7L), anyString(), anyLong()))
        .thenReturn(Optional.empty());
    when(fixture.storage.putFile(eq("go"), anyString(), any(Path.class), anyString()))
        .thenAnswer(invocation -> new BlobReference(
            "bucket", invocation.getArgument(1), invocation.getArgument(3), 7L));
    when(fixture.assetDao.insertBlobOrFindExisting(any()))
        .thenAnswer(invocation -> ((AssetBlobRecord) invocation.getArgument(0)).withId(2L));
  }

  private static Fixture fixture() {
    AssetDao assetDao = mock(AssetDao.class);
    ComponentDao componentDao = mock(ComponentDao.class);
    BrowseNodeDao browseNodeDao = mock(BrowseNodeDao.class);
    AssetMetadataCache cache = mock(AssetMetadataCache.class);
    BlobStorage storage = mock(BlobStorage.class);
    return new Fixture(
        assetDao, componentDao, browseNodeDao, cache, storage,
        new GoAssetWriter(assetDao, componentDao, browseNodeDao, cache, null));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        10L, "go", RepositoryFormat.GO, RepositoryType.PROXY, "go", true, 7L,
        null, null, null, true, "https://proxy.golang.org/",
        60, 60, true, null, List.of());
  }

  private static AssetRecord asset(long id, long blobId) {
    return new AssetRecord(
        id, 10L, 3L, blobId, RepositoryFormat.GO, PATH, null,
        "v1.2.3.zip", "PACKAGE", "application/zip", 7L,
        null, Instant.EPOCH, Map.of());
  }

  private static AssetBlobRecord blob(long id, String objectKey) {
    return new AssetBlobRecord(
        id, 7L, "blob://bucket/" + objectKey, null, objectKey, null,
        "sha1", "sha256", "md5", 7L, "application/zip", "proxy", "upstream",
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private record Fixture(
      AssetDao assetDao,
      ComponentDao componentDao,
      BrowseNodeDao browseNodeDao,
      AssetMetadataCache cache,
      BlobStorage storage,
      GoAssetWriter writer) {
  }
}
