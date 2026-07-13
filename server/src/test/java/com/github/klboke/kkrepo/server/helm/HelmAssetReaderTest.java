package com.github.klboke.kkrepo.server.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HelmAssetReaderTest {
  @Test
  void servesHeadWithoutOpeningStorage() {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    when(assetDao.findBlobById(2L)).thenReturn(Optional.of(blob()));

    MavenResponse response = new HelmAssetReader(
        assetDao, registry, mock(AssetMetadataCache.class))
        .serve(asset(), true, "demo-1.0.0.tgz");

    assertEquals(200, response.status());
    assertFalse(response.hasBody());
    assertEquals(4, response.contentLength());
    assertEquals("application/gzip", response.contentType());
    assertEquals("sha1", response.etag());
    verify(registry, never()).forBlobStoreId(7L);
  }

  @Test
  void lazilyStreamsSnapshotBody() throws Exception {
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    when(registry.forBlobStoreId(7L)).thenReturn(storage);
    when(storage.get(any(BlobReference.class)))
        .thenReturn(Optional.of(new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8))));
    HelmAssetReader reader = new HelmAssetReader(
        mock(AssetDao.class), registry, mock(AssetMetadataCache.class));

    MavenResponse response = reader.serveSnapshot(
        CachedAssetMetadata.of(asset(), blob()), false, "demo-1.0.0.tgz");

    assertTrue(response.hasBody());
    assertEquals("body", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void reportsMissingMetadataAndPhysicalBlob() {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    HelmAssetReader reader = new HelmAssetReader(
        assetDao, registry, mock(AssetMetadataCache.class));
    AssetRecord missingBinding = new AssetRecord(
        1L, 10L, null, null, RepositoryFormat.HELM, "missing.tgz", null,
        "missing.tgz", "PACKAGE", "application/gzip", 4L, null, Instant.EPOCH, Map.of());

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> reader.serve(missingBinding, false, "missing.tgz"));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> reader.serveSnapshot(
            CachedAssetMetadata.of(missingBinding, null), false, "missing.tgz"));

    when(assetDao.findBlobById(2L)).thenReturn(Optional.of(blob()));
    BlobStorage storage = mock(BlobStorage.class);
    when(registry.forBlobStoreId(7L)).thenReturn(storage);
    when(storage.get(any(BlobReference.class))).thenReturn(Optional.empty());
    MavenResponse response = reader.serve(asset(), false, "demo-1.0.0.tgz");
    assertThrows(MavenExceptions.MavenNotFoundException.class, response::body);
  }

  private static AssetRecord asset() {
    return new AssetRecord(
        1L, 10L, 3L, 2L, RepositoryFormat.HELM, "demo-1.0.0.tgz", null,
        "demo-1.0.0.tgz", "PACKAGE", "application/gzip", 4L, null,
        Instant.parse("2026-07-13T00:00:00Z"), Map.of("name", "demo", "version", "1.0.0"));
  }

  private static AssetBlobRecord blob() {
    return new AssetBlobRecord(
        2L, 7L, "blob://bucket/demo.tgz", null, "demo.tgz", null,
        "sha1", "sha256", "md5", 4, "application/gzip", "user", "127.0.0.1",
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }
}
