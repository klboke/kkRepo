package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.protocol.npm.NpmPath;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.BrowseNodeDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.ComponentDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class NpmGroupServiceCacheTest {

  @Test
  void mergedPackageRootIsServedFromGroupBlobCacheOnRepeatedReads() throws IOException {
    Fixture fixture = fixture();
    fixture.repositories.putGroupsContaining(101L, List.of(record(999L, "npm-group")));
    NpmGroupService groupService = fixture.groupService();

    RepositoryRuntime member = runtime(101L, "npm-hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime group = runtime(999L, "npm-group", RepositoryType.GROUP, List.of(member));
    NpmPackageId packageId = NpmPackageId.parse("@example/demo");
    NpmPath path = new NpmPath(NpmPath.Kind.PACKAGE_ROOT, packageId.id(), packageId,
        null, null, null, null);

    MavenResponse first = groupService.get(group, path, "http://nexus/group", false);
    MavenResponse second = groupService.get(group, path, "http://nexus/group", false);

    assertEquals(1, fixture.hosted.calls.get(), "second read must skip member fan-out");
    assertEquals(1, fixture.storage.puts.get(), "merged packument should be written once to blob storage");
    assertTrue(fixture.assets.findAssetByPath(group.id(), packageId.id()).isPresent(),
        "group repository must own a cached package-root asset");
    String firstBody = body(first);
    String secondBody = body(second);
    assertEquals(firstBody, secondBody);
    assertTrue(firstBody.contains("\"http://nexus/group/" + packageId.tarballPath("demo-1.0.0.tgz") + "\""),
        "tarball URL must be rewritten with the request base URL: " + firstBody);
  }

  @Test
  void cachedBlobIsSharedAcrossBaseUrlsAndResponseUrlIsRewritten() throws IOException {
    Fixture fixture = fixture();
    NpmGroupService groupService = fixture.groupService();

    RepositoryRuntime member = runtime(11L, "npm-hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime group = runtime(12L, "npm-group", RepositoryType.GROUP, List.of(member));
    NpmPackageId packageId = NpmPackageId.parse("@example/demo");
    NpmPath path = new NpmPath(NpmPath.Kind.PACKAGE_ROOT, packageId.id(), packageId,
        null, null, null, null);

    String internal = body(groupService.get(group, path, "http://internal/group", false));
    String external = body(groupService.get(group, path, "http://external/group", false));
    groupService.get(group, path, "http://internal/group", false);

    assertEquals(1, fixture.hosted.calls.get(),
        "base URL changes must not create separate packument cache entries");
    assertTrue(internal.contains("\"http://internal/group/" + packageId.tarballPath("demo-1.0.0.tgz") + "\""));
    assertTrue(external.contains("\"http://external/group/" + packageId.tarballPath("demo-1.0.0.tgz") + "\""));
  }

  @Test
  void installV1PackageRootIsAbbreviatedAndCachedSeparately() throws IOException {
    Fixture fixture = fixture();
    NpmGroupService groupService = fixture.groupService();

    RepositoryRuntime member = runtime(11L, "npm-hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime group = runtime(12L, "npm-group", RepositoryType.GROUP, List.of(member));
    NpmPackageId packageId = NpmPackageId.parse("@example/demo");
    NpmPath path = new NpmPath(NpmPath.Kind.PACKAGE_ROOT, packageId.id(), packageId,
        null, null, null, null);

    String installBody = body(groupService.get(
        group, path, "http://nexus/group", false, NpmPackumentVariant.INSTALL_V1));
    String installBodySecond = body(groupService.get(
        group, path, "http://nexus/group", false, NpmPackumentVariant.INSTALL_V1));
    assertTrue(fixture.assets.findAssetByPath(group.id(), packageId.id()).isEmpty(),
        "install-v1 should no longer build an intermediate full group packument");
    String fullBody = body(groupService.get(group, path, "http://nexus/group", false));

    assertEquals(2, fixture.hosted.calls.get(),
        "install-v1 and full variants should each fan out once without an intermediate full build");
    assertEquals(2, fixture.storage.puts.get(),
        "first install-v1 read should store one full blob and one abbreviated blob");
    assertTrue(fixture.assets.findAssetByPath(group.id(), packageId.id()).isPresent());
    assertTrue(fixture.assets.findAssetByPath(
        group.id(), NpmPackumentVariant.INSTALL_V1.cachePath(packageId)).isPresent());
    assertEquals(installBody, installBodySecond);
    assertFalse(installBody.contains("\"readme\""), installBody);
    assertFalse(installBody.contains("\"scripts\""), installBody);
    assertTrue(installBody.contains("\"hasInstallScript\":true"), installBody);
    assertTrue(installBody.contains("\"dependencies\""), installBody);
    assertTrue(installBody.contains("\"http://nexus/group/" + packageId.tarballPath("demo-1.0.0.tgz") + "\""));
    assertTrue(fullBody.contains("\"readme\""), fullBody);
    assertTrue(fullBody.contains("\"scripts\""), fullBody);
  }

  @Test
  void memberPackageInvalidationRebuildsCachedBlob() {
    Fixture fixture = fixture();
    fixture.repositories.putGroupsContaining(101L, List.of(record(999L, "npm-group")));
    NpmGroupService groupService = fixture.groupService();

    RepositoryRuntime member = runtime(101L, "npm-hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime group = runtime(999L, "npm-group", RepositoryType.GROUP, List.of(member));
    NpmPackageId packageId = NpmPackageId.parse("@example/demo");
    NpmPath path = new NpmPath(NpmPath.Kind.PACKAGE_ROOT, packageId.id(), packageId,
        null, null, null, null);

    groupService.get(group, path, "http://nexus/group", false);
    fixture.packumentCache.invalidateMemberPackageAfterCommit(member.id(), packageId.id());
    groupService.get(group, path, "http://nexus/group", false);

    assertEquals(2, fixture.hosted.calls.get(), "invalidated package root must fan out again");
    assertEquals(2, fixture.storage.puts.get(), "rebuilt package root must replace the cached blob");
  }

  @Test
  void tarballMissesWhenOnlyGroupMemberHasBadUpstream() {
    Fixture fixture = fixture();
    NpmGroupService groupService = fixture.groupServiceWithProxy(new FailingNpmProxyService());

    NpmPackageId packageId = NpmPackageId.parse("@example/forge-tokens");
    NpmPath path = new NpmPath(NpmPath.Kind.TARBALL, packageId.tarballPath("forge-tokens-0.2.0.tgz"),
        packageId, null, "forge-tokens-0.2.0.tgz", null, null);
    RepositoryRuntime proxy = runtime(101L, "npm-proxy", RepositoryType.PROXY, List.of());
    RepositoryRuntime group = runtime(999L, "npm-group", RepositoryType.GROUP, List.of(proxy));

    NpmExceptions.NpmNotFoundException error = assertThrows(
        NpmExceptions.NpmNotFoundException.class,
        () -> groupService.get(group, path, "http://nexus/group", false));

    assertEquals(packageId.tarballPath("forge-tokens-0.2.0.tgz"), error.getMessage());
  }

  @Test
  void groupPackumentCacheExpiresAtNextMinimumReleaseAgeTransition() {
    Fixture fixture = fixture();
    RecordingNpmProxyService proxyService = new RecordingNpmProxyService();
    NpmGroupService groupService = fixture.groupServiceWithProxy(proxyService);
    NpmPackageId packageId = NpmPackageId.parse("@example/demo");
    NpmPath path = new NpmPath(
        NpmPath.Kind.PACKAGE_ROOT, packageId.id(), packageId, null, null, null, null);
    RepositoryRuntime proxy = runtime(
        101L, "npm-proxy", RepositoryType.PROXY, List.of(), 60);
    RepositoryRuntime group = runtime(
        999L, "npm-group", RepositoryType.GROUP, List.of(proxy));

    groupService.get(group, path, "http://nexus/group", false);
    groupService.get(group, path, "http://nexus/group", false);

    assertEquals(1, proxyService.calls.get(),
        "the group cache may be reused before the member's next maturity transition");
    assertEquals(1, fixture.storage.puts.get(),
        "the filtered group packument should be persisted with an exact policy deadline");
    assertTrue(fixture.packumentCache.findFresh(
        group,
        packageId,
        NpmPackumentVariant.FULL,
        proxyService.nextTransition.minusNanos(1)).isPresent());
    assertTrue(fixture.packumentCache.findFresh(
        group,
        packageId,
        NpmPackumentVariant.FULL,
        proxyService.nextTransition).isEmpty(),
        "the cached group packument must expire exactly when a version becomes mature");
  }

  @Test
  void groupReturnsReleaseAgeDenialWhenNoMemberCanServeTarball() {
    Fixture fixture = fixture();
    NpmGroupService groupService = fixture.groupServiceWithProxy(new ReleaseAgeNpmProxyService());
    NpmPackageId packageId = NpmPackageId.parse("@example/demo");
    NpmPath path = new NpmPath(
        NpmPath.Kind.TARBALL,
        packageId.tarballPath("demo-2.0.0.tgz"),
        packageId,
        null,
        "demo-2.0.0.tgz",
        null,
        null);
    RepositoryRuntime proxy = runtime(
        101L, "npm-proxy", RepositoryType.PROXY, List.of(), 60);
    RepositoryRuntime group = runtime(
        999L, "npm-group", RepositoryType.GROUP, List.of(proxy));

    NpmExceptions.ReleaseAgeDenied denied = assertThrows(
        NpmExceptions.ReleaseAgeDenied.class,
        () -> groupService.get(group, path, "http://nexus/group", false));

    assertEquals("release is too new", denied.getMessage());
  }

  @Test
  void packageAndDistTagHeadResponsesUseMergedPackument() throws Exception {
    Fixture fixture = fixture();
    NpmGroupService groupService = fixture.groupService();
    RepositoryRuntime member = runtime(101L, "npm-hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime group = runtime(999L, "npm-group", RepositoryType.GROUP, List.of(member));
    NpmPackageId packageId = NpmPackageId.parse("@example/demo");
    NpmPath packagePath = new NpmPath(
        NpmPath.Kind.PACKAGE_ROOT, packageId.id(), packageId, null, null, null, null);
    NpmPath tagsPath = new NpmPath(
        NpmPath.Kind.DIST_TAGS, packageId.id(), packageId, null, null, null, null);

    MavenResponse packageHead = groupService.get(group, packagePath, "base", true);
    MavenResponse tagsHead = groupService.get(group, tagsPath, "base", true);
    MavenResponse tagsBody = groupService.get(group, tagsPath, "base", false);

    assertEquals(null, packageHead.body());
    assertEquals(null, tagsHead.body());
    assertTrue(body(tagsBody).contains("{}"));
  }

  @Test
  void cachedTarballMemberDenialIsEvictedBeforeGroupRetries() {
    Fixture fixture = fixture();
    GroupMemberAssetCache memberCache = mock(GroupMemberAssetCache.class);
    NpmProxyService proxy = new ReleaseAgeNpmProxyService();
    NpmGroupService groupService = new NpmGroupService(
        fixture.hosted, proxy, fixture.mapper, fixture.packumentCache, memberCache,
        fixture.registry, fixture.writer);
    NpmPackageId packageId = NpmPackageId.parse("@example/demo");
    String tarballName = "demo-2.0.0.tgz";
    String cachePath = packageId.tarballPath(tarballName);
    NpmPath path = new NpmPath(
        NpmPath.Kind.TARBALL, cachePath, packageId, null, tarballName, null, null);
    RepositoryRuntime proxyRuntime = runtime(
        101L, "npm-proxy", RepositoryType.PROXY, List.of(), 60);
    RepositoryRuntime group = runtime(
        999L, "npm-group", RepositoryType.GROUP, List.of(proxyRuntime));
    when(memberCache.get(group, cachePath, NexusCacheType.CONTENT))
        .thenReturn(Optional.of(proxyRuntime.id()));

    assertThrows(NpmExceptions.ReleaseAgeDenied.class,
        () -> groupService.get(group, path, "base", false));

    verify(memberCache).evict(group, cachePath, NexusCacheType.CONTENT);
  }

  @Test
  void nestedGroupsAndProxyInstallVariantUseVariantAwareDispatch() throws Exception {
    Fixture fixture = fixture();
    RecordingNpmProxyService proxyService = new RecordingNpmProxyService();
    NpmGroupService groupService = fixture.groupServiceWithProxy(proxyService);
    NpmPackageId packageId = NpmPackageId.parse("@example/demo");
    NpmPath path = new NpmPath(
        NpmPath.Kind.PACKAGE_ROOT, packageId.id(), packageId, null, null, null, null);
    RepositoryRuntime proxy = runtime(101L, "npm-proxy", RepositoryType.PROXY, List.of(), 60);
    RepositoryRuntime proxyGroup = runtime(
        999L, "proxy-group", RepositoryType.GROUP, List.of(proxy));

    assertTrue(body(groupService.get(
        proxyGroup, path, "base", false, NpmPackumentVariant.INSTALL_V1))
        .contains("1.0.0"));

    RepositoryRuntime hosted = runtime(201L, "npm-hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime inner = runtime(202L, "inner", RepositoryType.GROUP, List.of(hosted));
    RepositoryRuntime outer = runtime(203L, "outer", RepositoryType.GROUP, List.of(inner));
    assertTrue(body(groupService.get(outer, path, "base", false)).contains("1.0.0"));
    assertTrue(body(groupService.get(
        outer, path, "base", false, NpmPackumentVariant.INSTALL_V1)).contains("1.0.0"));
  }

  @Test
  void groupUsesEarliestPolicyTransitionAcrossProxyMembers() {
    Fixture fixture = fixture();
    PerMemberTransitionProxyService proxy = new PerMemberTransitionProxyService();
    NpmGroupService groupService = fixture.groupServiceWithProxy(proxy);
    NpmPackageId packageId = NpmPackageId.parse("@example/demo");
    NpmPath path = new NpmPath(
        NpmPath.Kind.PACKAGE_ROOT, packageId.id(), packageId, null, null, null, null);
    RepositoryRuntime first = runtime(101L, "first", RepositoryType.PROXY, List.of(), 60);
    RepositoryRuntime second = runtime(102L, "second", RepositoryType.PROXY, List.of(), 60);
    RepositoryRuntime later = runtime(103L, "later", RepositoryType.PROXY, List.of(), 60);
    RepositoryRuntime noDeadline = runtime(
        104L, "no-deadline", RepositoryType.PROXY, List.of(), 60);
    RepositoryRuntime group = runtime(
        999L, "npm-group", RepositoryType.GROUP,
        List.of(first, second, later, noDeadline));

    MavenResponse response = groupService.get(group, path, "base", false);

    assertEquals(
        proxy.base.plusSeconds(300),
        response.internalAttribute(NpmProxyService.POLICY_VALID_UNTIL_CONTEXT));
  }

  private static Fixture fixture() {
    ObjectMapper mapper = new ObjectMapper();
    StubRepositoryDao repositories = new StubRepositoryDao();
    InMemoryAssetDao assets = new InMemoryAssetDao();
    InMemoryBlobStorage storage = new InMemoryBlobStorage();
    AssetMetadataCache assetMetadataCache = new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0);
    NexusLikeCacheController cacheController = new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    NpmGroupPackumentCache packumentCache =
        new NpmGroupPackumentCache(repositories, assets, assetMetadataCache, cacheController, true);
    NpmAssetWriter writer = new NpmAssetWriter(
        assets,
        new NoopComponentDao(),
        new NoopBrowseNodeDao(),
        null,
        assetMetadataCache,
        packumentCache,
        null);
    RecordingNpmHostedService hosted = new RecordingNpmHostedService();
    BlobStorageRegistry registry = new SingleBlobStorageRegistry(storage);
    return new Fixture(mapper, repositories, assets, storage, packumentCache, writer, hosted, registry);
  }

  private static String body(MavenResponse response) throws IOException {
    try (InputStream in = response.body()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static RepositoryRuntime runtime(
      long id, String name, RepositoryType type, List<RepositoryRuntime> members) {
    return runtime(id, name, type, members, 0);
  }

  private static RepositoryRuntime runtime(
      long id,
      String name,
      RepositoryType type,
      List<RepositoryRuntime> members,
      int minimumReleaseAgeMinutes) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.NPM, type, "npm-" + type.name().toLowerCase(),
        true, 1L, "ALLOW", null, null, true, null, null, 60,
        null, null, members, minimumReleaseAgeMinutes);
  }

  private static RepositoryRecord record(long id, String name) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.NPM, RepositoryType.GROUP,
        "npm-group", true, 1L, null, null, null, null, "ALLOW", false, Map.of());
  }

  private record Fixture(
      ObjectMapper mapper,
      StubRepositoryDao repositories,
      InMemoryAssetDao assets,
      InMemoryBlobStorage storage,
      NpmGroupPackumentCache packumentCache,
      NpmAssetWriter writer,
      RecordingNpmHostedService hosted,
      BlobStorageRegistry registry) {
    NpmGroupService groupService() {
      return new NpmGroupService(hosted, null, mapper, packumentCache, null, registry, writer);
    }

    NpmGroupService groupServiceWithProxy(NpmProxyService proxy) {
      return new NpmGroupService(hosted, proxy, mapper, packumentCache, null, registry, writer);
    }
  }

  private static class RecordingNpmHostedService extends NpmHostedService {
    final AtomicInteger calls = new AtomicInteger();

    RecordingNpmHostedService() {
      super(null, null, null, new ObjectMapper(), null);
    }

    @Override
    public MavenResponse get(
        RepositoryRuntime runtime, NpmPath path, String repositoryBaseUrl, boolean headOnly) {
      return get(runtime, path, repositoryBaseUrl, headOnly, NpmPackumentVariant.FULL);
    }

    @Override
    public MavenResponse get(
        RepositoryRuntime runtime,
        NpmPath path,
        String repositoryBaseUrl,
        boolean headOnly,
        NpmPackumentVariant variant) {
      calls.incrementAndGet();
      String body = """
          {
            "name": "@example/demo",
            "_id": "@example/demo",
            "readme": "large readme",
            "versions": {
              "1.0.0": {
                "name": "@example/demo",
                "version": "1.0.0",
                "readme": "large version readme",
                "scripts": {
                  "install": "node install.js"
                },
                "dependencies": {
                  "left-pad": "^1.3.0"
                },
                "dist": {
                  "tarball": "https://upstream.example/demo-1.0.0.tgz",
                  "shasum": "0000000000000000000000000000000000000000"
                }
              }
            }
          }""";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
          NpmResponseSupport.JSON, null, Instant.now());
    }
  }

  private static class FailingNpmProxyService extends NpmProxyService {
    FailingNpmProxyService() {
      super(null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public MavenResponse get(
        RepositoryRuntime runtime, NpmPath path, String repositoryBaseUrl, boolean headOnly) {
      throw new NpmExceptions.BadUpstreamException("Upstream returned 502");
    }
  }

  private static class RecordingNpmProxyService extends NpmProxyService {
    final AtomicInteger calls = new AtomicInteger();
    final Instant nextTransition = Instant.now().plusSeconds(600);

    RecordingNpmProxyService() {
      super(null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public MavenResponse get(
        RepositoryRuntime runtime,
        NpmPath path,
        String repositoryBaseUrl,
        boolean headOnly) {
      calls.incrementAndGet();
      byte[] body = """
          {"name":"@example/demo","dist-tags":{"latest":"1.0.0"},"versions":{
            "1.0.0":{"name":"@example/demo","version":"1.0.0",
              "dist":{"tarball":"https://upstream.example/demo-1.0.0.tgz"}}
          }}
          """.getBytes(StandardCharsets.UTF_8);
      return MavenResponse.ok(new ByteArrayInputStream(body), body.length,
          NpmResponseSupport.JSON, null, Instant.now())
          .withInternalAttribute(
              NpmProxyService.POLICY_VALID_UNTIL_CONTEXT, nextTransition);
    }

    @Override
    public MavenResponse get(
        RepositoryRuntime runtime,
        NpmPath path,
        String repositoryBaseUrl,
        boolean headOnly,
        NpmPackumentVariant variant) {
      return get(runtime, path, repositoryBaseUrl, headOnly);
    }
  }

  private static class PerMemberTransitionProxyService extends NpmProxyService {
    final Instant base = Instant.parse("2026-07-19T12:00:00Z");

    PerMemberTransitionProxyService() {
      super(null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public MavenResponse get(
        RepositoryRuntime runtime, NpmPath path, String repositoryBaseUrl, boolean headOnly) {
      byte[] bytes = "{\"name\":\"@example/demo\",\"versions\":{}}"
          .getBytes(StandardCharsets.UTF_8);
      Object transition = switch ((int) runtime.id()) {
        case 101 -> base.plusSeconds(600);
        case 102 -> base.plusSeconds(300);
        case 103 -> base.plusSeconds(900);
        default -> "not-an-instant";
      };
      return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
              NpmResponseSupport.JSON, null, base)
          .withInternalAttribute(NpmProxyService.POLICY_VALID_UNTIL_CONTEXT, transition);
    }
  }

  private static class ReleaseAgeNpmProxyService extends NpmProxyService {
    ReleaseAgeNpmProxyService() {
      super(null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public MavenResponse get(
        RepositoryRuntime runtime,
        NpmPath path,
        String repositoryBaseUrl,
        boolean headOnly) {
      throw new NpmExceptions.ReleaseAgeDenied("release is too new");
    }
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

  private static class InMemoryAssetDao extends AssetDaoAdapter {
    private final AtomicLong blobIds = new AtomicLong(200);
    private final AtomicLong assetIds = new AtomicLong(100);
    private final Map<Long, AssetBlobRecord> blobs = new ConcurrentHashMap<>();
    private final Map<String, AssetRecord> assets = new ConcurrentHashMap<>();

    InMemoryAssetDao() {
      super(null, null);
    }

    @Override
    public Optional<AssetBlobRecord> findReusableBlobBySha256(long blobStoreId, String sha256, long size) {
      return Optional.empty();
    }

    @Override
    public long insertBlob(AssetBlobRecord record) {
      long id = blobIds.incrementAndGet();
      blobs.put(id, withId(record, id));
      return id;
    }

    @Override
    public Optional<AssetBlobRecord> findBlobById(long assetBlobId) {
      return Optional.ofNullable(blobs.get(assetBlobId));
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      return Optional.ofNullable(assets.get(key(repositoryId, path)));
    }

    @Override
    public OptionalLong tryInsertAsset(AssetRecord record) {
      String key = key(record.repositoryId(), record.path());
      if (assets.containsKey(key)) {
        return OptionalLong.empty();
      }
      long id = assetIds.incrementAndGet();
      assets.put(key, withId(record, id));
      return OptionalLong.of(id);
    }

    @Override
    public int updateAssetBlobBindingAndMetadata(long assetId, Long componentId, long assetBlobId,
        String kind, String contentType, long size, Instant lastUpdatedAt, Map<String, Object> attributes) {
      assets.replaceAll((ignored, prior) -> prior.id() == assetId
          ? new AssetRecord(
              prior.id(), prior.repositoryId(), componentId, assetBlobId, prior.format(),
              prior.path(), prior.pathHash(), prior.name(), kind, contentType, size,
              prior.lastDownloadedAt(), lastUpdatedAt, attributes)
          : prior);
      return 1;
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

    @Override
    public int markBlobDeletedIfUnreferenced(long assetBlobId, String reason) {
      return 1;
    }

    private static AssetBlobRecord withId(AssetBlobRecord record, long id) {
      return new AssetBlobRecord(
          id, record.blobStoreId(), record.blobRef(), record.blobRefHash(),
          record.objectKey(), record.objectKeyHash(), record.sha1(), record.sha256(),
          record.md5(), record.size(), record.contentType(), record.createdBy(),
          record.createdByIp(), record.blobCreatedAt(), record.blobUpdatedAt(),
          record.attributes());
    }

    private static AssetRecord withId(AssetRecord record, long id) {
      return new AssetRecord(
          id, record.repositoryId(), record.componentId(), record.assetBlobId(),
          record.format(), record.path(), PersistenceHashes.pathHash(record.path()), record.name(),
          record.kind(), record.contentType(), record.size(), record.lastDownloadedAt(),
          record.lastUpdatedAt(), record.attributes());
    }

    private static String key(long repositoryId, String path) {
      return repositoryId + ":" + path;
    }
  }

  private static class InMemoryBlobStorage implements BlobStorage {
    private final AtomicInteger puts = new AtomicInteger();
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      try {
        String objectKey = "objects/" + puts.incrementAndGet();
        objects.put(objectKey, content.readAllBytes());
        return new BlobReference("default", objectKey, sha256, size);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      byte[] bytes = objects.get(reference.objectKey());
      return bytes == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public boolean exists(BlobReference reference) {
      return objects.containsKey(reference.objectKey());
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
      objects.remove(reference.objectKey());
    }
  }

  private static class SingleBlobStorageRegistry extends BlobStorageRegistry {
    private final BlobStorage storage;

    SingleBlobStorageRegistry(BlobStorage storage) {
      super(null, null, null, null, false);
      this.storage = storage;
    }

    @Override
    public BlobStorage forBlobStoreId(long blobStoreId) {
      return storage;
    }
  }

  private static class NoopBrowseNodeDao extends BrowseNodeDaoAdapter {
    NoopBrowseNodeDao() {
      super(null);
    }

    @Override
    public void upsertPathAncestors(long repositoryId, String fullPath, Long assetId, Long componentId) {
    }
  }

  private static class NoopComponentDao extends ComponentDaoAdapter {
    NoopComponentDao() {
      super(null, null);
    }
  }
}
