package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PypiGroupSimpleIndexCacheTest {
  private static final Instant NOW = Instant.parse("2026-06-04T09:00:00Z");

  @Test
  void freshProjectIndexAssetIsReusable() {
    Fixture fixture = fixture(true);
    RepositoryRuntime group = runtime(99L, "pypi-group", 60);
    fixture.assets.putAsset(group.id(), PypiPaths.indexPath("demo"),
        PypiGroupSimpleIndexCache.INDEX, fixture.cache.freshAttributes(group, "demo", NOW), NOW);

    assertTrue(fixture.cache.findFresh(
        group, PypiPaths.indexPath("demo"), PypiGroupSimpleIndexCache.INDEX, NOW.plusSeconds(30)).isPresent());
  }

  @Test
  void missingCacheInfoIsStaleLikeNexus() {
    Fixture fixture = fixture(true);
    RepositoryRuntime group = runtime(99L, "pypi-group", 60);
    fixture.assets.putAsset(group.id(), PypiPaths.indexPath("demo"),
        PypiGroupSimpleIndexCache.INDEX, Map.of("name", "demo"), NOW);

    assertTrue(fixture.cache.findFresh(
        group, PypiPaths.indexPath("demo"), PypiGroupSimpleIndexCache.INDEX, NOW).isEmpty());
  }

  @Test
  void metadataMaxAgeExpiresIndexAsset() {
    Fixture fixture = fixture(true);
    RepositoryRuntime group = runtime(99L, "pypi-group", 1);
    fixture.assets.putAsset(group.id(), PypiPaths.indexPath("demo"),
        PypiGroupSimpleIndexCache.INDEX, fixture.cache.freshAttributes(group, "demo", NOW), NOW);

    assertTrue(fixture.cache.findFresh(
        group, PypiPaths.indexPath("demo"), PypiGroupSimpleIndexCache.INDEX, NOW.plusSeconds(59)).isPresent());
    assertTrue(fixture.cache.findFresh(
        group, PypiPaths.indexPath("demo"), PypiGroupSimpleIndexCache.INDEX, NOW.plusSeconds(60)).isEmpty());
  }

  @Test
  void memberMetadataMaxAgeExpiresGroupIndexAsset() {
    Fixture fixture = fixture(true);
    RepositoryRuntime proxy = proxyRuntime(10L, "pypi-proxy", 1);
    RepositoryRuntime group = runtime(99L, "pypi-group", 1440, List.of(proxy));
    fixture.assets.putAsset(group.id(), PypiPaths.indexPath("demo"),
        PypiGroupSimpleIndexCache.INDEX, fixture.cache.freshAttributes(group, "demo", NOW), NOW);

    assertTrue(fixture.cache.findFresh(
        group, PypiPaths.indexPath("demo"), PypiGroupSimpleIndexCache.INDEX, NOW.plusSeconds(59)).isPresent());
    assertTrue(fixture.cache.findFresh(
        group, PypiPaths.indexPath("demo"), PypiGroupSimpleIndexCache.INDEX, NOW.plusSeconds(60)).isEmpty());
  }

  @Test
  void memberProjectInvalidationMarksRootAndProjectIndexesAndAncestorsInvalidated() {
    Fixture fixture = fixture(true);
    RepositoryRuntime inner = runtime(20L, "inner", 60);
    RepositoryRuntime outer = runtime(30L, "outer", 60);
    fixture.repositories.putGroupsContaining(10L, List.of(record(inner.id(), inner.name())));
    fixture.repositories.putGroupsContaining(inner.id(), List.of(record(outer.id(), outer.name())));
    putRootAndProjects(fixture, inner);
    putRootAndProjects(fixture, outer);

    fixture.cache.invalidateMemberProjectAfterCommit(10L, "demo");

    assertStale(fixture, inner, PypiPaths.INDEX_PREFIX, PypiGroupSimpleIndexCache.ROOT_INDEX);
    assertStale(fixture, inner, PypiPaths.indexPath("demo"), PypiGroupSimpleIndexCache.INDEX);
    assertStale(fixture, outer, PypiPaths.INDEX_PREFIX, PypiGroupSimpleIndexCache.ROOT_INDEX);
    assertStale(fixture, outer, PypiPaths.indexPath("demo"), PypiGroupSimpleIndexCache.INDEX);
    assertTrue(fixture.cache.findFresh(
        outer, PypiPaths.indexPath("other"), PypiGroupSimpleIndexCache.INDEX, NOW.plusSeconds(1)).isPresent());
    assertTrue(NexusLikeCacheInfo.fromAttributes(
            fixture.assets.asset(outer.id(), PypiPaths.indexPath("demo")).attributes())
        .orElseThrow()
        .invalidated());
    assertFalse(NexusLikeCacheInfo.fromAttributes(
            fixture.assets.asset(outer.id(), PypiPaths.indexPath("other")).attributes())
        .orElseThrow()
        .invalidated());
  }

  @Test
  void groupInvalidationExpiresGroupAndAncestorTokens() {
    Fixture fixture = fixture(true);
    RepositoryRuntime inner = runtime(20L, "inner", 60);
    RepositoryRuntime outer = runtime(30L, "outer", 60);
    fixture.repositories.putGroupsContaining(inner.id(), List.of(record(outer.id(), outer.name())));
    fixture.assets.putAsset(inner.id(), PypiPaths.indexPath("demo"),
        PypiGroupSimpleIndexCache.INDEX, fixture.cache.freshAttributes(inner, "demo", NOW), NOW);
    fixture.assets.putAsset(outer.id(), PypiPaths.indexPath("demo"),
        PypiGroupSimpleIndexCache.INDEX, fixture.cache.freshAttributes(outer, "demo", NOW), NOW);

    fixture.cache.invalidateGroupAfterCommit(inner.id());

    assertStale(fixture, inner, PypiPaths.indexPath("demo"), PypiGroupSimpleIndexCache.INDEX);
    assertStale(fixture, outer, PypiPaths.indexPath("demo"), PypiGroupSimpleIndexCache.INDEX);
  }

  @Test
  void disabledCacheDoesNotServeOrInvalidateAssets() {
    Fixture fixture = fixture(false);
    RepositoryRuntime group = runtime(99L, "pypi-group", 60);
    fixture.assets.putAsset(group.id(), PypiPaths.indexPath("demo"),
        PypiGroupSimpleIndexCache.INDEX, Map.of("name", "demo"), NOW);

    fixture.cache.invalidateMemberProjectAfterCommit(10L, "demo");

    assertTrue(fixture.cache.findFresh(
        group, PypiPaths.indexPath("demo"), PypiGroupSimpleIndexCache.INDEX, NOW).isEmpty());
    assertEquals(Map.of("name", "demo"),
        fixture.assets.asset(group.id(), PypiPaths.indexPath("demo")).attributes());
  }

  private static void putRootAndProjects(Fixture fixture, RepositoryRuntime group) {
    fixture.assets.putAsset(group.id(), PypiPaths.INDEX_PREFIX,
        PypiGroupSimpleIndexCache.ROOT_INDEX, fixture.cache.freshAttributes(group, null, NOW), NOW);
    fixture.assets.putAsset(group.id(), PypiPaths.indexPath("demo"),
        PypiGroupSimpleIndexCache.INDEX, fixture.cache.freshAttributes(group, "demo", NOW), NOW);
    fixture.assets.putAsset(group.id(), PypiPaths.indexPath("other"),
        PypiGroupSimpleIndexCache.INDEX, fixture.cache.freshAttributes(group, "other", NOW), NOW);
  }

  private static void assertStale(Fixture fixture, RepositoryRuntime group, String path, String kind) {
    assertTrue(fixture.cache.findFresh(group, path, kind, NOW.plusSeconds(1)).isEmpty());
  }

  private static Fixture fixture(boolean enabled) {
    StubRepositoryDao repositories = new StubRepositoryDao();
    StubAssetDao assets = new StubAssetDao();
    AssetMetadataCache assetMetadataCache = new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0);
    NexusLikeCacheController controller = new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    PypiGroupSimpleIndexCache cache =
        new PypiGroupSimpleIndexCache(repositories, assets, assetMetadataCache, controller, enabled);
    return new Fixture(cache, repositories, assets);
  }

  private static RepositoryRuntime runtime(long id, String name, int metadataMaxAgeMinutes) {
    return runtime(id, name, metadataMaxAgeMinutes, List.of());
  }

  private static RepositoryRuntime runtime(
      long id,
      String name,
      int metadataMaxAgeMinutes,
      List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.PYPI, RepositoryType.GROUP, "pypi-group",
        true, 1L, "ALLOW", null, null, true, null, null, metadataMaxAgeMinutes,
        null, null, members);
  }

  private static RepositoryRuntime proxyRuntime(long id, String name, int metadataMaxAgeMinutes) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.PYPI, RepositoryType.PROXY, "pypi-proxy",
        true, 1L, "ALLOW", null, null, true, "http://example.invalid/simple/", null,
        metadataMaxAgeMinutes, null, null, List.of());
  }

  private static RepositoryRecord record(long id, String name) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.PYPI, RepositoryType.GROUP,
        "pypi-group", true, 1L, null, null, null, null, "ALLOW", false, Map.of());
  }

  private record Fixture(
      PypiGroupSimpleIndexCache cache,
      StubRepositoryDao repositories,
      StubAssetDao assets) {}

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

  private static class StubAssetDao extends AssetDaoAdapter {
    private final AtomicLong assetIds = new AtomicLong(100);
    private final AtomicLong blobIds = new AtomicLong(200);
    private final Map<String, AssetRecord> assets = new HashMap<>();
    private final Map<Long, AssetBlobRecord> blobs = new HashMap<>();

    StubAssetDao() {
      super(null, null);
    }

    void putAsset(long repositoryId, String path, String kind, Map<String, Object> attributes, Instant updatedAt) {
      long blobId = blobIds.incrementAndGet();
      blobs.put(blobId, new AssetBlobRecord(
          blobId, 1L, "blob://default/" + repositoryId + "/" + path, null,
          "objects/" + blobId, null, "sha1", "sha256", "md5", 123L,
          "text/html", "test", "127.0.0.1", updatedAt, updatedAt, Map.of()));
      long assetId = assetIds.incrementAndGet();
      assets.put(key(repositoryId, path), new AssetRecord(
          assetId, repositoryId, null, blobId, RepositoryFormat.PYPI, path,
          PersistenceHashes.pathHash(path), path, kind, "text/html",
          123L, null, updatedAt, new HashMap<>(attributes)));
    }

    AssetRecord asset(long repositoryId, String path) {
      return assets.get(key(repositoryId, path));
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      return Optional.ofNullable(assets.get(key(repositoryId, path)));
    }

    @Override
    public Optional<AssetBlobRecord> findBlobById(long assetBlobId) {
      return Optional.ofNullable(blobs.get(assetBlobId));
    }

    @Override
    public int updateAssetAttributes(long assetId, Map<String, Object> attributes) {
      assets.replaceAll((ignored, prior) -> prior.id() == assetId
          ? new AssetRecord(
              prior.id(), prior.repositoryId(), prior.componentId(), prior.assetBlobId(),
              prior.format(), prior.path(), prior.pathHash(), prior.name(), prior.kind(),
              prior.contentType(), prior.size(), prior.lastDownloadedAt(), prior.lastUpdatedAt(),
              attributes)
          : prior);
      return 1;
    }

    private static String key(long repositoryId, String path) {
      return repositoryId + ":" + path;
    }
  }
}
