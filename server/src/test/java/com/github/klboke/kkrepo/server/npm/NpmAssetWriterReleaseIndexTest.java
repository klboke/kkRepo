package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.NpmReleaseIndexDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmMinimumReleaseAge;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
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

class NpmAssetWriterReleaseIndexTest {
  private static final NpmPackageId PACKAGE = NpmPackageId.parse("demo");

  @Test
  void packageRootOverloadsPersistOrderedReleaseIndexRows() {
    Fixture fixture = fixture(true);
    NpmMinimumReleaseAge.ReleaseIndex index = new NpmMinimumReleaseAge.ReleaseIndex(List.of(
        new NpmMinimumReleaseAge.IndexedRelease(
            "1.0.0", Instant.parse("2026-07-19T10:00:00Z"), null, "demo-1.0.0.tgz"),
        new NpmMinimumReleaseAge.IndexedRelease(
            "broken", null, "missing-publish-time", "demo-broken.tgz")));

    NpmAssetWriter.Stored indexed = fixture.writer.writePackageRoot(
        runtime(), fixture.storage, 1L, PACKAGE, json(), "proxy", "upstream", Map.of(), index);
    fixture.writer.writePackageRoot(
        runtime(), fixture.storage, 1L, PACKAGE, json(), "proxy", "upstream", Map.of());
    fixture.writer.writePackageRoot(
        runtime(), fixture.storage, 1L, PACKAGE, json(), "proxy", "upstream",
        Map.of(), Map.of("cache", "fresh"), false);

    assertEquals("demo", indexed.asset().path());
    assertEquals(null, indexed.responseFile());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<NpmReleaseIndexDao.Release>> rows = ArgumentCaptor.forClass(List.class);
    verify(fixture.releaseIndexDao).replaceIfCurrent(
        eq(70L), eq(50L), eq(false), rows.capture(), any(Instant.class));
    assertEquals(List.of(
        new NpmReleaseIndexDao.Release(
            0, "1.0.0", Instant.parse("2026-07-19T10:00:00Z"), null, "demo-1.0.0.tgz"),
        new NpmReleaseIndexDao.Release(
            1, "broken", null, "missing-publish-time", "demo-broken.tgz")), rows.getValue());
  }

  @Test
  void releaseIndexRevisionMismatchFailsThePackageRootWrite() {
    Fixture fixture = fixture(false);
    NpmMinimumReleaseAge.ReleaseIndex index = new NpmMinimumReleaseAge.ReleaseIndex(List.of(
        new NpmMinimumReleaseAge.IndexedRelease(
            "1.0.0", Instant.parse("2026-07-19T10:00:00Z"), null, "demo-1.0.0.tgz")));

    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> fixture.writer.writePackageRoot(
            runtime(), fixture.storage, 1L, PACKAGE, json(), "proxy", "upstream", Map.of(), index));

    assertEquals(
        "npm release index revision no longer matches package root npm-proxy/demo",
        error.getMessage());
  }

  @Test
  void tarballWriteCanRetainItsResponseFile() {
    Fixture fixture = fixture(true);

    NpmAssetWriter.Stored stored = fixture.writer.writeTarball(
        runtime(), fixture.storage, 1L, PACKAGE, "1.0.0", "demo-1.0.0.tgz",
        new ByteArrayInputStream("tarball".getBytes(StandardCharsets.UTF_8)),
        "application/octet-stream", "proxy", "upstream", Map.of(), true);

    assertNotNull(stored.responseFile());
    assertFalse(stored.responseFile().toFile().isDirectory());
    stored.discardBody();
  }

  private static Fixture fixture(boolean indexAccepted) {
    AssetDao assetDao = mock(AssetDao.class);
    ComponentDao componentDao = mock(ComponentDao.class);
    BrowseNodeDao browseNodeDao = mock(BrowseNodeDao.class);
    AssetMetadataCache metadataCache = mock(AssetMetadataCache.class);
    NpmReleaseIndexDao releaseIndexDao = mock(NpmReleaseIndexDao.class);
    BlobStorage storage = mock(BlobStorage.class);
    BlobReference reference = new BlobReference("default", "objects/npm-test", "sha256", 7L);

    when(storage.putFile(anyString(), anyString(), any(Path.class), anyString()))
        .thenReturn(reference);
    when(assetDao.findAssetByPath(anyLong(), anyString())).thenReturn(Optional.empty());
    when(assetDao.findReusableBlobBySha256(anyLong(), anyString(), anyLong()))
        .thenReturn(Optional.empty());
    when(assetDao.recoverDeletedBlobBySha256(anyLong(), anyString(), anyLong()))
        .thenReturn(Optional.empty());
    when(assetDao.insertBlobOrFindExisting(any(AssetBlobRecord.class)))
        .thenAnswer(invocation -> withId(invocation.getArgument(0), 50L));
    when(assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.of(70L));
    when(componentDao.upsertReturningId(any())).thenReturn(80L);
    when(releaseIndexDao.replaceIfCurrent(
        anyLong(), anyLong(), any(Boolean.class), any(), any(Instant.class)))
        .thenReturn(indexAccepted);

    NpmAssetWriter writer = new NpmAssetWriter(
        assetDao, componentDao, browseNodeDao, null, metadataCache,
        null, null, releaseIndexDao);
    return new Fixture(writer, storage, releaseIndexDao);
  }

  private static AssetBlobRecord withId(AssetBlobRecord record, long id) {
    return new AssetBlobRecord(
        id, record.blobStoreId(), record.blobRef(), record.blobRefHash(), record.objectKey(),
        record.objectKeyHash(), record.sha1(), record.sha256(), record.md5(), record.size(),
        record.contentType(), record.createdBy(), record.createdByIp(), record.blobCreatedAt(),
        record.blobUpdatedAt(), record.attributes());
  }

  private static byte[] json() {
    return "{\"name\":\"demo\",\"versions\":{}}".getBytes(StandardCharsets.UTF_8);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        10L, "npm-proxy", RepositoryFormat.NPM, RepositoryType.PROXY, "npm-proxy", true,
        1L, null, "MIXED", "PERMISSIVE", true, "https://registry.npmjs.org",
        1440, 1440, true, null, List.of());
  }

  private record Fixture(
      NpmAssetWriter writer,
      BlobStorage storage,
      NpmReleaseIndexDao releaseIndexDao) {
  }
}
