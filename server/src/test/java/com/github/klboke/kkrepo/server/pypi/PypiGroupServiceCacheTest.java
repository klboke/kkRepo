package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
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

class PypiGroupServiceCacheTest {

  @Test
  void projectIndexIsServedFromGroupBlobCacheOnRepeatedReads() throws IOException {
    Fixture fixture = fixture();
    fixture.repositories.putGroupsContaining(101L, List.of(record(999L, "pypi-group")));
    PypiGroupService groupService = fixture.groupService();

    RepositoryRuntime member = runtime(101L, "pypi-hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime group = runtime(999L, "pypi-group", RepositoryType.GROUP, List.of(member));

    PypiResponse first = groupService.getIndex(group, "Demo_Pkg", false);
    PypiResponse second = groupService.getIndex(group, "demo-pkg", false);

    assertEquals(1, fixture.hosted.projectIndexCalls.get(), "second read must skip member fan-out");
    assertEquals(1, fixture.storage.puts.get(), "merged simple index should be written once to blob storage");
    assertTrue(fixture.assets.findAssetByPath(group.id(), PypiPaths.indexPath("demo-pkg")).isPresent(),
        "group repository must own a cached project index asset");
    String firstBody = body(first);
    String secondBody = body(second);
    assertEquals(firstBody, secondBody);
    assertTrue(firstBody.contains("demo_pkg-1.0.0.tar.gz"));
  }

  @Test
  void memberProjectInvalidationRebuildsCachedProjectIndex() throws IOException {
    Fixture fixture = fixture();
    fixture.repositories.putGroupsContaining(101L, List.of(record(999L, "pypi-group")));
    PypiGroupService groupService = fixture.groupService();

    RepositoryRuntime member = runtime(101L, "pypi-hosted", RepositoryType.HOSTED, List.of());
    RepositoryRuntime group = runtime(999L, "pypi-group", RepositoryType.GROUP, List.of(member));

    groupService.getIndex(group, "demo-pkg", false);
    fixture.cache.invalidateMemberProjectAfterCommit(member.id(), "demo-pkg");
    groupService.getIndex(group, "demo-pkg", false);

    assertEquals(2, fixture.hosted.projectIndexCalls.get(), "invalidated project index must fan out again");
    assertEquals(2, fixture.storage.puts.get(), "rebuilt project index must replace the cached blob");
  }

  private static Fixture fixture() {
    StubRepositoryDao repositories = new StubRepositoryDao();
    InMemoryAssetDao assets = new InMemoryAssetDao();
    InMemoryBlobStorage storage = new InMemoryBlobStorage();
    AssetMetadataCache assetMetadataCache = new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0);
    NexusLikeCacheController cacheController = new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    PypiGroupSimpleIndexCache cache =
        new PypiGroupSimpleIndexCache(repositories, assets, assetMetadataCache, cacheController, true);
    PypiAssetWriter writer = new PypiAssetWriter(
        assets,
        new NoopComponentDao(),
        new NoopBrowseNodeDao(),
        assetMetadataCache,
        cache,
        null);
    BlobStorageRegistry registry = new SingleBlobStorageRegistry(storage);
    PypiAssetReader reader = new PypiAssetReader(assets, registry);
    RecordingPypiHostedService hosted = new RecordingPypiHostedService();
    return new Fixture(repositories, assets, storage, cache, writer, reader, hosted, registry);
  }

  private static String body(PypiResponse response) throws IOException {
    try (InputStream in = response.body()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static RepositoryRuntime runtime(
      long id, String name, RepositoryType type, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.PYPI, type, "pypi-" + type.name().toLowerCase(),
        true, 1L, "ALLOW", null, null, true, null, null, 60,
        null, null, members);
  }

  private static RepositoryRecord record(long id, String name) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.PYPI, RepositoryType.GROUP,
        "pypi-group", true, 1L, null, null, null, null, "ALLOW", false, Map.of());
  }

  private record Fixture(
      StubRepositoryDao repositories,
      InMemoryAssetDao assets,
      InMemoryBlobStorage storage,
      PypiGroupSimpleIndexCache cache,
      PypiAssetWriter writer,
      PypiAssetReader reader,
      RecordingPypiHostedService hosted,
      BlobStorageRegistry registry) {
    PypiGroupService groupService() {
      return new PypiGroupService(hosted, null, cache, null, registry, writer, reader);
    }
  }

  private static class RecordingPypiHostedService extends PypiHostedService {
    final AtomicInteger projectIndexCalls = new AtomicInteger();

    RecordingPypiHostedService() {
      super(null, null, null, null, null, null, null, 0);
    }

    @Override
    public PypiResponse getIndex(RepositoryRuntime runtime, String projectName, boolean headOnly) {
      projectIndexCalls.incrementAndGet();
      String normalized = PypiPaths.normalizeName(projectName);
      String html = PypiIndex.buildProject(normalized, List.of(new PypiLink(
          "demo_pkg-1.0.0.tar.gz",
          "../../packages/demo-pkg/1.0.0/demo_pkg-1.0.0.tar.gz#md5=abc",
          "")));
      byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
      return PypiResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "text/html", null, Instant.now());
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
    public Optional<AssetBlobRecord> recoverDeletedBlobBySha256(long blobStoreId, String sha256, long size) {
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
      super(null, null, null, null, 0L);
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
