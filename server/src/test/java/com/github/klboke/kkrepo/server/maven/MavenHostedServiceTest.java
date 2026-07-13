package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MavenHostedServiceTest {
  private static final MavenPathParser PARSER = new MavenPathParser();

  @Test
  void snapshotPutQueuesGaAndGavRebuildMarkers() {
    Fixture fixture = fixture(false);
    MavenPath path = path(
        "com/acme/demo/1.0-SNAPSHOT/demo-1.0-20260713.123456-1.jar");
    when(fixture.assetDao.findAssetByPath(10L, path.path())).thenReturn(Optional.empty());

    MavenResponse response = fixture.service.put(
        runtime(RepositoryType.HOSTED, "ALLOW", 7L),
        path,
        body("jar"),
        "application/java-archive",
        "alice",
        "127.0.0.1",
        true);

    assertEquals(201, response.status());
    verify(fixture.rebuildDao).enqueue(10L, MetadataRebuildScope.ga("com.acme", "demo"));
    verify(fixture.rebuildDao).enqueue(
        10L, MetadataRebuildScope.gav("com.acme", "demo", "1.0-SNAPSHOT"));
  }

  @Test
  void releasePutQueuesOnlyGaAndHashPutQueuesNothing() {
    Fixture fixture = fixture(false);
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "ALLOW", 7L);
    MavenPath release = path("com/acme/demo/1.0/demo-1.0.jar");
    when(fixture.assetDao.findAssetByPath(10L, release.path())).thenReturn(Optional.empty());

    fixture.service.put(
        runtime, release, body("jar"), "application/java-archive", "alice", null, true);

    verify(fixture.rebuildDao).enqueue(10L, MetadataRebuildScope.ga("com.acme", "demo"));
    verify(fixture.rebuildDao, never())
        .enqueue(10L, MetadataRebuildScope.gav("com.acme", "demo", "1.0"));

    Fixture checksumFixture = fixture(false);
    MavenPath checksum = path("com/acme/demo/1.0/demo-1.0.jar.sha1");
    when(checksumFixture.assetDao.findAssetByPath(10L, checksum.path())).thenReturn(Optional.empty());
    checksumFixture.service.put(
        runtime, checksum, body("sha1"), "text/plain", "alice", null, true);
    verify(checksumFixture.rebuildDao, never()).enqueue(anyLong(), any());
  }

  @Test
  void explicitMetadataUploadCancelsPendingScopeMarker() {
    Fixture fixture = fixture(false);
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "ALLOW", 7L);
    MavenPath gaMetadata = path("com/acme/demo/maven-metadata.xml");
    MavenPath gavMetadata = path("com/acme/demo/1.0-SNAPSHOT/maven-metadata.xml");
    when(fixture.assetDao.findAssetByPath(eq(10L), any())).thenReturn(Optional.empty());

    fixture.service.put(runtime, gaMetadata, body("<metadata/>"), "application/xml", "alice", null);
    fixture.service.put(runtime, gavMetadata, body("<metadata/>"), "application/xml", "alice", null);

    verify(fixture.rebuildDao).delete(10L, MetadataRebuildScope.ga("com.acme", "demo"));
    verify(fixture.rebuildDao).delete(
        10L, MetadataRebuildScope.gav("com.acme", "demo", "1.0-SNAPSHOT"));
  }

  @Test
  void synchronousSnapshotPutRebuildsMetadataInlineWithoutQueueing() {
    Fixture fixture = fixture(true);
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "ALLOW", 7L);
    MavenPath path = path(
        "com/acme/demo/1.0-SNAPSHOT/demo-1.0-20260713.123456-1.pom");
    when(fixture.assetDao.findAssetByPath(10L, path.path())).thenReturn(Optional.empty());

    fixture.service.put(runtime, path, body("pom"), "application/xml", "alice", null, true);

    verify(fixture.metadata).rebuildGa(
        runtime, fixture.storage, 7L, "com.acme", "demo", "alice", null);
    verify(fixture.metadata).rebuildBaseVersionIfSnapshot(
        eq(runtime), eq(fixture.storage), eq(7L), any(), eq("alice"), eq(null));
    verify(fixture.rebuildDao, never()).enqueue(anyLong(), any());
  }

  @Test
  void writePolicyAndRepositoryTypeAreEnforcedBeforeStorageWrite() {
    Fixture fixture = fixture(false);
    MavenPath path = path("com/acme/demo/1.0/demo-1.0.jar");
    when(fixture.assetDao.findAssetByPath(10L, path.path()))
        .thenReturn(Optional.of(mock(AssetRecord.class)));

    assertThrows(MavenExceptions.WritePolicyDenied.class, () ->
        fixture.service.put(
            runtime(RepositoryType.HOSTED, "ALLOW_ONCE", 7L),
            path,
            body("jar"),
            "application/java-archive",
            "alice",
            null));
    assertThrows(MavenExceptions.MethodNotAllowed.class, () ->
        fixture.service.put(
            runtime(RepositoryType.PROXY, "ALLOW", 7L),
            path,
            body("jar"),
            "application/java-archive",
            "alice",
            null));

    verify(fixture.writer, never()).writePrimary(
        any(), any(), anyLong(), any(), any(), any(), any(), any(), anyMap(), anyBoolean());
  }

  @Test
  void deleteReturnsNotFoundWithoutQueueingAndSuccessfulDeleteQueuesMetadata() {
    Fixture missing = fixture(false);
    RepositoryRuntime runtime = runtime(RepositoryType.HOSTED, "ALLOW", 7L);
    MavenPath path = path("com/acme/demo/1.0/demo-1.0.jar");
    when(missing.writer.deleteAsset(runtime, missing.storage, path)).thenReturn(0);

    assertEquals(404, missing.service.delete(runtime, path).status());
    verify(missing.rebuildDao, never()).enqueue(anyLong(), any());

    Fixture deleted = fixture(false);
    when(deleted.writer.deleteAsset(runtime, deleted.storage, path)).thenReturn(1);
    assertEquals(204, deleted.service.delete(runtime, path).status());
    verify(deleted.rebuildDao).enqueue(10L, MetadataRebuildScope.ga("com.acme", "demo"));
  }

  private static Fixture fixture(boolean synchronousMetadata) {
    AssetDao assetDao = mock(AssetDao.class);
    BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    MavenAssetWriter writer = mock(MavenAssetWriter.class);
    MavenMetadataService metadata = mock(MavenMetadataService.class);
    MetadataRebuildDao rebuildDao = mock(MetadataRebuildDao.class);
    when(storages.forBlobStoreId(7L)).thenReturn(storage);
    MavenHostedService service = new MavenHostedService(
        assetDao,
        storages,
        writer,
        metadata,
        rebuildDao,
        mock(AssetMetadataCache.class),
        synchronousMetadata);
    return new Fixture(assetDao, storage, writer, metadata, rebuildDao, service);
  }

  private static ByteArrayInputStream body(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }

  private static MavenPath path(String value) {
    return PARSER.parsePath(value);
  }

  private static RepositoryRuntime runtime(
      RepositoryType type,
      String writePolicy,
      Long blobStoreId) {
    return new RepositoryRuntime(
        10L,
        "maven-hosted",
        RepositoryFormat.MAVEN2,
        type,
        "maven2-" + type.name().toLowerCase(),
        true,
        blobStoreId,
        writePolicy,
        "MIXED",
        "PERMISSIVE",
        true,
        type == RepositoryType.PROXY ? "https://repo.example/" : null,
        null,
        null,
        true,
        null,
        List.of());
  }

  private record Fixture(
      AssetDao assetDao,
      BlobStorage storage,
      MavenAssetWriter writer,
      MavenMetadataService metadata,
      MetadataRebuildDao rebuildDao,
      MavenHostedService service) {
  }
}
