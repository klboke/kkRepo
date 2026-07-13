package com.github.klboke.kkrepo.server.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.catalog.CatalogCacheBroadcaster;
import com.github.klboke.kkrepo.server.support.dao.BlobStoreDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RepositoryCatalogCacheTest {

  @Test
  void loadsMembersWithoutPerGroupQueries() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.add(hosted(1L, "npm-hosted"));
    repositories.add(group(2L, "npm-group"));
    repositories.members.put(2L, List.of("npm-hosted"));

    RepositoryCatalogCache cache = new RepositoryCatalogCache(repositories, new StubBlobStoreDao(), true);

    RepositoryCatalogCache.RepositoryCatalog catalog = cache.current().orElseThrow();

    assertEquals(2, catalog.records().size());
    assertEquals(List.of("npm-hosted"), catalog.membersOf(2L));
    assertEquals(List.of(), catalog.membersOf(1L));
    assertEquals("default", catalog.blobStoreNames().get(1L));
    // batched once; never falls back to per-group listMembers
    assertEquals(1, repositories.listAllCalls);
    assertEquals(0, repositories.listMembersCalls);
  }

  @Test
  void mutationBroadcastRefreshesSiblingCatalogImmediately() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    repositories.add(group(2L, "npm-group"));
    InMemoryBroadcaster broadcaster = new InMemoryBroadcaster();
    RepositoryCatalogCache writer =
        new RepositoryCatalogCache(repositories, new StubBlobStoreDao(), true, broadcaster);
    RepositoryCatalogCache sibling =
        new RepositoryCatalogCache(repositories, new StubBlobStoreDao(), true, broadcaster);

    writer.warmUp();
    sibling.warmUp();
    assertEquals(List.of(), sibling.current().orElseThrow().membersOf(2L));

    repositories.members.put(2L, List.of("npm-hosted"));
    assertEquals(List.of(), sibling.current().orElseThrow().membersOf(2L));

    writer.refreshAfterCommit();

    assertEquals(List.of("npm-hosted"), sibling.current().orElseThrow().membersOf(2L));
    assertTrue(broadcaster.publishCalls >= 1);
  }

  private static RepositoryRecord hosted(long id, String name) {
    return record(id, name, RepositoryType.HOSTED);
  }

  private static RepositoryRecord group(long id, String name) {
    return record(id, name, RepositoryType.GROUP);
  }

  private static RepositoryRecord record(long id, String name, RepositoryType type) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.NPM, type, "npm-" + type.name().toLowerCase(java.util.Locale.ROOT),
        true, 1L, null, null, null, null, null, true, Map.of());
  }

  private static final class StubRepositoryDao extends RepositoryDaoAdapter {
    private final List<RepositoryRecord> records = new ArrayList<>();
    private final Map<Long, List<String>> members = new LinkedHashMap<>();
    private int listAllCalls;
    private int listMembersCalls;

    private StubRepositoryDao() {
      super(null, null);
    }

    private void add(RepositoryRecord record) {
      records.add(record);
    }

    @Override
    public List<RepositoryRecord> list() {
      return List.copyOf(records);
    }

    @Override
    public Map<Long, List<String>> listAllGroupMembers() {
      listAllCalls++;
      Map<Long, List<String>> copy = new LinkedHashMap<>();
      members.forEach((groupId, names) -> copy.put(groupId, List.copyOf(names)));
      return copy;
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      listMembersCalls++;
      return List.of();
    }
  }

  private static final class StubBlobStoreDao extends BlobStoreDaoAdapter {
    private StubBlobStoreDao() {
      super(null, null);
    }

    @Override
    public List<BlobStoreRecord> list() {
      return List.of(new BlobStoreRecord(1L, "default", "s3", null, null, "bucket", null, Map.of()));
    }
  }

  private static final class InMemoryBroadcaster implements CatalogCacheBroadcaster {
    private final Map<String, List<Runnable>> listeners = new LinkedHashMap<>();
    private int publishCalls;

    @Override
    public void subscribe(String catalogName, Runnable refreshListener) {
      listeners.computeIfAbsent(catalogName, ignored -> new ArrayList<>()).add(refreshListener);
    }

    @Override
    public void publishRefresh(String catalogName) {
      publishCalls++;
      List.copyOf(listeners.getOrDefault(catalogName, List.of())).forEach(Runnable::run);
    }
  }
}
