package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache.Loaded;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MavenGroupServiceTest {
  private static final MavenPathParser PARSER = new MavenPathParser();

  @Test
  void headMissStaysNotFoundWhenLaterProxyIsUnavailable() {
    MavenGroupService service = service();

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group(), markerJar(), true));
  }

  @Test
  void getMissStaysNotFoundWhenLaterProxyIsUnavailable() {
    MavenGroupService service = service();

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group(), markerJar(), false));
  }

  @Test
  void getMissesWhenOnlyGroupMemberHasBadUpstream() {
    MavenGroupService service = service();

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(failingOnlyGroup(), markerJar(), false));
  }

  @Test
  void gradleModuleMetadataMissStillProbesLaterMembersLikeNexus() throws IOException {
    MavenGroupService service = new MavenGroupService(new MissingHostedService(), new SuccessfulProxyService(),
        null, null, null, null);

    MavenResponse response = service.get(group(), gradleModuleMetadata(), false);

    assertEquals(200, response.status());
    assertEquals("module-ok", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void nestedGroupCycleDoesNotRecurseForeverAndContinuesLaterMembers() throws IOException {
    MavenGroupService service = new MavenGroupService(new MissingHostedService(), new SuccessfulProxyService(),
        null, null, null, null);
    List<RepositoryRuntime> rootMembers = new ArrayList<>();
    List<RepositoryRuntime> nestedMembers = new ArrayList<>();
    RepositoryRuntime root = group(10, "root", rootMembers);
    RepositoryRuntime nested = group(11, "nested", nestedMembers);
    rootMembers.add(nested);
    nestedMembers.add(root);
    nestedMembers.add(proxy());

    MavenResponse response = service.get(root, markerJar(), false);

    assertEquals(200, response.status());
    assertEquals("module-ok", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void cachedGroupArtifactServedBeforeMemberFanout() throws IOException {
    InMemorySharedCache shared = new InMemorySharedCache();
    AssetMetadataCache cache = new AssetMetadataCache(shared, true, 120, 5);
    MavenPath path = markerJar();
    warmCache(cache, 1L, path.path(), snapshot(100L, 1L, path.path(), blob(900L)));
    MavenGroupService service = new MavenGroupService(
        new MissingHostedService(),
        new FailingProxyService(),
        new EmptyAssetDao(),
        new FixedBlobStorageRegistry(new BytesBlobStorage("cached-group")),
        null,
        cache);

    MavenResponse response = service.get(group(), path, false);

    assertEquals(200, response.status());
    assertEquals("cached-group", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void cachedGroupArtifactExpiresUsingShortestMemberContentMaxAge() throws IOException {
    InMemorySharedCache shared = new InMemorySharedCache();
    AssetMetadataCache cache = new AssetMetadataCache(shared, true, 120, 5);
    MavenPath path = markerJar();
    warmCache(cache, 1L, path.path(),
        snapshot(100L, 1L, path.path(), blob(900L), Instant.now().minusSeconds(120)));
    MavenGroupService service = new MavenGroupService(
        new MissingHostedService(),
        new SuccessfulProxyService(),
        new EmptyAssetDao(),
        new FixedBlobStorageRegistry(new BytesBlobStorage("cached-group")),
        null,
        cache);

    MavenResponse response = service.get(group(1L, "maven-public", List.of(proxy(1, 1440))), path, false);

    assertEquals(200, response.status());
    assertEquals("module-ok", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void memberHitWritesGroupArtifactReferenceCache() {
    InMemorySharedCache shared = new InMemorySharedCache();
    AssetMetadataCache cache = new AssetMetadataCache(shared, true, 120, 5);
    MavenPath path = markerJar();
    warmCache(cache, 3L, path.path(), snapshot(101L, 3L, path.path(), blob(901L)));
    CapturingWriter writer = new CapturingWriter();
    MavenGroupService service = new MavenGroupService(
        new MissingHostedService(),
        new SuccessfulProxyService(),
        new EmptyAssetDao(),
        null,
        writer,
        cache);

    MavenResponse response = service.get(group(), path, true);

    assertEquals(200, response.status());
    assertEquals("maven-public:" + path.path() + ":3", writer.reference);
  }

  private static MavenGroupService service() {
    return new MavenGroupService(new MissingHostedService(), new FailingProxyService(),
        null, null, null, null);
  }

  private static MavenPath markerJar() {
    return PARSER.parsePath(
        "org/gradle/kotlin/org.gradle.kotlin.kotlin-dsl.gradle.plugin/4.2.1/"
            + "org.gradle.kotlin.kotlin-dsl.gradle.plugin-4.2.1.jar");
  }

  private static MavenPath gradleModuleMetadata() {
    return PARSER.parsePath(
        "io/sentry/sentry-logback/6.9.1/sentry-logback-6.9.1.module");
  }

  private static RepositoryRuntime group() {
    return group(1, "maven-public", List.of(hosted(), proxy()));
  }

  private static RepositoryRuntime group(long id, String name, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id,
        name,
        RepositoryFormat.MAVEN2,
        RepositoryType.GROUP,
        "maven2-group",
        true,
        1L,
        null,
        "MIXED",
        "PERMISSIVE",
        true,
        null,
        1440,
        1440,
        true,
        null,
        members);
  }

  private static RepositoryRuntime failingOnlyGroup() {
    return new RepositoryRuntime(
        11,
        "maven-public",
        RepositoryFormat.MAVEN2,
        RepositoryType.GROUP,
        "maven2-group",
        true,
        1L,
        null,
        "MIXED",
        "PERMISSIVE",
        true,
        null,
        1440,
        1440,
        true,
        null,
        List.of(proxy()));
  }

  private static RepositoryRuntime hosted() {
    return new RepositoryRuntime(
        2,
        "maven-releases",
        RepositoryFormat.MAVEN2,
        RepositoryType.HOSTED,
        "maven2-hosted",
        true,
        1L,
        null,
        "RELEASE",
        "PERMISSIVE",
        true,
        null,
        1440,
        1440,
        true, null, List.of());
  }

  private static RepositoryRuntime proxy() {
    return proxy(1440, 1440);
  }

  private static RepositoryRuntime proxy(int contentMaxAgeMinutes, int metadataMaxAgeMinutes) {
    return new RepositoryRuntime(
        3,
        "nexus-dev",
        RepositoryFormat.MAVEN2,
        RepositoryType.PROXY,
        "maven2-proxy",
        true,
        1L,
        null,
        "MIXED",
        "PERMISSIVE",
        true,
        "http://127.0.0.1:8080/repository/maven-public/",
        contentMaxAgeMinutes,
        metadataMaxAgeMinutes,
        true, null, List.of());
  }

  private static CachedAssetMetadata snapshot(long assetId, long repositoryId, String path, AssetBlobRecord blob) {
    return snapshot(assetId, repositoryId, path, blob, Instant.now().minusSeconds(60));
  }

  private static CachedAssetMetadata snapshot(
      long assetId,
      long repositoryId,
      String path,
      AssetBlobRecord blob,
      Instant lastUpdatedAt) {
    return CachedAssetMetadata.of(new AssetRecord(
        assetId,
        repositoryId,
        null,
        blob.id(),
        RepositoryFormat.MAVEN2,
        path,
        null,
        path.substring(path.lastIndexOf('/') + 1),
        "artifact",
        "application/java-archive",
        blob.size(),
        null,
        lastUpdatedAt,
        Map.of()),
        blob);
  }

  private static AssetBlobRecord blob(long id) {
    return new AssetBlobRecord(
        id,
        1L,
        "s3://bucket/objects/" + id,
        null,
        "objects/" + id,
        null,
        "sha1-" + id,
        "sha256-" + id,
        "md5-" + id,
        12L,
        "application/java-archive",
        "system",
        null,
        Instant.parse("2026-06-02T00:00:00Z"),
        Instant.parse("2026-06-02T00:00:00Z"),
        Map.of());
  }

  private static void warmCache(
      AssetMetadataCache cache, long repositoryId, String path, CachedAssetMetadata snapshot) {
    cache.find(repositoryId, path,
        () -> Optional.of(new Loaded(snapshot.toAssetRecord(), snapshot.toBlobRecord())));
  }

  private static class EmptyAssetDao extends AssetDaoAdapter {
    EmptyAssetDao() {
      super(null, null);
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      return Optional.empty();
    }
  }

  private static class CapturingWriter extends MavenAssetWriter {
    private String reference;

    CapturingWriter() {
      super(null, null, null, null, null);
    }

    @Override
    public CachedAssetMetadata referenceCachedAsset(
        RepositoryRuntime runtime,
        MavenPath path,
        CachedAssetMetadata source,
        String createdBy,
        String createdByIp) {
      reference = runtime.name() + ":" + path.path() + ":" + source.repositoryId();
      return source;
    }
  }

  private static class FixedBlobStorageRegistry extends BlobStorageRegistry {
    private final BlobStorage storage;

    FixedBlobStorageRegistry(BlobStorage storage) {
      super(null, null, null, null, 0L);
      this.storage = storage;
    }

    @Override
    public BlobStorage forBlobStoreId(long blobStoreId) {
      return storage;
    }
  }

  private static class BytesBlobStorage implements BlobStorage {
    private final byte[] bytes;

    BytesBlobStorage(String body) {
      this.bytes = body.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      return null;
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      return Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public boolean exists(BlobReference reference) {
      return true;
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
    }
  }

  private static class MissingHostedService extends MavenHostedService {
    MissingHostedService() {
      super(null, null, null, null, null, null, false);
    }

    @Override
    public MavenResponse get(RepositoryRuntime runtime, MavenPath path, boolean headOnly) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
  }

  private static class FailingProxyService extends MavenProxyService {
    FailingProxyService() {
      super(null, null, null, null, null, null, null);
    }

    @Override
    public MavenResponse get(RepositoryRuntime runtime, MavenPath path, boolean headOnly) {
      throw new MavenExceptions.BadUpstreamException("Upstream timed out");
    }
  }

  private static class SuccessfulProxyService extends MavenProxyService {
    SuccessfulProxyService() {
      super(null, null, null, null, null, null, null);
    }

    @Override
    public MavenResponse get(RepositoryRuntime runtime, MavenPath path, boolean headOnly) {
      if (headOnly) {
        return MavenResponse.noBody(200, 9, "application/octet-stream", "etag", Instant.EPOCH);
      }
      return MavenResponse.ok(
          new ByteArrayInputStream("module-ok".getBytes(StandardCharsets.UTF_8)),
          9,
          "application/octet-stream",
          "etag",
          Instant.EPOCH);
    }
  }
}
