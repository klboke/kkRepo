package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RHostedBrowseIndexesTest {
  private static final String LINUX = "bin/linux/resolute-x86_64/4.6/src/contrib";
  private final RRegistryDao registry = mock(RRegistryDao.class);
  private final AssetDao assets = mock(AssetDao.class);
  private final RHostedBrowseIndexes indexes = new RHostedBrowseIndexes(registry, assets);
  private final RepositoryRecord hosted = repository(RepositoryType.HOSTED);

  @ParameterizedTest
  @ValueSource(strings = {"src/contrib", LINUX, "bin/windows/contrib/4.6", "bin/macosx/big-sur-arm64/contrib/4.6"})
  void listsTheActiveSnapshotAtItsCanonicalLocation(String namespace) {
    String path = namespace + "/PACKAGES.gz";
    String hidden = ".r/snapshots/hash/7/PACKAGES.gz";
    when(registry.findPublishedSnapshot(1, namespace)).thenReturn(Optional.of(
        new RRegistryDao.Snapshot(1, namespace, 7, 1, Map.of(path, hidden), "hash", Instant.EPOCH)));
    when(assets.findAssetByPath(1, hidden)).thenReturn(Optional.of(new AssetRecord(
        11L, 1, null, 12L, RepositoryFormat.R, hidden, null, "PACKAGES.gz", "r-index",
        "application/x-gzip", 42L, null, Instant.EPOCH, Map.of())));

    var entry = indexes.children(hosted, namespace, ignored -> false).getFirst();
    assertEquals(path, entry.path());
    assertEquals("/repository/r/" + path, entry.downloadUrl());
    assertEquals(42L, entry.size());
    assertTrue(entry.leaf());
    assertTrue(entry.readOnly());
    assertEquals(Optional.of(namespace), RHostedBrowseIndexes.indexNamespace(path));
    assertTrue(RHostedBrowseIndexes.indexNamespace(namespace + "/PACKAGES").isEmpty());
    verify(registry).findPublishedSnapshot(1, namespace);
    verify(assets).findAssetByPath(1, hidden);
    verifyNoMoreInteractions(registry, assets);
  }

  @Test
  void mergesOnlyPublishedAncestorsAndKeepsEmptyIndexesReachable() {
    when(registry.listSuites(1)).thenReturn(List.of(
        suite("src/contrib", true), suite(LINUX, true), suite("bin/windows/contrib/4.6", true),
        suite("bin/macosx/big-sur-arm64/contrib/4.6", false)));
    assertEquals(List.of("bin"), indexes.children(hosted, "", path -> path.equals("src")).stream()
        .map(BrowseController.BrowseEntry::path).toList());
    assertEquals(List.of("linux", "windows"), indexes.children(hosted, "bin", path -> false).stream()
        .map(BrowseController.BrowseEntry::name).toList());
    String version = "bin/linux/resolute-x86_64/4.6";
    var src = indexes.children(hosted, version, ignored -> false).getFirst();
    assertFalse(src.leaf());
    assertEquals(version + "/src", src.path());
    assertTrue(RHostedBrowseIndexes.indexNamespace(version + "/PACKAGES.gz").isEmpty());
    assertTrue(indexes.children(hosted, "src/contrib", ignored -> false).isEmpty());
    verifyNoInteractions(assets);
  }

  @Test
  void browseDoesNotInventIndexesForOtherRepositoryTypesOrMissingManifestEntries() {
    for (var type : List.of(RepositoryType.GROUP, RepositoryType.PROXY)) {
      assertTrue(indexes.children(repository(type), "src/contrib", ignored -> false).isEmpty());
    }
    assertTrue(indexes.children(hosted, LINUX + "/demo/1.0", ignored -> false).isEmpty());
    verifyNoInteractions(registry, assets);
    when(registry.findPublishedSnapshot(1, LINUX)).thenReturn(Optional.of(
        new RRegistryDao.Snapshot(1, LINUX, 2, 1, Map.of(), "hash", Instant.EPOCH)));
    assertTrue(indexes.children(hosted, LINUX, ignored -> false).isEmpty());
    verifyNoInteractions(assets);
  }

  private static RRegistryDao.SuiteState suite(String namespace, boolean published) {
    return new RRegistryDao.SuiteState(1, namespace, 3, Instant.EPOCH, published ? 2 : 0,
        1, published ? Instant.EPOCH : null, null, null, Instant.EPOCH);
  }

  private static RepositoryRecord repository(RepositoryType type) {
    return new RepositoryRecord(1L, "r", RepositoryFormat.R, type, "r-hosted", true,
        1L, null, null, "RELEASE", "STRICT", "ALLOW", true, Map.of());
  }
}
