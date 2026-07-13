package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.catalog.CatalogCacheBroadcaster;
import com.github.klboke.kkrepo.server.support.dao.BlobStoreDaoAdapter;
import com.github.klboke.kkrepo.storage.s3.S3BlobStoreConfig;
import com.github.klboke.kkrepo.storage.s3.config.S3StorageProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BlobStorageRegistryTest {
  @Test
  void configForUsesBlobStoreCatalogSnapshot() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "default", "old-bucket"));
    BlobStorageRegistry registry = registry(dao);

    registry.syncDatabaseToMemory();

    assertEquals("old-bucket", registry.configFor(1).bucket());
    assertEquals("old-bucket", registry.configFor(1).bucket());
    assertEquals(1, dao.listCalls);
    assertEquals(0, dao.findByIdCalls);
  }

  @Test
  void scheduledSyncRefreshesBlobStoreCatalogFromDatabase() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "default", "old-bucket"));
    BlobStorageRegistry registry = registry(dao);

    registry.syncDatabaseToMemory();
    assertEquals("old-bucket", registry.configFor(1).bucket());

    dao.put(s3Store(1, "default", "new-bucket"));
    assertEquals("old-bucket", registry.configFor(1).bucket());

    registry.syncDatabaseToMemory();

    assertEquals("new-bucket", registry.configFor(1).bucket());
    assertEquals(2, dao.listCalls);
    assertEquals(0, dao.findByIdCalls);
  }

  @Test
  void refreshAllReloadsBlobStoreCatalogAfterMutation() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "default", "old-bucket"));
    BlobStorageRegistry registry = registry(dao);

    registry.syncDatabaseToMemory();
    dao.put(s3Store(1, "default", "new-bucket"));

    registry.refreshAll();

    assertEquals("new-bucket", registry.configFor(1).bucket());
    assertEquals(2, dao.listCalls);
    assertEquals(0, dao.findByIdCalls);
  }

  @Test
  void mutationBroadcastRefreshesSiblingCatalogImmediately() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "default", "old-bucket"));
    InMemoryBroadcaster broadcaster = new InMemoryBroadcaster();
    BlobStorageRegistry writerNode = registry(dao, broadcaster);
    BlobStorageRegistry siblingNode = registry(dao, broadcaster);

    writerNode.warmUpBlobStoreCatalog();
    siblingNode.warmUpBlobStoreCatalog();
    assertEquals("old-bucket", writerNode.configFor(1).bucket());
    assertEquals("old-bucket", siblingNode.configFor(1).bucket());

    dao.put(s3Store(1, "default", "new-bucket"));
    writerNode.refreshAllAndBroadcast();

    assertEquals("new-bucket", writerNode.configFor(1).bucket());
    assertEquals("new-bucket", siblingNode.configFor(1).bucket());
    assertEquals(1, broadcaster.publishCalls);
    assertEquals(0, dao.findByIdCalls);
  }

  private static BlobStorageRegistry registry(InMemoryBlobStoreDao dao) {
    return new BlobStorageRegistry(dao, null, null, new S3StorageProperties(), true);
  }

  private static BlobStorageRegistry registry(InMemoryBlobStoreDao dao, CatalogCacheBroadcaster broadcaster) {
    return new BlobStorageRegistry(dao, null, null, new S3StorageProperties(), true, broadcaster);
  }

  private static BlobStoreRecord s3Store(long id, String name, String bucket) {
    return new BlobStoreRecord(
        id,
        name,
        "s3",
        "https://oss-cn-shanghai-internal.aliyuncs.com",
        "cn-shanghai",
        bucket,
        "",
        Map.of(
            "engine", S3BlobStoreConfig.ENGINE_OSS_NATIVE,
            "accessKey", "ak",
            "secretKey", "sk",
            "pathStyleAccess", false));
  }

  private static final class InMemoryBlobStoreDao extends BlobStoreDaoAdapter {
    private final Map<Long, BlobStoreRecord> records = new LinkedHashMap<>();
    private int listCalls;
    private int findByIdCalls;

    private InMemoryBlobStoreDao() {
      super(null, null);
    }

    private void put(BlobStoreRecord record) {
      records.put(record.id(), record);
    }

    @Override
    public Optional<BlobStoreRecord> findById(long id) {
      findByIdCalls++;
      return Optional.ofNullable(records.get(id));
    }

    @Override
    public List<BlobStoreRecord> list() {
      listCalls++;
      return records.values().stream()
          .sorted(Comparator.comparing(BlobStoreRecord::name))
          .toList();
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
