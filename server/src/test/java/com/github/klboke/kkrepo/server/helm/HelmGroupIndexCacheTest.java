package com.github.klboke.kkrepo.server.helm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class HelmGroupIndexCacheTest {
  @Test
  void memberIndexChangeInvalidatesEveryContainingGroupAcrossReplicas() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.putGroupsContaining(11L, List.of(group(21L, "nested")));
    repositories.putGroupsContaining(21L, List.of(group(31L, "root")));
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories,
        mock(AssetDao.class),
        mock(AssetMetadataCache.class),
        controller,
        true);
    String nestedBefore = controller.currentToken(21L, NexusCacheType.METADATA);
    String rootBefore = controller.currentToken(31L, NexusCacheType.METADATA);

    cache.invalidateMemberAfterCommit(11L);

    assertNotEquals(nestedBefore, controller.currentToken(21L, NexusCacheType.METADATA));
    assertNotEquals(rootBefore, controller.currentToken(31L, NexusCacheType.METADATA));
  }

  @Test
  void findsOnlyMarkedIndexesAtTheCurrentSharedWatermark() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    AssetMetadataCache metadata = mock(AssetMetadataCache.class);
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), metadata, controller, true);
    RepositoryRuntime group = runtime(21L, 60);
    NexusLikeCacheInfo info = cache.current(group, java.time.Instant.now());
    Map<String, Object> attributes = cache.freshAttributes(info);
    CachedAssetMetadata snapshot = snapshot("INDEX", attributes, true);
    when(metadata.find(eq(group.id()), eq("index.yaml"), any())).thenReturn(Optional.of(snapshot));

    assertTrue(cache.enabled());
    assertTrue(cache.findFresh(group, java.time.Instant.now()).isPresent());

    controller.invalidate(group.id(), NexusCacheType.METADATA);
    assertFalse(cache.findFresh(group, java.time.Instant.now()).isPresent());
  }

  @Test
  void rejectsMissingUnmarkedAndBloblessSnapshotsAndHonorsDisableSwitch() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    AssetMetadataCache metadata = mock(AssetMetadataCache.class);
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    RepositoryRuntime group = runtime(21L, 60);
    HelmGroupIndexCache disabled = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), metadata, controller, false);
    assertFalse(disabled.findFresh(group, java.time.Instant.now()).isPresent());
    disabled.invalidateMemberAfterCommit(11L);
    disabled.invalidateGroupAfterCommit(group.id());

    HelmGroupIndexCache enabled = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), metadata, controller, true);
    when(metadata.find(eq(group.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(snapshot("INDEX", Map.of(), true)))
        .thenReturn(Optional.of(snapshot("INDEX", null, true)))
        .thenReturn(Optional.of(snapshot("INDEX", Map.of("helmGroupIndex", true), false)));

    assertFalse(enabled.findFresh(group, java.time.Instant.now()).isPresent());
    assertFalse(enabled.findFresh(group, java.time.Instant.now()).isPresent());
    assertFalse(enabled.findFresh(group, java.time.Instant.now()).isPresent());
    assertFalse(enabled.findFresh(group, java.time.Instant.now()).isPresent());
  }

  @Test
  void groupInvalidationIsDeferredUntilCommitAndRecursesToParents() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.putGroupsContaining(21L, List.of(group(31L, "root")));
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), mock(AssetMetadataCache.class), controller, true);
    String nestedBefore = controller.currentToken(21L, NexusCacheType.METADATA);
    String rootBefore = controller.currentToken(31L, NexusCacheType.METADATA);

    TransactionSynchronizationManager.initSynchronization();
    try {
      cache.invalidateGroupAfterCommit(21L);
      cache.invalidateGroupAfterCommit(21L);
      assertTrue(nestedBefore.equals(controller.currentToken(21L, NexusCacheType.METADATA)));
      List<TransactionSynchronization> synchronizations =
          TransactionSynchronizationManager.getSynchronizations();
      assertTrue(synchronizations.size() == 1);
      synchronizations.getFirst().afterCommit();
      assertNotEquals(nestedBefore, controller.currentToken(21L, NexusCacheType.METADATA));
      assertNotEquals(rootBefore, controller.currentToken(31L, NexusCacheType.METADATA));
      synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void invalidationFailureDoesNotBreakCommittedRepositoryWrites() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.putGroupsContaining(11L, List.of(group(21L, "all")));
    NexusLikeCacheController controller = mock(NexusLikeCacheController.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("watermark unavailable"))
        .when(controller).invalidate(21L, NexusCacheType.METADATA);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), mock(AssetMetadataCache.class), controller, true);

    assertDoesNotThrow(() -> cache.invalidateMemberAfterCommit(11L));
  }

  private static CachedAssetMetadata snapshot(
      String kind, Map<String, Object> attributes, boolean withBlob) {
    CachedAssetMetadata.CachedBlob blob = withBlob
        ? new CachedAssetMetadata.CachedBlob(
            2L, 7L, "blob://bucket/index", "index", "sha1", "sha256", "md5", 4L,
            "text/x-yaml", "group", null, java.time.Instant.EPOCH,
            java.time.Instant.EPOCH, Map.of())
        : null;
    return new CachedAssetMetadata(
        1L, 21L, null, withBlob ? 2L : null, RepositoryFormat.HELM,
        "index.yaml", "index.yaml", kind, "text/x-yaml", 4L,
        java.time.Instant.now(), attributes, blob);
  }

  private static RepositoryRuntime runtime(long id, int metadataMaxAgeMinutes) {
    return new RepositoryRuntime(
        id, "helm-group", RepositoryFormat.HELM, RepositoryType.GROUP, "helm-group", true, 7L,
        null, null, null, true, null, 60, metadataMaxAgeMinutes, true, null, List.of());
  }

  private static RepositoryRecord group(long id, String name) {
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.HELM,
        RepositoryType.GROUP,
        "helm-group",
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of("recipe", "helm-group"));
  }

  private static class StubRepositoryDao extends RepositoryDaoAdapter {
    private final Map<Long, List<RepositoryRecord>> groupsByMember = new HashMap<>();

    StubRepositoryDao() {
      super(null, null);
    }

    void putGroupsContaining(long memberId, List<RepositoryRecord> groups) {
      groupsByMember.put(memberId, groups);
    }

    @Override
    public List<RepositoryRecord> listGroupsContaining(long memberRepositoryId) {
      return groupsByMember.getOrDefault(memberRepositoryId, List.of());
    }
  }
}
