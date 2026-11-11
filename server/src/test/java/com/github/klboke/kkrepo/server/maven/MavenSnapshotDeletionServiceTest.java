package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.MavenSnapshotDao.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MavenSnapshotDeletionServiceTest {
  final MavenSnapshotDao snapshots = mock(MavenSnapshotDao.class);
  final AssetDao assets = mock(AssetDao.class);
  final BrowseNodeDao browse = mock(BrowseNodeDao.class);
  final ComponentDao components = mock(ComponentDao.class);
  final AssetMetadataCache cache = mock(AssetMetadataCache.class);
  final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  final MavenGroupMetadataInvalidator groups = mock(MavenGroupMetadataInvalidator.class);
  final RepositoryRuntime runtime = mock(RepositoryRuntime.class);
  final MavenSnapshotDeletionService service = new MavenSnapshotDeletionService(snapshots, assets, browse, components, cache, runtimes, groups);

  @BeforeEach void fixture() {
    when(runtime.id()).thenReturn(1L);
    when(runtimes.resolve("snapshots")).thenReturn(Optional.of(runtime));
    when(snapshots.findBuild(1)).thenReturn(Optional.of(new Build(1, 1, 1, "1-20260101.120000-1", 1, 1, 1, 2, 1, Instant.EPOCH)));
    when(snapshots.findFamily(1)).thenReturn(Optional.of(new Family(1, 1, "com/example/demo/1-SNAPSHOT", "com.example", "demo", "1-SNAPSHOT")));
    when(snapshots.assetIds(1, 4097)).thenReturn(List.of(1L, 2L));
    when(assets.findAssetsByIdsForUpdate(List.of(1L, 2L))).thenReturn(Map.of(1L, asset(1, ".jar.sha1"), 2L, asset(2, ".jar")));
    when(snapshots.tryLockBlob(anyLong())).thenReturn(true);
    when(snapshots.tryLockComponent(anyLong())).thenReturn(true);
  }

  @Test void deletesWholeBuildAndInvalidatesGroupsEvenIfChecksumHasLowestAssetId() {
    assertEquals(2, service.delete("snapshots", 1));
    var order = inOrder(snapshots, assets, browse, cache, components);
    order.verify(snapshots).lockFamily(1, "com/example/demo/1-SNAPSHOT", "com.example", "demo", "1-SNAPSHOT");
    order.verify(assets).findAssetsByIdsForUpdate(List.of(1L, 2L));
    order.verify(snapshots).tryLockBlob(99);
    order.verify(assets).hasComponentAssetsOutside(7L, List.of(1L, 2L));
    order.verify(snapshots).tryLockComponent(7);
    order.verify(browse).deleteByAssetId(1);
    order.verify(assets).deleteAssetById(1);
    order.verify(cache).evictAfterCommit(eq(1L), endsWith(".sha1"));
    verify(assets).deleteAssetById(2);
    verify(assets).markBlobDeletedIfUnreferenced(99, "snapshot build cleanup");
    verify(components).deleteIfNoAssets(7);
    verify(groups).memberAssetDeleted(eq(runtime), argThat(path -> !path.isSubordinate() && path.path().endsWith(".jar")));
  }
  @Test void busySharedBlobOrComponentCausesRetryBeforeAnyDelete() {
    when(snapshots.tryLockBlob(99)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.delete("snapshots", 1));
    verifyNoInteractions(browse, cache, components);
    when(snapshots.tryLockBlob(99)).thenReturn(true);
    when(snapshots.tryLockComponent(7)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.delete("snapshots", 1));
    verify(assets, never()).deleteAssetById(anyLong());
  }
  @Test void doesNotLockOrPruneSharedBaseVersionComponent() {
    when(assets.hasComponentAssetsOutside(7L, List.of(1L, 2L))).thenReturn(true);
    assertEquals(2, service.delete("snapshots", 1));
    verify(snapshots, never()).tryLockComponent(anyLong());
    verifyNoInteractions(components);
  }
  @Test void refusesWrongRepositoryOversizedBuildAndTreatsMissingBuildAsIdempotent() {
    when(runtime.id()).thenReturn(2L);
    assertThrows(IllegalArgumentException.class, () -> service.delete("snapshots", 1));
    when(runtime.id()).thenReturn(1L);
    when(snapshots.assetIds(1, 4097)).thenReturn(Collections.nCopies(4097, 1L));
    assertThrows(IllegalStateException.class, () -> service.delete("snapshots", 1));
    when(snapshots.findBuild(1)).thenReturn(Optional.empty());
    assertEquals(0, service.delete("snapshots", 1));
    verifyNoInteractions(browse, cache, components, groups);
  }
  private AssetRecord asset(long id, String suffix) {
    String name = "demo-1-20260101.120000-1" + suffix;
    return new AssetRecord(id, 1, 7L, 99L, RepositoryFormat.MAVEN2, "com/example/demo/1-SNAPSHOT/" + name,
        new byte[32], name, "artifact", "application/octet-stream", 100L, null, Instant.EPOCH, Map.of());
  }
}
