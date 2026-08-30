package com.github.klboke.kkrepo.server.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GroupMemberAssetCacheTest {

  @Test
  void disabledCacheDoesNotCaptureOrPublishWinnerGenerations() {
    InMemorySharedCache shared = new InMemorySharedCache();
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        shared,
        new StubRepositoryDao(),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        false,
        86400);
    RepositoryRuntime group = runtime(999L, "helm-group");

    assertTrue(cache.captureGeneration(group, NexusCacheType.CONTENT).isEmpty());
    assertTrue(cache.get(group, "demo-1.0.0.tgz", NexusCacheType.CONTENT).isEmpty());
    assertTrue(cache.getIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, null).isEmpty());
    assertTrue(cache.getIfCurrent(
        group,
        "demo-1.0.0.tgz",
        NexusCacheType.CONTENT,
        new GroupMemberAssetCache.Generation(
            group.id(), NexusCacheType.CONTENT, null)).isEmpty());
    assertDoesNotThrow(() -> cache.putIfCurrent(
        group,
        "demo-1.0.0.tgz",
        NexusCacheType.CONTENT,
        101L,
        new GroupMemberAssetCache.Generation(999L, NexusCacheType.CONTENT, "0")));
    assertTrue(shared.getJson(
        "group-member-asset",
        "999:CONTENT:demo-1.0.0.tgz",
        GroupMemberAssetCache.Entry.class).isEmpty());
  }

  @Test
  void generationCaptureFailsClosedWhenTheDurableWatermarkIsUnavailable() {
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60) {
      @Override
      public String currentDurableToken(long repositoryId, NexusCacheType type) {
        throw new IllegalStateException("database unavailable");
      }
    };
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        new InMemorySharedCache(), new StubRepositoryDao(), controller, true, 86400);

    assertTrue(cache.captureGeneration(
        runtime(999L, "helm-group"), NexusCacheType.CONTENT).isEmpty());
  }

  @Test
  void conditionalPublicationRejectsAGenerationCapturedForAnotherGroup() {
    InMemorySharedCache shared = new InMemorySharedCache();
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        shared,
        new StubRepositoryDao(),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        true,
        86400);
    RepositoryRuntime group = runtime(999L, "helm-group");

    cache.putIfCurrent(
        group,
        "demo-1.0.0.tgz",
        NexusCacheType.CONTENT,
        101L,
        new GroupMemberAssetCache.Generation(998L, NexusCacheType.CONTENT, "0"));

    assertTrue(shared.getJson(
        "group-member-asset",
        "999:CONTENT:demo-1.0.0.tgz",
        GroupMemberAssetCache.Entry.class).isEmpty());
    assertTrue(cache.getIfCurrent(
        group,
        "demo-1.0.0.tgz",
        NexusCacheType.CONTENT,
        new GroupMemberAssetCache.Generation(998L, NexusCacheType.CONTENT, "0")).isEmpty());
    assertTrue(cache.getIfCurrent(
        group,
        "demo-1.0.0.tgz",
        NexusCacheType.CONTENT,
        new GroupMemberAssetCache.Generation(
            group.id(), NexusCacheType.METADATA, "0")).isEmpty());
  }

  @Test
  void conditionalPublicationContainsSharedCacheWriteFailures() {
    InMemorySharedCache shared = new InMemorySharedCache() {
      @Override
      public void putJson(String namespace, String key, Object value, Duration ttl) {
        throw new IllegalStateException("shared cache unavailable");
      }
    };
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        shared,
        new StubRepositoryDao(),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        true,
        86400);
    RepositoryRuntime group = runtime(999L, "helm-group");
    GroupMemberAssetCache.Generation generation = cache.captureGeneration(
        group, NexusCacheType.CONTENT).orElseThrow();

    assertDoesNotThrow(() -> cache.putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, 101L, generation));
  }

  @Test
  void publishesWinnerWhenCapturedGenerationRemainsCurrent() {
    InMemorySharedCache shared = new InMemorySharedCache();
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        shared, new StubRepositoryDao(), controller, true, 86400);
    RepositoryRuntime group = runtime(999L, "helm-group");
    GroupMemberAssetCache.Generation generation = cache.captureGeneration(
        group, NexusCacheType.CONTENT).orElseThrow();

    cache.putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, 101L, generation);

    assertEquals(
        101L,
        cache.getIfCurrent(
            group, "demo-1.0.0.tgz", null, generation).orElseThrow());
  }

  @Test
  void memberSourceGenerationFencesWinnersWhenTheGroupTokenDoesNotMove() {
    InMemorySharedCache shared = new InMemorySharedCache();
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        shared,
        new StubRepositoryDao(),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        true,
        86400);
    RepositoryRuntime group = runtime(999L, "helm-group");
    GroupMemberAssetCache.Generation original = cache.captureGeneration(
        group, NexusCacheType.CONTENT).orElseThrow().withSourceGeneration("member-index-1");

    cache.putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, 101L, original);

    GroupMemberAssetCache.Generation changed = new GroupMemberAssetCache.Generation(
        group.id(), NexusCacheType.CONTENT, original.cacheToken(), "member-index-2");
    assertEquals(
        101L,
        cache.getIfCurrent(
            group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, original).orElseThrow());
    assertTrue(cache.getIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, changed).isEmpty());
    assertEquals(
        "member-index-1",
        shared.getJson(
            "group-member-asset",
            "999:CONTENT:demo-1.0.0.tgz",
            GroupMemberAssetCache.Entry.class).orElseThrow().sourceGeneration());
  }

  @Test
  void generationAwareReadContainsSharedCacheFailures() {
    InMemorySharedCache shared = new InMemorySharedCache() {
      @Override
      public <T> Optional<T> getJson(
          String namespace, String key, Class<T> type) {
        throw new IllegalStateException("shared cache unavailable");
      }
    };
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        shared,
        new StubRepositoryDao(),
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60),
        true,
        86400);
    RepositoryRuntime group = runtime(999L, "helm-group");
    GroupMemberAssetCache.Generation generation = cache.captureGeneration(
        group, NexusCacheType.CONTENT).orElseThrow();

    assertTrue(cache.getIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, generation).isEmpty());
  }

  @Test
  void generationAwareReadDoesNotTrustAStaleNodeLocalToken() {
    InMemorySharedCache shared = new InMemorySharedCache();
    InMemoryVersionWatermark watermark = new InMemoryVersionWatermark();
    NexusLikeCacheController controller = new NexusLikeCacheController(watermark, 60);
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        shared, new StubRepositoryDao(), controller, true, 86400);
    RepositoryRuntime group = runtime(999L, "helm-group");
    cache.put(group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, 101L);
    GroupMemberAssetCache.Generation beforeInvalidation = cache.captureGeneration(
        group, NexusCacheType.CONTENT).orElseThrow();

    watermark.bump("repo:999:CONTENT");
    GroupMemberAssetCache.Generation current = cache.captureGeneration(
        group, NexusCacheType.CONTENT).orElseThrow();

    assertEquals("0", controller.currentToken(group.id(), NexusCacheType.CONTENT));
    assertEquals("1", current.cacheToken());
    assertTrue(cache.getIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, beforeInvalidation).isEmpty());
    assertTrue(cache.getIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, current).isEmpty());
  }

  @Test
  void doesNotPublishWinnerWhenInvalidatedDuringResolution() {
    InMemorySharedCache shared = new InMemorySharedCache();
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        shared, new StubRepositoryDao(), controller, true, 86400);
    RepositoryRuntime group = runtime(999L, "helm-group");
    GroupMemberAssetCache.Generation generation = cache.captureGeneration(
        group, NexusCacheType.CONTENT).orElseThrow();

    controller.invalidateOrThrow(group.id(), NexusCacheType.CONTENT);
    cache.putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, 101L, generation);

    assertFalse(shared.getJson(
        "group-member-asset",
        "999:CONTENT:demo-1.0.0.tgz",
        GroupMemberAssetCache.Entry.class).isPresent());
  }

  @Test
  void cachedMemberIsIgnoredAfterContainingGroupTokenInvalidation() {
    InMemorySharedCache shared = new InMemorySharedCache();
    StubRepositoryDao dao = new StubRepositoryDao();
    dao.putGroupsContaining(101L, List.of(record(999L, "pypi-group")));
    NexusLikeCacheController controller = new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    GroupMemberAssetCache cache = new GroupMemberAssetCache(shared, dao, controller, true, 86400);
    RepositoryRuntime group = runtime(999L, "pypi-group");

    cache.put(group, "packages/demo/demo-1.0.0.whl", NexusCacheType.CONTENT, 101L);

    assertEquals(101L, cache.get(group, "packages/demo/demo-1.0.0.whl", NexusCacheType.CONTENT).orElseThrow());

    cache.invalidateMemberAfterCommit(101L);

    assertTrue(cache.get(group, "packages/demo/demo-1.0.0.whl", NexusCacheType.CONTENT).isEmpty());
  }

  @Test
  void typedMemberInvalidationPreservesUnrelatedGroupMetadata() {
    InMemorySharedCache shared = new InMemorySharedCache();
    StubRepositoryDao dao = new StubRepositoryDao();
    dao.putGroupsContaining(101L, List.of(record(999L, "helm-group")));
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        shared, dao, controller, true, 86400);
    RepositoryRuntime group = runtime(999L, "helm-group");

    cache.put(group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, 101L);
    cache.put(group, "index.yaml", NexusCacheType.METADATA, 202L);

    cache.invalidateMemberAfterCommit(101L, NexusCacheType.CONTENT);

    assertTrue(cache.get(group, "demo-1.0.0.tgz", NexusCacheType.CONTENT).isEmpty());
    assertEquals(
        202L, cache.get(group, "index.yaml", NexusCacheType.METADATA).orElseThrow());
  }

  @Test
  void typedMemberInvalidationIsDeduplicatedAndDeferredUntilCommit() {
    InMemorySharedCache shared = new InMemorySharedCache();
    StubRepositoryDao dao = new StubRepositoryDao();
    dao.putGroupsContaining(101L, List.of(record(999L, "helm-group")));
    NexusLikeCacheController controller = new NexusLikeCacheController(
        new InMemoryVersionWatermark(), 60);
    GroupMemberAssetCache cache = new GroupMemberAssetCache(
        shared, dao, controller, true, 86400);
    RepositoryRuntime group = runtime(999L, "helm-group");
    cache.put(group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, 101L);

    TransactionSynchronizationManager.initSynchronization();
    try {
      cache.invalidateMemberAfterCommit(101L, NexusCacheType.CONTENT);
      cache.invalidateMemberAfterCommit(101L, NexusCacheType.CONTENT);
      assertEquals(
          101L,
          cache.get(group, "demo-1.0.0.tgz", NexusCacheType.CONTENT).orElseThrow());

      List<TransactionSynchronization> synchronizations =
          TransactionSynchronizationManager.getSynchronizations();
      assertEquals(1, synchronizations.size());
      synchronizations.forEach(TransactionSynchronization::afterCommit);
      assertTrue(cache.get(
          group, "demo-1.0.0.tgz", NexusCacheType.CONTENT).isEmpty());
      synchronizations.forEach(sync -> sync.afterCompletion(
          TransactionSynchronization.STATUS_COMMITTED));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void cachedMemberIsIgnoredAfterGroupInvalidation() {
    InMemorySharedCache shared = new InMemorySharedCache();
    StubRepositoryDao dao = new StubRepositoryDao();
    NexusLikeCacheController controller = new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    GroupMemberAssetCache cache = new GroupMemberAssetCache(shared, dao, controller, true, 86400);
    RepositoryRuntime group = runtime(999L, "npm-group");

    cache.put(group, "@scope/demo/-/demo-1.0.0.tgz", NexusCacheType.CONTENT, 101L);
    cache.invalidateGroupAfterCommit(999L);

    assertTrue(cache.get(group, "@scope/demo/-/demo-1.0.0.tgz", NexusCacheType.CONTENT).isEmpty());
  }

  private static RepositoryRuntime runtime(long id, String name) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.PYPI, RepositoryType.GROUP, "pypi-group",
        true, 1L, "ALLOW", null, null, true, null, null, null, List.of());
  }

  private static RepositoryRecord record(long id, String name) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.PYPI, RepositoryType.GROUP,
        "pypi-group", true, null, null, null, null, null, "ALLOW", false, Map.of());
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
