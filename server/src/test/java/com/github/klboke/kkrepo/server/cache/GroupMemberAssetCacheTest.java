package com.github.klboke.kkrepo.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GroupMemberAssetCacheTest {

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
