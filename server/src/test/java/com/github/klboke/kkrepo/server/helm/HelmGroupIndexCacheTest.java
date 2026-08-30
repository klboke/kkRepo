package com.github.klboke.kkrepo.server.helm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
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
    RepositoryIndexRebuildDao rebuildQueue = mock(RepositoryIndexRebuildDao.class);
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories,
        mock(AssetDao.class),
        mock(AssetMetadataCache.class),
        controller,
        rebuildQueue,
        true);
    String nestedBefore = controller.currentToken(21L, NexusCacheType.METADATA);
    String rootBefore = controller.currentToken(31L, NexusCacheType.METADATA);

    cache.invalidateMemberAfterCommit(11L);

    assertNotEquals(nestedBefore, controller.currentToken(21L, NexusCacheType.METADATA));
    assertNotEquals(rootBefore, controller.currentToken(31L, NexusCacheType.METADATA));
    verify(rebuildQueue).enqueue(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
        RepositoryIndexRebuildDao.ROOT_SCOPE);
    verify(rebuildQueue).enqueue(
        31L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
        RepositoryIndexRebuildDao.ROOT_SCOPE);

    String beforeRetry = controller.currentToken(21L, NexusCacheType.METADATA);
    cache.retryInvalidation(21L);
    assertNotEquals(beforeRetry, controller.currentToken(21L, NexusCacheType.METADATA));
  }

  @Test
  void findsOnlyMarkedIndexesAtTheCurrentSharedWatermark() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    AssetMetadataCache metadata = mock(AssetMetadataCache.class);
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), metadata, controller,
        mock(RepositoryIndexRebuildDao.class), true);
    RepositoryRuntime group = runtime(21L, 60);
    NexusLikeCacheInfo info = cache.current(group, java.time.Instant.now());
    Map<String, Object> attributes = cache.freshAttributes(group, info, null);
    CachedAssetMetadata snapshot = snapshot("INDEX", attributes, true);
    when(metadata.find(eq(group.id()), eq("index.yaml"), any())).thenReturn(Optional.of(snapshot));

    assertTrue(cache.enabled());
    assertTrue(cache.findFresh(group, java.time.Instant.now()).isPresent());

    controller.invalidate(group.id(), NexusCacheType.METADATA);
    assertFalse(cache.findFresh(group, java.time.Instant.now()).isPresent());
  }

  @Test
  void expiresAtTheEarliestMemberIndexDeadlineWithoutRestartingItsTtl() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    AssetMetadataCache metadata = mock(AssetMetadataCache.class);
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), metadata, controller,
        mock(RepositoryIndexRebuildDao.class), true);
    RepositoryRuntime group = runtime(21L, 60);
    java.time.Instant now = java.time.Instant.parse("2026-08-30T00:00:00Z");
    java.time.Instant memberFreshUntil = now.plusSeconds(30);
    NexusLikeCacheInfo info = cache.current(group, now);
    CachedAssetMetadata snapshot = snapshot(
        "INDEX", cache.freshAttributes(group, info, memberFreshUntil), true);
    when(metadata.find(eq(group.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(snapshot));

    assertEquals(memberFreshUntil, cache.memberIndexFreshUntil(snapshot));
    assertTrue(cache.findFresh(group, now.plusSeconds(29)).isPresent());
    assertFalse(cache.findFresh(group, memberFreshUntil).isPresent());
  }

  @Test
  void rejectsMalformedMemberFreshnessDeadlines() {
    AssetMetadataCache metadata = mock(AssetMetadataCache.class);
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        new StubRepositoryDao(), mock(AssetDao.class), metadata, controller,
        mock(RepositoryIndexRebuildDao.class), true);
    RepositoryRuntime group = runtime(21L, 60);
    java.time.Instant now = java.time.Instant.parse("2026-08-30T00:00:00Z");
    Map<String, Object> attributes = new HashMap<>(
        cache.freshAttributes(group, cache.current(group, now), null));
    attributes.put(HelmGroupIndexCache.MEMBER_INDEX_FRESH_UNTIL_ATTRIBUTE, "not-an-instant");
    when(metadata.find(eq(group.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(snapshot("INDEX", attributes, true)));

    assertFalse(cache.findFresh(group, now).isPresent());
  }

  @Test
  void rejectsAnIndexBuiltFromAnOlderProxyConfigurationSnapshot() {
    AssetMetadataCache metadata = mock(AssetMetadataCache.class);
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        new StubRepositoryDao(), mock(AssetDao.class), metadata, controller,
        mock(RepositoryIndexRebuildDao.class), true);
    RepositoryRuntime oldGroup = runtime(
        21L, 60, List.of(proxyRuntime(11L, "https://old.example.test/")));
    RepositoryRuntime updatedGroup = runtime(
        21L, 60, List.of(proxyRuntime(11L, "https://new.example.test/")));
    java.time.Instant now = java.time.Instant.parse("2026-08-30T00:00:00Z");
    CachedAssetMetadata snapshot = snapshot(
        "INDEX", cache.freshAttributes(oldGroup, cache.current(oldGroup, now), null), true);
    when(metadata.find(eq(oldGroup.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(snapshot));

    assertTrue(cache.findFresh(oldGroup, now).isPresent());
    assertFalse(cache.findFresh(updatedGroup, now).isPresent());
  }

  @Test
  void rejectsMissingUnmarkedAndBloblessSnapshotsAndHonorsDisableSwitch() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    AssetMetadataCache metadata = mock(AssetMetadataCache.class);
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    RepositoryRuntime group = runtime(21L, 60);
    HelmGroupIndexCache disabled = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), metadata, controller,
        mock(RepositoryIndexRebuildDao.class), false);
    assertFalse(disabled.findFresh(group, java.time.Instant.now()).isPresent());
    disabled.invalidateMemberAfterCommit(11L);
    disabled.invalidateGroupAfterCommit(group.id());

    HelmGroupIndexCache enabled = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), metadata, controller,
        mock(RepositoryIndexRebuildDao.class), true);
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
        repositories, mock(AssetDao.class), mock(AssetMetadataCache.class), controller,
        mock(RepositoryIndexRebuildDao.class), true);
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
    RepositoryIndexRebuildDao rebuildQueue = mock(RepositoryIndexRebuildDao.class);
    doThrow(new IllegalStateException("watermark unavailable"))
        .when(controller).invalidateOrThrow(21L, NexusCacheType.METADATA);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), mock(AssetMetadataCache.class), controller,
        rebuildQueue, true);

    assertDoesNotThrow(() -> cache.invalidateMemberAfterCommit(11L));
    verify(rebuildQueue).enqueue(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
        RepositoryIndexRebuildDao.ROOT_SCOPE);
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
    return runtime(id, metadataMaxAgeMinutes, List.of());
  }

  private static RepositoryRuntime runtime(
      long id, int metadataMaxAgeMinutes, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, "helm-group", RepositoryFormat.HELM, RepositoryType.GROUP, "helm-group", true, 7L,
        null, null, null, true, null, 60, metadataMaxAgeMinutes, true, null, members);
  }

  private static RepositoryRuntime proxyRuntime(long id, String remoteUrl) {
    return new RepositoryRuntime(
        id, "helm-proxy", RepositoryFormat.HELM, RepositoryType.PROXY, "helm-proxy", true, 7L,
        null, null, null, true, remoteUrl, 60, 60, true, null, List.of());
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
