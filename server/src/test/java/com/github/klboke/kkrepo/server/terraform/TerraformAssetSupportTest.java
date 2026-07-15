package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TerraformAssetSupportTest {
  private AssetDao assetDao;
  private BlobStorageRegistry storages;
  private RawHostedService hosted;
  private BlobStorage storage;
  private TerraformAssetSupport support;
  private RepositoryRuntime runtime;
  private AssetRecord asset;
  private AssetBlobRecord blob;

  @BeforeEach
  void setUp() {
    assetDao = mock(AssetDao.class);
    storages = mock(BlobStorageRegistry.class);
    hosted = mock(RawHostedService.class);
    storage = mock(BlobStorage.class);
    support = new TerraformAssetSupport(assetDao, storages, hosted);
    runtime = new RepositoryRuntime(
        1, "terraform", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED,
        "terraform-hosted", true, 1L, "ALLOW_ONCE", null, null, true,
        null, null, null, List.of());
    asset = new AssetRecord(
        2L, runtime.id(), null, 3L, RepositoryFormat.TERRAFORM, "v1/file.zip",
        new byte[32], "file.zip", "terraform", "application/zip", 7L,
        null, Instant.parse("2026-07-14T00:00:00Z"), Map.of());
    blob = new AssetBlobRecord(
        3L, 4L, "bucket/object", new byte[32], "object", new byte[32],
        "sha1", "sha256", "md5", 7, "application/zip", "alice", null,
        Instant.now(), Instant.now(), Map.of());
  }

  @Test
  void delegatesStoresAndDatabaseQueries() {
    support.store(runtime, "v1/file.zip", new ByteArrayInputStream(new byte[0]),
        "application/zip", Map.of("kind", "archive"), "alice", "127.0.0.1");
    support.storeBytes(runtime, "v1/metadata.json", "{}".getBytes(StandardCharsets.UTF_8),
        "application/json", Map.of("kind", "metadata"));
    verify(hosted).putInternal(eq(runtime), eq("v1/file.zip"), any(),
        eq("application/zip"), any(), eq("alice"), eq("127.0.0.1"));
    verify(hosted).putInternal(eq(runtime), eq("v1/metadata.json"), any(),
        eq("application/json"), any(), eq("terraform"), eq(null));
    support.delete(runtime, "v1/file.zip");
    verify(hosted).deleteInternal(runtime, "v1/file.zip");

    when(assetDao.findAssetByPath(runtime.id(), asset.path())).thenReturn(Optional.of(asset));
    when(assetDao.listAssetsByPrefix(runtime.id(), "v1/")).thenReturn(List.of(asset));
    when(assetDao.findBlobById(3L)).thenReturn(Optional.of(blob));
    assertEquals(Optional.of(asset), support.find(runtime, asset.path()));
    assertEquals(List.of(asset), support.list(runtime, "v1/"));
    assertEquals(blob, support.blob(asset));
    assertEquals(null, support.blob(new AssetRecord(
        5L, runtime.id(), null, null, RepositoryFormat.TERRAFORM, "missing", new byte[0],
        "missing", "terraform", null, null, null, null, Map.of())));
  }

  @Test
  void readsAndServesAssetsThroughConfiguredBlobStorage() throws Exception {
    byte[] content = "archive".getBytes(StandardCharsets.UTF_8);
    when(assetDao.findAssetByPath(runtime.id(), asset.path())).thenReturn(Optional.of(asset));
    when(assetDao.findBlobById(3L)).thenReturn(Optional.of(blob));
    when(storages.forBlobStoreId(blob.blobStoreId())).thenReturn(storage);
    when(storage.get(any())).thenAnswer(invocation ->
        Optional.of(new ByteArrayInputStream(content)));
    when(storage.stat(any())).thenAnswer(invocation -> {
      BlobReference reference = invocation.getArgument(0);
      return Optional.of(new BlobObjectMetadata(reference, "etag", "application/zip", Instant.now()));
    });

    assertArrayEquals(content, support.bytes(runtime, asset.path()));
    MavenResponse head = support.serve(runtime, asset.path(), true);
    assertEquals(200, head.status());
    assertEquals(content.length, head.contentLength());
    assertFalse(head.hasBody());
    MavenResponse get = support.serve(runtime, asset.path(), false);
    try (var body = get.body()) {
      assertArrayEquals(content, body.readAllBytes());
    }
  }

  @Test
  void treatsMissingDatabaseOrBlobStorageStateAsNotFound() {
    when(assetDao.findAssetByPath(runtime.id(), "missing")).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> support.bytes(runtime, "missing"));

    when(assetDao.findAssetByPath(runtime.id(), asset.path())).thenReturn(Optional.of(asset));
    when(assetDao.findBlobById(3L)).thenReturn(Optional.of(blob));
    when(storages.forBlobStoreId(blob.blobStoreId())).thenReturn(storage);
    when(storage.stat(any())).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> support.serve(runtime, asset.path(), false));
  }
}
