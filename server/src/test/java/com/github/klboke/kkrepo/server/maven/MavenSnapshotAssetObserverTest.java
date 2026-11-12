package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.github.klboke.kkrepo.core.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class MavenSnapshotAssetObserverTest {
  final MavenSnapshotDao snapshots = mock(MavenSnapshotDao.class);
  final RepositoryDao repositories = mock(RepositoryDao.class);
  final MavenSnapshotAssetObserver observer = new MavenSnapshotAssetObserver(snapshots, repositories);

  @Test void metadataAndSubordinatesShareTheFamilyLockButOnlyBuildFilesEnterInventory() {
    var repo = mock(RepositoryRecord.class);
    when(repo.type()).thenReturn(RepositoryType.HOSTED);
    when(repositories.findById(1)).thenReturn(Optional.of(repo));
    when(snapshots.lockFamily(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(10L);
    String root = "com/example/demo/1-SNAPSHOT/";
    var metadata = asset(RepositoryFormat.MAVEN2, root + "maven-metadata.xml");
    assertTrue(observer.supports(metadata));
    observer.beforeMutation(metadata);
    observer.stored(metadata);
    verify(snapshots).lockFamily(1, root.substring(0, root.length() - 1), "com.example", "demo", "1-SNAPSHOT");
    verify(snapshots, never()).store(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), anyInt(), anyString(), anyBoolean(), any(), anyBoolean());
    var signature = asset(RepositoryFormat.MAVEN2, root + "demo-1-20260101.120000-9-sources.jar.asc.sha256");
    observer.beforeMutation(signature);
    observer.stored(signature);
    verify(snapshots).store(eq(10L), eq(1L), eq(1L), eq("1-20260101.120000-9"), anyLong(), eq(9), eq("7:sources:jar"), eq(false), eq(Instant.EPOCH), eq(false));
    observer.index(signature, true);
    verify(snapshots).store(eq(10L), eq(1L), eq(1L), anyString(), anyLong(), eq(9), anyString(), eq(false), eq(Instant.EPOCH), eq(true));
    observer.deleted(signature);
    verify(snapshots).remove(1);
  }
  @Test void excludesNonMavenReleasesInvalidPathsAndProxyCaches() {
    for (var asset : List.of(asset(RepositoryFormat.NPM, "demo/1-SNAPSHOT/foo.jar"),
        asset(RepositoryFormat.MAVEN2, "com/example/demo/1/demo-1.jar"),
        asset(RepositoryFormat.MAVEN2, null))) {
      assertFalse(observer.supports(asset));
      observer.beforeMutation(asset);
      observer.stored(asset);
    }
    observer.beforeMutation(asset(RepositoryFormat.MAVEN2, "a/1-SNAPSHOT/file"));
    var repo = mock(RepositoryRecord.class);
    when(repo.type()).thenReturn(RepositoryType.PROXY);
    when(repositories.findById(1)).thenReturn(Optional.of(repo));
    var proxy = asset(RepositoryFormat.MAVEN2, "com/example/demo/1-SNAPSHOT/demo-1-20260101.120000-1.jar");
    observer.beforeMutation(proxy); observer.stored(proxy);
    observer.deleted(asset(RepositoryFormat.NPM, "demo"));
    verifyNoInteractions(snapshots);
  }
  static AssetRecord asset(RepositoryFormat format, String path) {
    return new AssetRecord(1L, 1, null, null, format, path, new byte[32], "file", "artifact",
        null, 100L, null, Instant.EPOCH, Map.of());
  }
}
