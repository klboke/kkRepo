package com.github.klboke.kkrepo.server.raw;

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

class RawAssetReaderTest {
  @Test
  void servesHeadWithoutOpeningBlobStorage() {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    AssetBlobRecord blob = blob();
    when(assetDao.findBlobById(2L)).thenReturn(Optional.of(blob));

    MavenResponse response = new RawAssetReader(assetDao, registry)
        .serve(asset(), true, "docs/readme.txt", "ATTACHMENT");

    assertEquals(200, response.status());
    assertFalse(response.hasBody());
    assertEquals(4, response.contentLength());
    assertEquals("text/plain", response.contentType());
    assertEquals("sha1", response.etag());
    verify(registry, never()).forBlobStoreId(any(Long.class));
  }

  @Test
  void lazilyStreamsBlobAndNormalizesDisposition() throws Exception {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    when(registry.forBlobStoreId(7L)).thenReturn(storage);
    when(storage.get(any(BlobReference.class)))
        .thenReturn(Optional.of(new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8))));

    MavenResponse response = new RawAssetReader(assetDao, registry)
        .serveSnapshot(CachedAssetMetadata.of(asset(), blob()), false, "docs/readme.txt", "INLINE");

    assertTrue(response.hasBody());
    assertEquals("body", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    assertEquals("inline", response.headers().get("Content-Disposition"));
  }

  @Test
  void reportsMissingMetadataAndPhysicalObjects() {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    RawAssetReader reader = new RawAssetReader(assetDao, registry);

    AssetRecord missingBinding = new AssetRecord(
        1L, 10L, null, null, RepositoryFormat.RAW, "missing.txt", null,
        "missing.txt", "raw", "text/plain", 4L, null, Instant.EPOCH, Map.of());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> reader.serve(missingBinding, false, "missing.txt", null));

    when(assetDao.findBlobById(2L)).thenReturn(Optional.of(blob()));
    BlobStorage storage = mock(BlobStorage.class);
    when(registry.forBlobStoreId(7L)).thenReturn(storage);
    when(storage.get(any(BlobReference.class))).thenReturn(Optional.empty());
    MavenResponse response = reader.serve(asset(), false, "docs/readme.txt", null);
    assertThrows(MavenExceptions.MavenNotFoundException.class, response::body);
  }

  private static AssetRecord asset() {
    return new AssetRecord(
        1L, 10L, null, 2L, RepositoryFormat.RAW, "docs/readme.txt", null,
        "readme.txt", "raw", "text/plain", 4L, null,
        Instant.parse("2026-07-13T00:00:00Z"), Map.of());
  }

  private static AssetBlobRecord blob() {
    return new AssetBlobRecord(
        2L, 7L, "blob://bucket/object-key", null, "object-key", null,
        "sha1", "sha256", "md5", 4, "text/plain", "user", "127.0.0.1",
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }
}
