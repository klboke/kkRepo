package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SwiftComponentServiceTest {

  @Test
  void fencedPublishChecksPermanentTombstoneInsideTheCommitTransaction() {
    ComponentDao components = mock(ComponentDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    SwiftPublishLeaseManager.Lease lease = mock(SwiftPublishLeaseManager.Lease.class);
    SwiftComponentService service = new SwiftComponentService(components, assets, browse, registry);
    RepositoryRuntime runtime = runtime();
    SwiftComponentService.Publication publication = new SwiftComponentService.Publication(
        "acme", "Acme", "library", "Library", "1.2.3", Instant.EPOCH, "{}",
        "a".repeat(64), 10L, null, null, null, "HOSTED", List.of(), List.of());
    when(registry.findTombstone(runtime.id(), "acme", "library", "1.2.3"))
        .thenReturn(Optional.of(new SwiftRegistryDao.Tombstone(
            runtime.id(), "acme", "library", "1.2.3", "deleted", 2L, Instant.EPOCH)));

    assertThrows(
        SwiftExceptions.Conflict.class,
        () -> service.publishFenced(runtime, publication, lease));

    verify(lease).assertHeld();
    verify(components, never()).upsertReturningId(any());
  }

  @Test
  void publishAtomicallyPromotesStagedAssetsToImmutablePublicPaths() {
    ComponentDao components = mock(ComponentDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    SwiftPublishLeaseManager.Lease lease = mock(SwiftPublishLeaseManager.Lease.class);
    SwiftComponentService service = new SwiftComponentService(components, assets, browse, registry);
    RepositoryRuntime runtime = runtime();
    AssetRecord stagedArchive = stagedAsset(
        10L, 110L, ".swift/staging/a/source.zip", "acme/library/1.2.3.zip");
    AssetRecord stagedManifest = stagedAsset(
        11L, 111L, ".swift/staging/b/Package.swift",
        "acme/library/1.2.3/Package.swift");
    when(components.upsertReturningId(any())).thenReturn(20L);
    when(assets.findAssetById(10L)).thenReturn(Optional.of(stagedArchive));
    when(assets.findAssetById(11L)).thenReturn(Optional.of(stagedManifest));
    when(assets.tryInsertAsset(any()))
        .thenReturn(OptionalLong.of(30L), OptionalLong.of(31L));
    when(assets.deleteAssetById(10L)).thenReturn(1);
    when(assets.deleteAssetById(11L)).thenReturn(1);
    when(registry.nextRepositoryRevision(runtime.id())).thenReturn(7L);
    when(registry.insertRelease(any(), any(), any())).thenAnswer(invocation -> {
      SwiftRegistryDao.Release candidate = invocation.getArgument(0);
      return new SwiftRegistryDao.Release(
          50L, candidate.repositoryId(), candidate.componentId(), candidate.scopeLc(),
          candidate.scopeDisplay(), candidate.nameLc(), candidate.nameDisplay(),
          candidate.version(), candidate.publishedAt(), candidate.metadataJson(),
          candidate.archiveSha256(), candidate.archiveAssetId(), candidate.signatureFormat(),
          candidate.sourceSignatureAssetId(), candidate.metadataSignatureAssetId(),
          candidate.sourceKind(), candidate.revision(), candidate.status(),
          candidate.createdAt(), candidate.updatedAt());
    });

    SwiftRegistryDao.Release release = service.publishFenced(
        runtime, new SwiftComponentService.Publication(
        "acme", "Acme", "library", "Library", "1.2.3", Instant.EPOCH, "{}",
        "a".repeat(64), 10L, null, null, null, "HOSTED",
        List.of(new SwiftComponentService.ManifestDraft(
            "Package.swift", "", 11L, "b".repeat(64))),
        List.of()), lease);

    assertEquals(30L, release.archiveAssetId());
    ArgumentCaptor<List<SwiftRegistryDao.Manifest>> manifests = ArgumentCaptor.forClass(List.class);
    verify(registry).insertRelease(any(), manifests.capture(), any());
    assertEquals(31L, manifests.getValue().getFirst().assetId());
    ArgumentCaptor<AssetRecord> promoted = ArgumentCaptor.forClass(AssetRecord.class);
    verify(assets, times(2)).tryInsertAsset(promoted.capture());
    assertEquals("acme/library/1.2.3.zip", promoted.getAllValues().get(0).path());
    assertEquals("acme/library/1.2.3/Package.swift", promoted.getAllValues().get(1).path());
    assertFalse(promoted.getAllValues().get(0).attributes().containsKey("swiftLogicalPath"));
    verify(browse).deleteByAssetId(10L);
    verify(browse).deleteByAssetId(11L);
    verify(lease).assertHeld();
  }

  @Test
  void rebuildRepairsAllReleaseBindingsWithoutDeletingReleaseOwningComponents() {
    ComponentDao components = mock(ComponentDao.class);
    AssetDao assets = mock(AssetDao.class);
    BrowseNodeDao browse = mock(BrowseNodeDao.class);
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    SwiftComponentService service = new SwiftComponentService(components, assets, browse, registry);
    RepositoryRuntime runtime = runtime();
    AssetRecord archive = asset(10L, "Acme/Library/1.2.3.zip");
    AssetRecord manifest = asset(11L, "acme/library/1.2.3/Package.swift");
    AssetRecord sourceSignature = asset(12L, ".swift/signatures/acme/library/1.2.3/source.cms");
    AssetRecord metadataSignature = asset(13L, ".swift/signatures/acme/library/1.2.3/metadata.sig");
    SwiftRegistryDao.Release release = new SwiftRegistryDao.Release(
        50L,
        runtime.id(),
        20L,
        "acme",
        "Acme",
        "library",
        "Library",
        "1.2.3",
        Instant.EPOCH,
        "{}",
        "a".repeat(64),
        archive.id(),
        "cms-1.0.0",
        sourceSignature.id(),
        metadataSignature.id(),
        "HOSTED",
        3L,
        SwiftRegistryDao.RELEASE_READY,
        Instant.EPOCH,
        Instant.EPOCH);
    when(assets.listAssetsByPrefix(runtime.id(), "")).thenReturn(List.of(
        archive, manifest, sourceSignature, metadataSignature));
    when(registry.findRelease(runtime.id(), "acme", "library", "1.2.3"))
        .thenReturn(Optional.of(release));
    when(registry.listManifests(release.id())).thenReturn(List.of(
        new SwiftRegistryDao.Manifest(
            release.id(), "Package.swift", "", manifest.id(), "b".repeat(64))));
    for (AssetRecord asset : List.of(archive, manifest, sourceSignature, metadataSignature)) {
      when(assets.findAssetById(asset.id())).thenReturn(Optional.of(asset));
    }

    service.rebuild(runtime);
    service.rebuild(runtime);

    verify(components, never()).deleteByRepositoryIdAndFormat(runtime.id(), RepositoryFormat.SWIFT);
    for (AssetRecord asset : List.of(archive, manifest, sourceSignature, metadataSignature)) {
      verify(assets, times(2)).updateAssetComponentBinding(asset.id(), release.componentId());
      verify(browse, times(2)).upsertPathAncestors(
          runtime.id(), asset.path(), asset.id(), release.componentId());
    }
  }

  private static AssetRecord asset(long id, String path) {
    return new AssetRecord(
        id,
        1L,
        null,
        100L + id,
        RepositoryFormat.SWIFT,
        path,
        PersistenceHashes.pathHash(path),
        path.substring(path.lastIndexOf('/') + 1),
        "swift-asset",
        "application/octet-stream",
        1L,
        null,
        Instant.EPOCH,
        Map.of());
  }

  private static AssetRecord stagedAsset(long id, long blobId, String path, String logicalPath) {
    return new AssetRecord(
        id,
        1L,
        null,
        blobId,
        RepositoryFormat.SWIFT,
        path,
        PersistenceHashes.pathHash(path),
        path.substring(path.lastIndexOf('/') + 1),
        "swift",
        "application/octet-stream",
        1L,
        null,
        Instant.EPOCH,
        Map.of("swiftLogicalPath", logicalPath));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L,
        "swift-hosted",
        RepositoryFormat.SWIFT,
        RepositoryType.HOSTED,
        "swift-hosted",
        true,
        7L,
        "ALLOW",
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        null,
        List.of());
  }
}
