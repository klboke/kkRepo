package com.github.klboke.kkrepo.server.npm;

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
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
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

class NpmGroupPackumentCacheTest {
  private static final NpmPackageId PACKAGE_ID = NpmPackageId.parse("@example/demo");
  private static final Instant NOW = Instant.parse("2026-06-04T08:00:00Z");

  @Test
  void freshPackageRootAssetIsReusable() {
    Fixture fixture = fixture(true);
    RepositoryRuntime group = runtime(99L, "npm-group", 60);
    fixture.assets.putAsset(group.id(), PACKAGE_ID.id(),
        fixture.cache.freshAttributes(group, NOW), NOW);

    assertTrue(fixture.cache.findFresh(group, PACKAGE_ID, NOW.plusSeconds(30)).isPresent());
  }

  @Test
  void freshInstallV1PackageRootAssetIsReusableSeparately() {
    Fixture fixture = fixture(true);
    RepositoryRuntime group = runtime(99L, "npm-group", 60);
    fixture.assets.putAsset(group.id(), PACKAGE_ID, NpmPackumentVariant.INSTALL_V1,
        fixture.cache.freshAttributes(group, NOW, NpmPackumentVariant.INSTALL_V1), NOW);

    assertTrue(fixture.cache.findFresh(
        group, PACKAGE_ID, NpmPackumentVariant.INSTALL_V1, NOW.plusSeconds(30)).isPresent());
    assertTrue(fixture.cache.findFresh(group, PACKAGE_ID, NOW.plusSeconds(30)).isEmpty());
  }

  @Test
  void missingCacheInfoIsStaleLikeNexus() {
    Fixture fixture = fixture(true);
    RepositoryRuntime group = runtime(99L, "npm-group", 60);
    fixture.assets.putAsset(group.id(), PACKAGE_ID.id(), Map.of("packageId", PACKAGE_ID.id()), NOW);

    assertTrue(fixture.cache.findFresh(group, PACKAGE_ID, NOW).isEmpty());
  }

  @Test
  void metadataMaxAgeExpiresPackageRootAsset() {
    Fixture fixture = fixture(true);
    RepositoryRuntime group = runtime(99L, "npm-group", 1);
    fixture.assets.putAsset(group.id(), PACKAGE_ID.id(),
        fixture.cache.freshAttributes(group, NOW), NOW);

    assertTrue(fixture.cache.findFresh(group, PACKAGE_ID, NOW.plusSeconds(59)).isPresent());
    assertTrue(fixture.cache.findFresh(group, PACKAGE_ID, NOW.plusSeconds(60)).isEmpty());
  }

  @Test
  void memberMetadataMaxAgeExpiresPackageRootAsset() {
    Fixture fixture = fixture(true);
    RepositoryRuntime proxy = proxyRuntime(10L, "npm-proxy", 1);
    RepositoryRuntime group = runtime(99L, "npm-group", 1440, List.of(proxy));
    fixture.assets.putAsset(group.id(), PACKAGE_ID.id(),
        fixture.cache.freshAttributes(group, NOW), NOW);

    assertTrue(fixture.cache.findFresh(group, PACKAGE_ID, NOW.plusSeconds(59)).isPresent());
    assertTrue(fixture.cache.findFresh(group, PACKAGE_ID, NOW.plusSeconds(60)).isEmpty());
  }

  @Test
  void memberPackageInvalidationMarksMatchingGroupAssetsAndAncestorsInvalidated() {
    Fixture fixture = fixture(true);
    RepositoryRuntime inner = runtime(20L, "inner", 60);
    RepositoryRuntime outer = runtime(30L, "outer", 60);
    fixture.repositories.putGroupsContaining(10L, List.of(record(inner.id(), inner.name())));
    fixture.repositories.putGroupsContaining(inner.id(), List.of(record(outer.id(), outer.name())));
    fixture.assets.putAsset(inner.id(), PACKAGE_ID.id(), fixture.cache.freshAttributes(inner, NOW), NOW);
    fixture.assets.putAsset(outer.id(), PACKAGE_ID.id(), fixture.cache.freshAttributes(outer, NOW), NOW);
    fixture.assets.putAsset(inner.id(), PACKAGE_ID, NpmPackumentVariant.INSTALL_V1,
        fixture.cache.freshAttributes(inner, NOW, NpmPackumentVariant.INSTALL_V1), NOW);
    fixture.assets.putAsset(outer.id(), PACKAGE_ID, NpmPackumentVariant.INSTALL_V1,
        fixture.cache.freshAttributes(outer, NOW, NpmPackumentVariant.INSTALL_V1), NOW);
    fixture.assets.putAsset(outer.id(), "other", fixture.cache.freshAttributes(outer, NOW), NOW);

    fixture.cache.invalidateMemberPackageAfterCommit(10L, PACKAGE_ID.id());

    assertTrue(fixture.cache.findFresh(inner, PACKAGE_ID, NOW.plusSeconds(1)).isEmpty());
    assertTrue(fixture.cache.findFresh(outer, PACKAGE_ID, NOW.plusSeconds(1)).isEmpty());
    assertTrue(fixture.cache.findFresh(
        inner, PACKAGE_ID, NpmPackumentVariant.INSTALL_V1, NOW.plusSeconds(1)).isEmpty());
    assertTrue(fixture.cache.findFresh(
        outer, PACKAGE_ID, NpmPackumentVariant.INSTALL_V1, NOW.plusSeconds(1)).isEmpty());
    assertTrue(NexusLikeCacheInfo.fromAttributes(
            fixture.assets.asset(outer.id(), PACKAGE_ID.id()).attributes())
        .orElseThrow()
        .invalidated());
    assertTrue(NexusLikeCacheInfo.fromAttributes(
            fixture.assets.asset(outer.id(), NpmPackumentVariant.INSTALL_V1.cachePath(PACKAGE_ID)).attributes())
        .orElseThrow()
        .invalidated());
    assertFalse(NexusLikeCacheInfo.fromAttributes(
            fixture.assets.asset(outer.id(), "other").attributes())
        .orElseThrow()
        .invalidated());
  }

  @Test
  void groupInvalidationExpiresGroupAndAncestorTokens() {
    Fixture fixture = fixture(true);
    RepositoryRuntime inner = runtime(20L, "inner", 60);
    RepositoryRuntime outer = runtime(30L, "outer", 60);
    fixture.repositories.putGroupsContaining(inner.id(), List.of(record(outer.id(), outer.name())));
    fixture.assets.putAsset(inner.id(), PACKAGE_ID.id(), fixture.cache.freshAttributes(inner, NOW), NOW);
    fixture.assets.putAsset(outer.id(), PACKAGE_ID.id(), fixture.cache.freshAttributes(outer, NOW), NOW);

    fixture.cache.invalidateGroupAfterCommit(inner.id());

    assertTrue(fixture.cache.findFresh(inner, PACKAGE_ID, NOW.plusSeconds(1)).isEmpty());
    assertTrue(fixture.cache.findFresh(outer, PACKAGE_ID, NOW.plusSeconds(1)).isEmpty());
  }

  @Test
  void disabledCacheDoesNotServeOrInvalidateAssets() {
    Fixture fixture = fixture(false);
    RepositoryRuntime group = runtime(99L, "npm-group", 60);
    fixture.assets.putAsset(group.id(), PACKAGE_ID.id(),
        fixture.cache.freshAttributes(group, NOW), NOW);

    fixture.cache.invalidateMemberPackageAfterCommit(10L, PACKAGE_ID.id());

    assertTrue(fixture.cache.findFresh(group, PACKAGE_ID, NOW).isEmpty());
    assertEquals(Map.of("packageId", PACKAGE_ID.id()),
        fixture.assets.asset(group.id(), PACKAGE_ID.id()).attributes());
  }

  private static Fixture fixture(boolean enabled) {
    StubRepositoryDao repositories = new StubRepositoryDao();
    StubAssetDao assets = new StubAssetDao();
    AssetMetadataCache assetMetadataCache = new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0);
    NexusLikeCacheController controller = new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    NpmGroupPackumentCache cache =
        new NpmGroupPackumentCache(repositories, assets, assetMetadataCache, controller, enabled);
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
        id, name, RepositoryFormat.NPM, RepositoryType.GROUP, "npm-group",
        true, 1L, "ALLOW", null, null, true, null, null, metadataMaxAgeMinutes,
        null, null, members);
  }

  private static RepositoryRuntime proxyRuntime(long id, String name, int metadataMaxAgeMinutes) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.NPM, RepositoryType.PROXY, "npm-proxy",
        true, 1L, "ALLOW", null, null, true, "http://example.invalid/registry/", null,
        metadataMaxAgeMinutes, null, null, List.of());
  }

  private static RepositoryRecord record(long id, String name) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.NPM, RepositoryType.GROUP,
        "npm-group", true, 1L, null, null, null, null, "ALLOW", false, Map.of());
  }

  private record Fixture(
      NpmGroupPackumentCache cache,
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

    void putAsset(long repositoryId, String path, Map<String, Object> attributes, Instant updatedAt) {
      putAsset(repositoryId, path, path, "package-root", attributes, updatedAt);
    }

    void putAsset(
        long repositoryId,
        NpmPackageId packageId,
        NpmPackumentVariant variant,
        Map<String, Object> attributes,
        Instant updatedAt) {
      putAsset(repositoryId, variant.cachePath(packageId), packageId.id(), variant.assetKind(), attributes, updatedAt);
    }

    void putAsset(
        long repositoryId,
        String path,
        String packageId,
        String kind,
        Map<String, Object> attributes,
        Instant updatedAt) {
      long blobId = blobIds.incrementAndGet();
      blobs.put(blobId, new AssetBlobRecord(
          blobId, 1L, "blob://default/" + repositoryId + "/" + path, null,
          "objects/" + blobId, null, "sha1", "sha256", "md5", 123L,
          NpmResponseSupport.JSON, "test", "127.0.0.1", updatedAt, updatedAt, Map.of()));
      long assetId = assetIds.incrementAndGet();
      Map<String, Object> assetAttrs = new HashMap<>();
      assetAttrs.put("packageId", packageId);
      assetAttrs.putAll(attributes);
      assets.put(key(repositoryId, path), new AssetRecord(
          assetId, repositoryId, null, blobId, RepositoryFormat.NPM, path,
          PersistenceHashes.pathHash(path), path, kind, NpmResponseSupport.JSON,
          123L, null, updatedAt, assetAttrs));
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
