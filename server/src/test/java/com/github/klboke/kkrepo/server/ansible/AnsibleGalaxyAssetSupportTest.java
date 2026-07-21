package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnsibleGalaxyAssetSupportTest {
  private static final String SHA256 = "a".repeat(64);

  private AssetDao assetDao;
  private BlobStorageRegistry storages;
  private RawHostedService hosted;
  private BlobStorage storage;
  private AnsibleGalaxyAssetSupport support;
  private RepositoryRuntime runtime;
  private AssetRecord asset;
  private AssetBlobRecord blob;

  @BeforeEach
  void setUp() {
    assetDao = mock(AssetDao.class);
    storages = mock(BlobStorageRegistry.class);
    hosted = mock(RawHostedService.class);
    storage = mock(BlobStorage.class);
    support = new AnsibleGalaxyAssetSupport(assetDao, storages, hosted);
    runtime = new RepositoryRuntime(
        7L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED,
        "ansiblegalaxy-hosted", true, 3L, "ALLOW_ONCE", null, null, true,
        null, 60, 60, java.util.List.of());
    Instant now = Instant.parse("2026-07-21T08:00:00Z");
    asset = new AssetRecord(
        11L, runtime.id(), 13L, 17L, RepositoryFormat.ANSIBLEGALAXY,
        "api/v3/artifacts/acme-tools-1.2.3.tar.gz", new byte[] {1},
        "acme-tools-1.2.3.tar.gz", "collection-artifact", "application/octet-stream",
        3L, null, now, Map.of());
    blob = new AssetBlobRecord(
        17L, 3L, "file:///blob", new byte[] {2}, "ansible/blob", new byte[] {3},
        null, SHA256, null, 3L, "application/octet-stream", "alice", "127.0.0.1",
        now, now, Map.of());
  }

  @Test
  void storesStagesFindsAndDeletesCollections(@TempDir Path temp) {
    ComponentRecord component = new ComponentRecord(
        null, runtime.id(), RepositoryFormat.ANSIBLEGALAXY, "acme", "tools", "1.2.3",
        "ansible-collection", new byte[] {4}, Map.of(), Instant.now());
    when(assetDao.findAssetByPath(runtime.id(), asset.path())).thenReturn(Optional.of(asset));
    when(hosted.putInternalWithComponentFileAtBrowsePathIfAbsent(
        eq(runtime), eq(asset.path()), any(), eq("application/octet-stream"),
        eq(Map.of("source", "test")), eq("alice"), eq("127.0.0.1"), eq(component),
        eq("acme/tools/1.2.3/acme-tools-1.2.3.tar.gz"))).thenReturn(true);

    AnsibleGalaxyAssetSupport.StoredCollection stored = support.storeCollection(
        runtime, asset.path(), temp.resolve("collection.tar.gz"), Map.of("source", "test"),
        "alice", "127.0.0.1", component);
    assertSame(asset, stored.asset());
    assertTrue(stored.created());
    verify(hosted).putInternalWithComponentFileAtBrowsePathIfAbsent(
        eq(runtime), eq(asset.path()), any(), eq("application/octet-stream"),
        eq(Map.of("source", "test")), eq("alice"), eq("127.0.0.1"), eq(component),
        eq("acme/tools/1.2.3/acme-tools-1.2.3.tar.gz"));

    String staging = ".ansible/staging/task-1/acme-tools-1.2.3.tar.gz";
    when(assetDao.findAssetByPath(runtime.id(), staging)).thenReturn(Optional.of(asset));
    assertSame(asset, support.stageCollection(
        runtime, "task-1", "acme-tools-1.2.3.tar.gz", temp.resolve("staged"),
        "alice", "127.0.0.1"));
    verify(hosted).putInternalUnindexedFile(
        eq(runtime), eq(staging), any(), eq("application/octet-stream"), eq(Map.of()),
        eq("alice"), eq("127.0.0.1"));

    assertSame(asset, support.find(runtime, staging).orElseThrow());
    support.delete(runtime, staging);
    verify(hosted).deleteInternal(runtime, staging);
  }

  @Test
  void reportsMissingStoredAndStagedAssets(@TempDir Path temp) {
    ComponentRecord component = new ComponentRecord(
        null, runtime.id(), RepositoryFormat.ANSIBLEGALAXY, "acme", "tools", "1.2.3",
        "ansible-collection", new byte[] {4}, Map.of(), Instant.now());
    assertThrows(IllegalStateException.class, () -> support.storeCollection(
        runtime, asset.path(), temp.resolve("missing"), Map.of(), "alice", null, component));
    assertThrows(IllegalStateException.class, () -> support.stageCollection(
        runtime, "task", asset.name(), temp.resolve("missing"), "alice", null));
  }

  @Test
  void servesHeadAndGetFromTheBoundBlob() throws Exception {
    when(assetDao.findAssetWithBlobById(asset.id()))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(asset, blob)));
    when(storages.forBlobStoreId(blob.blobStoreId())).thenReturn(storage);
    when(storage.stat(any())).thenReturn(Optional.of(mock(BlobObjectMetadata.class)));
    when(storage.get(any())).thenReturn(Optional.of(new ByteArrayInputStream(new byte[] {1, 2, 3})));

    var head = support.serve(runtime.id(), asset.id(), true);
    assertEquals(200, head.status());
    assertFalse(head.hasBody());
    assertEquals(3L, head.contentLength());
    assertEquals(SHA256, head.etag());

    var get = support.serve(runtime.id(), asset.id(), false);
    assertTrue(get.hasBody());
    try (InputStream input = get.body()) {
      assertArrayEquals(new byte[] {1, 2, 3}, input.readAllBytes());
    }
  }

  @Test
  void rejectsMissingAssetBlobObjectAndLateBodyMiss() {
    when(assetDao.findAssetWithBlobById(asset.id())).thenReturn(Optional.empty());
    assertThrows(AnsibleGalaxyExceptions.NotFound.class,
        () -> support.serve(runtime.id(), asset.id(), false));

    when(assetDao.findAssetWithBlobById(asset.id()))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(asset, null)));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class,
        () -> support.serve(runtime.id(), asset.id(), false));

    when(assetDao.findAssetWithBlobById(asset.id()))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(asset, blob)));
    when(storages.forBlobStoreId(blob.blobStoreId())).thenReturn(storage);
    when(storage.stat(any())).thenReturn(Optional.empty());
    assertThrows(AnsibleGalaxyExceptions.NotFound.class,
        () -> support.serve(runtime.id(), asset.id(), false));

    when(storage.stat(any())).thenReturn(Optional.of(mock(BlobObjectMetadata.class)));
    when(storage.get(any())).thenReturn(Optional.empty());
    var response = support.serve(runtime.id(), asset.id(), false);
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, response::body);
  }

  @Test
  void readsBytesAndOpenStreamsAndMapsIoFailures() throws Exception {
    when(assetDao.findAssetWithBlobById(asset.id()))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(asset, blob)));
    when(storages.forBlobStoreId(blob.blobStoreId())).thenReturn(storage);
    when(storage.get(any())).thenReturn(Optional.of(new ByteArrayInputStream(new byte[] {4, 5})));
    assertArrayEquals(new byte[] {4, 5}, support.bytes(runtime.id(), asset.id()));

    when(storage.get(any())).thenReturn(Optional.of(new ByteArrayInputStream(new byte[] {6})));
    try (InputStream input = support.open(runtime.id(), asset.id())) {
      assertEquals(6, input.read());
    }

    InputStream broken = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("broken");
      }
    };
    when(storage.get(any())).thenReturn(Optional.of(broken));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> support.bytes(runtime.id(), asset.id()));
  }

  @Test
  void rejectsWrongRepositoryAndMissingBlobAcrossReadHelpers() {
    AssetRecord wrongRepository = new AssetRecord(
        asset.id(), 99L, asset.componentId(), asset.assetBlobId(), asset.format(), asset.path(),
        asset.pathHash(), asset.name(), asset.kind(), asset.contentType(), asset.size(),
        asset.lastDownloadedAt(), asset.lastUpdatedAt(), asset.attributes());
    when(assetDao.findAssetWithBlobById(asset.id()))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(wrongRepository, blob)));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class,
        () -> support.bytes(runtime.id(), asset.id()));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class,
        () -> support.open(runtime.id(), asset.id()));

    when(assetDao.findAssetWithBlobById(asset.id()))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(asset, null)));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class,
        () -> support.bytes(runtime.id(), asset.id()));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class,
        () -> support.open(runtime.id(), asset.id()));

    when(assetDao.findAssetWithBlobById(asset.id()))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(asset, blob)));
    when(storages.forBlobStoreId(blob.blobStoreId())).thenReturn(storage);
    when(storage.get(any())).thenReturn(Optional.empty());
    assertThrows(AnsibleGalaxyExceptions.NotFound.class,
        () -> support.bytes(runtime.id(), asset.id()));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class,
        () -> support.open(runtime.id(), asset.id()));
  }

  @Test
  void requiresPersistedBlobMetadata() {
    AssetRecord withoutBinding = new AssetRecord(
        asset.id(), asset.repositoryId(), asset.componentId(), null, asset.format(), asset.path(),
        asset.pathHash(), asset.name(), asset.kind(), asset.contentType(), asset.size(),
        asset.lastDownloadedAt(), asset.lastUpdatedAt(), asset.attributes());
    assertThrows(AnsibleGalaxyExceptions.NotFound.class,
        () -> support.requiredBlob(withoutBinding));

    when(assetDao.findBlobById(blob.id())).thenReturn(Optional.empty(), Optional.of(blob));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> support.requiredBlob(asset));
    assertSame(blob, support.requiredBlob(asset));
  }
}
