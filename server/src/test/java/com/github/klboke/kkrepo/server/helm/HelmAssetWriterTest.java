package com.github.klboke.kkrepo.server.helm;

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
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.helm.HelmAssetKind;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HelmAssetWriterTest {
  @Test
  void writesChartPackageWithParsedMetadataAndDigests() throws Exception {
    Fixture fixture = fixture();
    byte[] chart = chartPackage("demo", "1.2.3");
    stubNewAsset(fixture, "demo-1.2.3.tgz");

    HelmAssetWriter.Stored stored = fixture.writer.write(
        runtime(), fixture.storage, 7L, "demo-1.2.3.tgz",
        new ByteArrayInputStream(chart), null, HelmAssetKind.PACKAGE, null,
        Map.of("remoteUrl", "https://example.test/demo.tgz"), Map.of(),
        "user", "127.0.0.1");

    assertTrue(stored.created());
    assertEquals("demo", stored.asset().attributes().get("name"));
    assertEquals("1.2.3", stored.asset().attributes().get("version"));
    assertEquals("PACKAGE", stored.asset().attributes().get("asset_kind"));
    assertEquals("https://example.test/demo.tgz", stored.asset().attributes().get("remoteUrl"));
    assertEquals(chart.length, stored.digests().size());
    assertNotNull(stored.digests().sha256());
    verify(fixture.componentDao).upsertReturningId(any());
    verify(fixture.browseNodeDao).upsertPathAncestors(10L, "demo-1.2.3.tgz", 11L, 3L);
    verify(fixture.cache).evictAfterCommit(10L, "demo-1.2.3.tgz");
  }

  @Test
  void parsesProvenanceCoordinatesAndUpdatesExistingAsset() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime();
    AssetRecord existing = new AssetRecord(
        11L, runtime.id(), 3L, 99L, RepositoryFormat.HELM, "demo-1.2.3.tgz.prov", null,
        "demo-1.2.3.tgz.prov", "PROVENANCE", "application/octet-stream", 1L,
        null, Instant.EPOCH, Map.of());
    when(fixture.assetDao.findAssetByPath(runtime.id(), "demo-1.2.3.tgz.prov"))
        .thenReturn(Optional.of(existing));
    stubBlobPersistence(fixture);
    byte[] provenance = """
        apiVersion: v2
        name: demo
        version: 1.2.3
        -----BEGIN PGP SIGNATURE-----
        ignored: true
        """.getBytes(StandardCharsets.UTF_8);

    HelmAssetWriter.Stored stored = fixture.writer.write(
        runtime, fixture.storage, 7L, "demo-1.2.3.tgz.prov",
        new ByteArrayInputStream(provenance), null, HelmAssetKind.PROVENANCE, null,
        Map.of(), Map.of("remoteEtag", "etag"), "proxy", "upstream");

    assertFalse(stored.created());
    assertEquals("demo", stored.asset().attributes().get("name"));
    assertEquals("1.2.3", stored.asset().attributes().get("version"));
    verify(fixture.assetDao).updateAssetBlobBindingAndMetadata(
        eq(11L), eq(3L), eq(2L), eq("PROVENANCE"), eq("application/octet-stream"),
        eq((long) provenance.length), any(Instant.class), any());
    verify(fixture.assetDao).markBlobDeletedIfUnreferenced(99L, "asset replaced");
  }

  @Test
  void cleansUploadedBlobWhenMetadataPersistenceFails() throws Exception {
    Fixture fixture = fixture();
    byte[] chart = chartPackage("demo", "1.0.0");
    when(fixture.assetDao.findReusableBlobBySha256(eq(7L), anyString(), eq((long) chart.length)))
        .thenReturn(Optional.empty());
    when(fixture.storage.putFile(eq("helm"), eq("demo-1.0.0.tgz"), any(Path.class), anyString()))
        .thenReturn(new BlobReference("bucket", "uploaded.tgz", "sha256", chart.length));
    when(fixture.assetDao.insertBlobOrFindExisting(any()))
        .thenThrow(new IllegalStateException("database unavailable"));
    when(fixture.assetDao.hasLiveBlobForObjectKeyHash(eq(7L), any())).thenReturn(false);

    assertThrows(IllegalStateException.class, () -> fixture.writer.write(
        runtime(), fixture.storage, 7L, "demo-1.0.0.tgz",
        new ByteArrayInputStream(chart), null, HelmAssetKind.PACKAGE, null,
        Map.of(), Map.of(), "user", "127.0.0.1"));

    verify(fixture.storage).delete(
        new BlobReference("bucket", "uploaded.tgz", "sha256", chart.length));
  }

  @Test
  void deletesMetadataAndUnreferencedBlob() {
    Fixture fixture = fixture();
    RepositoryRuntime runtime = runtime();
    AssetRecord existing = new AssetRecord(
        11L, runtime.id(), 3L, 2L, RepositoryFormat.HELM, "demo-1.0.0.tgz", null,
        "demo-1.0.0.tgz", "PACKAGE", "application/gzip", 4L,
        null, Instant.EPOCH, Map.of());
    when(fixture.assetDao.findAssetByPath(runtime.id(), "demo-1.0.0.tgz"))
        .thenReturn(Optional.of(existing));

    assertEquals(1, fixture.writer.deleteAsset(runtime, fixture.storage, "demo-1.0.0.tgz"));
    assertEquals(0, fixture.writer.deleteAsset(runtime, fixture.storage, "missing.tgz"));

    verify(fixture.browseNodeDao).deleteByAssetId(11L);
    verify(fixture.assetDao).deleteAssetById(11L);
    verify(fixture.assetDao).markBlobDeletedIfUnreferenced(2L, "asset unlinked");
    verify(fixture.componentDao).deleteIfNoAssets(3L);
    verify(fixture.cache).evictAfterCommit(runtime.id(), "demo-1.0.0.tgz");
    verify(fixture.storage, never()).delete(any());
  }

  private static void stubNewAsset(Fixture fixture, String path) {
    when(fixture.assetDao.findAssetByPath(10L, path)).thenReturn(Optional.empty());
    stubBlobPersistence(fixture);
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(3L);
    when(fixture.assetDao.tryInsertAsset(any())).thenReturn(OptionalLong.of(11L));
  }

  private static void stubBlobPersistence(Fixture fixture) {
    when(fixture.assetDao.findReusableBlobBySha256(eq(7L), anyString(), anyLong()))
        .thenReturn(Optional.empty());
    when(fixture.assetDao.recoverDeletedBlobBySha256(eq(7L), anyString(), anyLong()))
        .thenReturn(Optional.empty());
    when(fixture.storage.putFile(eq("helm"), anyString(), any(Path.class), anyString()))
        .thenAnswer(invocation -> new BlobReference(
            "bucket", invocation.getArgument(1), invocation.getArgument(3), 0));
    when(fixture.assetDao.insertBlobOrFindExisting(any()))
        .thenAnswer(invocation -> ((AssetBlobRecord) invocation.getArgument(0)).withId(2L));
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(3L);
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
        new HelmAssetWriter(assetDao, componentDao, browseNodeDao, cache, null));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        10L, "helm", RepositoryFormat.HELM, RepositoryType.HOSTED, "helm", true, 7L,
        "ALLOW", null, null, true, null, 60, 60, true, null, List.of());
  }

  private static byte[] chartPackage(String name, String version) throws Exception {
    ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(tarBytes)) {
      byte[] body = """
          apiVersion: v2
          name: %s
          version: %s
          """.formatted(name, version).getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry(name + "/Chart.yaml");
      entry.setSize(body.length);
      tar.putArchiveEntry(entry);
      tar.write(body);
      tar.closeArchiveEntry();
    }
    ByteArrayOutputStream gzipBytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBytes)) {
      gzip.write(tarBytes.toByteArray());
    }
    return gzipBytes.toByteArray();
  }

  private record Fixture(
      AssetDao assetDao,
      ComponentDao componentDao,
      BrowseNodeDao browseNodeDao,
      AssetMetadataCache cache,
      BlobStorage storage,
      HelmAssetWriter writer) {
  }
}
