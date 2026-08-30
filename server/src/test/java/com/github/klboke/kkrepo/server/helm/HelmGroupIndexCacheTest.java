package com.github.klboke.kkrepo.server.helm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class HelmGroupIndexCacheTest {
  @Test
  void fastPathUsesANewTransactionAfterTheMemberWriteCommits() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.putGroupsContaining(11L, List.of(group(21L, "all")));
    RepositoryIndexRebuildDao rebuildQueue = mock(RepositoryIndexRebuildDao.class);
    when(rebuildQueue.enqueueHelmGroupInvalidation(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION)).thenReturn("request");
    when(rebuildQueue.acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
        "request")).thenReturn(true);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(new SimpleTransactionStatus());
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories,
        mock(AssetDao.class),
        mock(AssetMetadataCache.class),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        rebuildQueue,
        transactionManager,
        true);

    cache.invalidateMemberAfterCommit(11L);

    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(definition.capture());
    assertEquals(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW,
        definition.getValue().getPropagationBehavior());
    verify(transactionManager).commit(any(TransactionStatus.class));
  }

  @Test
  void memberIndexChangeInvalidatesEveryContainingGroupAcrossReplicas() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.putGroupsContaining(11L, List.of(group(21L, "nested")));
    repositories.putGroupsContaining(21L, List.of(group(31L, "root")));
    RepositoryIndexRebuildDao rebuildQueue = mock(RepositoryIndexRebuildDao.class);
    when(rebuildQueue.enqueueHelmGroupInvalidation(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION)).thenReturn("nested-request");
    when(rebuildQueue.enqueueHelmGroupInvalidation(
        31L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION)).thenReturn("root-request");
    when(rebuildQueue.acknowledgeHelmGroupInvalidationIfRequestToken(
        eq(21L), eq(RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION),
        eq("nested-request"))).thenReturn(true);
    when(rebuildQueue.acknowledgeHelmGroupInvalidationIfRequestToken(
        eq(31L), eq(RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION),
        eq("root-request"))).thenReturn(true);
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
    String nestedContentBefore = controller.currentToken(21L, NexusCacheType.CONTENT);
    String rootContentBefore = controller.currentToken(31L, NexusCacheType.CONTENT);

    cache.invalidateMemberAfterCommit(11L);

    assertNotEquals(nestedBefore, controller.currentToken(21L, NexusCacheType.METADATA));
    assertNotEquals(rootBefore, controller.currentToken(31L, NexusCacheType.METADATA));
    assertNotEquals(nestedContentBefore, controller.currentToken(21L, NexusCacheType.CONTENT));
    assertNotEquals(rootContentBefore, controller.currentToken(31L, NexusCacheType.CONTENT));
    verify(rebuildQueue).enqueueHelmGroupInvalidation(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION);
    verify(rebuildQueue).enqueueHelmGroupInvalidation(
        31L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION);
    verify(rebuildQueue).acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
        "nested-request");

    String beforeRetry = controller.currentToken(21L, NexusCacheType.METADATA);
    String contentBeforeRetry = controller.currentToken(21L, NexusCacheType.CONTENT);
    cache.retryInvalidation(21L);
    assertNotEquals(beforeRetry, controller.currentToken(21L, NexusCacheType.METADATA));
    assertNotEquals(contentBeforeRetry, controller.currentToken(21L, NexusCacheType.CONTENT));
  }

  @Test
  void packageChangeInvalidatesContentWinnersWithoutRebuildingMergedIndexes() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.putGroupsContaining(11L, List.of(group(21L, "all")));
    RepositoryIndexRebuildDao rebuildQueue = mock(RepositoryIndexRebuildDao.class);
    when(rebuildQueue.enqueueHelmGroupInvalidation(
        21L, RepositoryIndexRebuildDao.HELM_GROUP_CONTENT_INVALIDATION))
        .thenReturn("content-request");
    when(rebuildQueue.acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_CONTENT_INVALIDATION,
        "content-request"))
        .thenReturn(true);
    NexusLikeCacheController controller =
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories,
        mock(AssetDao.class),
        mock(AssetMetadataCache.class),
        controller,
        rebuildQueue,
        true);
    String metadataBefore = controller.currentToken(21L, NexusCacheType.METADATA);
    String contentBefore = controller.currentToken(21L, NexusCacheType.CONTENT);

    cache.invalidateMemberContentAfterCommit(11L);

    assertEquals(metadataBefore, controller.currentToken(21L, NexusCacheType.METADATA));
    assertNotEquals(contentBefore, controller.currentToken(21L, NexusCacheType.CONTENT));
    verify(rebuildQueue).enqueueHelmGroupInvalidation(
        21L, RepositoryIndexRebuildDao.HELM_GROUP_CONTENT_INVALIDATION);
    verify(rebuildQueue).acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_CONTENT_INVALIDATION,
        "content-request");
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
  void rejectsCachedIndexesAfterARemoteDurableWatermarkAdvance() {
    VersionWatermark watermark = mock(VersionWatermark.class);
    when(watermark.current("repo:21:METADATA")).thenReturn(1L);
    when(watermark.currentDurable("repo:21:METADATA")).thenReturn(2L);
    NexusLikeCacheController controller = new NexusLikeCacheController(watermark, 60);
    AssetMetadataCache metadata = mock(AssetMetadataCache.class);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        new StubRepositoryDao(), mock(AssetDao.class), metadata, controller,
        mock(RepositoryIndexRebuildDao.class), true);
    RepositoryRuntime group = runtime(21L, 60);
    java.time.Instant now = java.time.Instant.parse("2026-08-30T00:00:00Z");
    NexusLikeCacheInfo info = cache.current(group, now.minusSeconds(1));
    CachedAssetMetadata snapshot = snapshot(
        "INDEX", cache.freshAttributes(group, info, null), true);
    when(metadata.find(eq(group.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(snapshot));

    assertFalse(controller.isStale(
        group.id(), NexusCacheType.METADATA, info, 60, now));
    assertFalse(cache.findFresh(group, now).isPresent());
  }

  @Test
  void rejectsCachedIndexesWhenTheDurableWatermarkCannotBeRead() {
    VersionWatermark watermark = mock(VersionWatermark.class);
    when(watermark.current("repo:21:METADATA")).thenReturn(1L);
    when(watermark.currentDurable("repo:21:METADATA"))
        .thenThrow(new IllegalStateException("database unavailable"));
    NexusLikeCacheController controller = new NexusLikeCacheController(watermark, 60);
    AssetMetadataCache metadata = mock(AssetMetadataCache.class);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        new StubRepositoryDao(), mock(AssetDao.class), metadata, controller,
        mock(RepositoryIndexRebuildDao.class), true);
    RepositoryRuntime group = runtime(21L, 60);
    java.time.Instant now = java.time.Instant.parse("2026-08-30T00:00:00Z");
    NexusLikeCacheInfo info = cache.current(group, now.minusSeconds(1));
    CachedAssetMetadata snapshot = snapshot(
        "INDEX", cache.freshAttributes(group, info, null), true);
    when(metadata.find(eq(group.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(snapshot));

    assertFalse(cache.findFresh(group, now).isPresent());
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
  void keepsWinnerInvalidationDurableWhenMergedIndexCachingIsDisabled() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.putGroupsContaining(11L, List.of(group(21L, "all")));
    RepositoryIndexRebuildDao rebuildQueue = mock(RepositoryIndexRebuildDao.class);
    when(rebuildQueue.enqueueHelmGroupInvalidation(
        21L, RepositoryIndexRebuildDao.HELM_GROUP_CONTENT_INVALIDATION))
        .thenReturn("content-request");
    when(rebuildQueue.acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_CONTENT_INVALIDATION,
        "content-request"))
        .thenReturn(true);
    HelmGroupIndexCache disabled = new HelmGroupIndexCache(
        repositories,
        mock(AssetDao.class),
        mock(AssetMetadataCache.class),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        rebuildQueue,
        false);

    disabled.invalidateMemberContentAfterCommit(11L);

    verify(rebuildQueue).enqueueHelmGroupInvalidation(
        21L, RepositoryIndexRebuildDao.HELM_GROUP_CONTENT_INVALIDATION);
    verify(rebuildQueue).acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_CONTENT_INVALIDATION,
        "content-request");
  }

  @Test
  void rejectsAndInvalidatesSnapshotsWhenAnOlderReplicaUpdatesTheMemberIndexRow() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    AssetMetadataCache metadata = mock(AssetMetadataCache.class);
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    RepositoryIndexRebuildDao rebuildQueue = mock(RepositoryIndexRebuildDao.class);
    when(rebuildQueue.enqueueHelmGroupInvalidation(
        21L, RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION))
        .thenReturn("mixed-version-request");
    when(rebuildQueue.acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
        "mixed-version-request"))
        .thenReturn(true);
    AssetDao assets = mock(AssetDao.class);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories, assets, metadata, controller, rebuildQueue, true);
    RepositoryRuntime group = runtime(
        21L, 60, List.of(proxyRuntime(11L, "https://charts.example.test/")));
    java.time.Instant now = java.time.Instant.parse("2026-08-30T00:00:00Z");
    when(assets.findAssetsByPathHash(any(), any()))
        .thenReturn(Map.of(11L, indexAsset(11L, 101L, 201L)))
        .thenReturn(Map.of(11L, indexAsset(
            11L, 101L, 201L, java.time.Instant.EPOCH.plusSeconds(1))));
    Map<String, Object> attributes =
        cache.freshAttributes(group, cache.current(group, now), null);
    when(metadata.find(eq(group.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(snapshot("INDEX", attributes, true)));

    assertFalse(cache.findFresh(group, now).isPresent());

    verify(rebuildQueue).enqueueHelmGroupInvalidation(
        21L, RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION);
    verify(rebuildQueue).acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
        "mixed-version-request");
  }

  @Test
  void winnerGenerationTracksExactProvenanceBindingsWrittenByOlderReplicas() {
    AssetDao assets = mock(AssetDao.class);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        new StubRepositoryDao(),
        assets,
        mock(AssetMetadataCache.class),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        mock(RepositoryIndexRebuildDao.class),
        true);
    RepositoryRuntime group = runtime(
        21L,
        60,
        List.of(
            proxyRuntime(11L, "https://first.example.test/"),
            proxyRuntime(12L, "https://second.example.test/")));
    Map<Long, AssetRecord> indexes = Map.of(
        11L, indexAsset(11L, 101L, 201L),
        12L, indexAsset(12L, 102L, 202L));
    AssetRecord laterWinner = provenanceAsset(12L, 112L, 212L);
    AssetRecord newEarlierWinner = provenanceAsset(11L, 111L, 211L);
    when(assets.findAssetsByPathHash(any(), any()))
        .thenReturn(indexes)
        .thenReturn(Map.of(12L, laterWinner))
        .thenReturn(indexes)
        .thenReturn(Map.of(11L, newEarlierWinner, 12L, laterWinner));

    String before = cache.winnerAssetGeneration(group, "demo-1.0.0.tgz.prov");
    String after = cache.winnerAssetGeneration(group, "demo-1.0.0.tgz.prov");

    assertNotEquals(before, after);
  }

  @Test
  void winnerGenerationValidatesPathsAndFailsClosedOnMissingAssetSnapshots() {
    AssetDao assets = mock(AssetDao.class);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        new StubRepositoryDao(),
        assets,
        mock(AssetMetadataCache.class),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        mock(RepositoryIndexRebuildDao.class),
        true);
    RepositoryRuntime group = runtime(
        21L, 60, List.of(proxyRuntime(11L, "https://charts.example.test/")));
    Map<Long, AssetRecord> indexes = Map.of(11L, indexAsset(11L, 101L, 201L));
    when(assets.findAssetsByPathHash(any(), any()))
        .thenReturn(indexes)
        .thenReturn(indexes)
        .thenReturn(indexes)
        .thenReturn(null);

    assertThrows(IllegalArgumentException.class, () -> cache.winnerAssetGeneration(group, null));
    assertThrows(IllegalArgumentException.class, () -> cache.winnerAssetGeneration(group, " "));
    assertEquals(
        cache.memberAssetGeneration(group),
        cache.winnerAssetGeneration(group, HelmHostedService.INDEX_PATH));
    assertThrows(
        IllegalStateException.class,
        () -> cache.winnerAssetGeneration(group, "demo-1.0.0.tgz"));
  }

  @Test
  void invalidatesCachedStateWhenTheDurableMemberGenerationCannotBeRead() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    AssetMetadataCache metadata = mock(AssetMetadataCache.class);
    RepositoryIndexRebuildDao rebuildQueue = mock(RepositoryIndexRebuildDao.class);
    when(rebuildQueue.enqueueHelmGroupInvalidation(
        21L, RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION))
        .thenReturn("generation-read-failed");
    when(rebuildQueue.acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
        "generation-read-failed"))
        .thenReturn(true);
    NexusLikeCacheController controller =
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    AssetDao assets = mock(AssetDao.class);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories, assets, metadata, controller, rebuildQueue, true);
    RepositoryRuntime group = runtime(
        21L, 60, List.of(proxyRuntime(11L, "https://charts.example.test/")));
    java.time.Instant now = java.time.Instant.parse("2026-08-30T00:00:00Z");
    Map<String, Object> attributes = cache.freshAttributes(
        group, cache.current(group, now), null, "member-generation");
    when(metadata.find(eq(group.id()), eq("index.yaml"), any()))
        .thenReturn(Optional.of(snapshot("INDEX", attributes, true)));
    when(assets.findAssetsByPathHash(any(), any()))
        .thenThrow(new IllegalStateException("asset binding unavailable"));

    assertFalse(cache.findFresh(group, now).isPresent());

    verify(rebuildQueue).enqueueHelmGroupInvalidation(
        21L, RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION);
    verify(rebuildQueue).acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
        "generation-read-failed");
  }

  @Test
  void rejectsInvalidFreshnessAndInvalidationInputs() {
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        new StubRepositoryDao(),
        mock(AssetDao.class),
        mock(AssetMetadataCache.class),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        mock(RepositoryIndexRebuildDao.class),
        true);
    RepositoryRuntime group = runtime(21L, 60);

    assertThrows(
        IllegalArgumentException.class,
        () -> cache.freshAttributes(group, cache.current(group, java.time.Instant.now()), null, " "));
    assertNull(cache.memberIndexFreshUntil(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> cache.retryInvalidation(21L, "unsupported"));
  }

  @Test
  void rejectsAnEmptyDurableInvalidationToken() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.putGroupsContaining(11L, List.of(group(21L, "all")));
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories,
        mock(AssetDao.class),
        mock(AssetMetadataCache.class),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        mock(RepositoryIndexRebuildDao.class),
        true);

    assertThrows(IllegalStateException.class, () -> cache.invalidateMemberAfterCommit(11L));
  }

  @Test
  void leavesWatermarksUnchangedWhenANewerInvalidationSupersedesTheFastPath() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.putGroupsContaining(11L, List.of(group(21L, "all")));
    RepositoryIndexRebuildDao rebuildQueue = mock(RepositoryIndexRebuildDao.class);
    when(rebuildQueue.enqueueHelmGroupInvalidation(
        21L, RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION))
        .thenReturn("superseded-request");
    NexusLikeCacheController controller =
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories,
        mock(AssetDao.class),
        mock(AssetMetadataCache.class),
        controller,
        rebuildQueue,
        true);
    String metadataBefore = controller.currentToken(21L, NexusCacheType.METADATA);
    String contentBefore = controller.currentToken(21L, NexusCacheType.CONTENT);

    cache.invalidateMemberAfterCommit(11L);

    assertEquals(metadataBefore, controller.currentToken(21L, NexusCacheType.METADATA));
    assertEquals(contentBefore, controller.currentToken(21L, NexusCacheType.CONTENT));
    verify(rebuildQueue).acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
        "superseded-request");
  }

  @Test
  void groupInvalidationIsDeferredUntilCommitAndRecursesToParents() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.putGroupsContaining(21L, List.of(group(31L, "root")));
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    RepositoryIndexRebuildDao rebuildQueue = mock(RepositoryIndexRebuildDao.class);
    when(rebuildQueue.enqueueHelmGroupInvalidation(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION)).thenReturn("nested-1", "nested-2");
    when(rebuildQueue.enqueueHelmGroupInvalidation(
        31L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION)).thenReturn("root-1", "root-2");
    when(rebuildQueue.acknowledgeHelmGroupInvalidationIfRequestToken(
        eq(21L), eq(RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION),
        eq("nested-2"))).thenReturn(true);
    when(rebuildQueue.acknowledgeHelmGroupInvalidationIfRequestToken(
        eq(31L), eq(RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION),
        eq("root-2"))).thenReturn(true);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), mock(AssetMetadataCache.class), controller,
        rebuildQueue, true);
    String nestedBefore = controller.currentToken(21L, NexusCacheType.METADATA);
    String rootBefore = controller.currentToken(31L, NexusCacheType.METADATA);
    String nestedContentBefore = controller.currentToken(21L, NexusCacheType.CONTENT);
    String rootContentBefore = controller.currentToken(31L, NexusCacheType.CONTENT);

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
      assertNotEquals(nestedContentBefore, controller.currentToken(21L, NexusCacheType.CONTENT));
      assertNotEquals(rootContentBefore, controller.currentToken(31L, NexusCacheType.CONTENT));
      verify(rebuildQueue).acknowledgeHelmGroupInvalidationIfRequestToken(
          21L,
          RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
          "nested-2");
      verify(rebuildQueue, never()).acknowledgeHelmGroupInvalidationIfRequestToken(
          21L,
          RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
          "nested-1");
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
    when(rebuildQueue.enqueueHelmGroupInvalidation(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION)).thenReturn("failed-request");
    when(rebuildQueue.acknowledgeHelmGroupInvalidationIfRequestToken(
        eq(21L), eq(RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION),
        eq("failed-request"))).thenReturn(true);
    doThrow(new IllegalStateException("watermark unavailable"))
        .when(controller).invalidateOrThrow(21L, NexusCacheType.CONTENT);
    HelmGroupIndexCache cache = new HelmGroupIndexCache(
        repositories, mock(AssetDao.class), mock(AssetMetadataCache.class), controller,
        rebuildQueue, true);

    assertDoesNotThrow(() -> cache.invalidateMemberAfterCommit(11L));
    verify(rebuildQueue).enqueueHelmGroupInvalidation(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION);
    verify(controller, never()).invalidateOrThrow(21L, NexusCacheType.METADATA);
    verify(rebuildQueue).acknowledgeHelmGroupInvalidationIfRequestToken(
        21L,
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
        "failed-request");
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

  private static AssetRecord indexAsset(long repositoryId, long assetId, long blobId) {
    return indexAsset(repositoryId, assetId, blobId, java.time.Instant.EPOCH);
  }

  private static AssetRecord indexAsset(
      long repositoryId, long assetId, long blobId, java.time.Instant lastUpdatedAt) {
    return new AssetRecord(
        assetId,
        repositoryId,
        null,
        blobId,
        RepositoryFormat.HELM,
        "index.yaml",
        null,
        "index.yaml",
        "INDEX",
        "text/x-yaml",
        4L,
        null,
        lastUpdatedAt,
        Map.of());
  }

  private static AssetRecord provenanceAsset(long repositoryId, long assetId, long blobId) {
    return new AssetRecord(
        assetId,
        repositoryId,
        null,
        blobId,
        RepositoryFormat.HELM,
        "demo-1.0.0.tgz.prov",
        null,
        "demo-1.0.0.tgz.prov",
        "PROVENANCE",
        "application/octet-stream",
        4L,
        null,
        java.time.Instant.EPOCH,
        Map.of());
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
