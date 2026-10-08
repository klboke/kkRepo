package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.catalog.CatalogCacheBroadcaster;
import com.github.klboke.kkrepo.server.support.dao.BlobStoreDaoAdapter;
import com.github.klboke.kkrepo.storage.s3.S3BlobStoreConfig;
import com.github.klboke.kkrepo.storage.s3.S3BlobStorageFactory;
import com.github.klboke.kkrepo.storage.s3.config.S3StorageProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlobStorageRegistryTest {
  @Test
  void deletionEvictsSdkClientsOnWriterAndBroadcastSubscriber() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "unused", "old-bucket"));
    S3BlobStorageFactory writerClients = clientsCaching(1);
    S3BlobStorageFactory subscriberClients = clientsCaching(1);
    InMemoryBroadcaster broadcaster = new InMemoryBroadcaster();
    BlobStorageRegistry writer = new BlobStorageRegistry(dao, writerClients, null,
        new S3StorageProperties(), true, broadcaster);
    BlobStorageRegistry subscriber = new BlobStorageRegistry(dao, subscriberClients, null,
        new S3StorageProperties(), true, broadcaster);
    writer.warmUpBlobStoreCatalog();
    subscriber.warmUpBlobStoreCatalog();

    dao.remove(1);
    writer.invalidate(1);
    writer.refreshAllAndBroadcast();

    verify(writerClients, atLeastOnce()).invalidate(1);
    verify(subscriberClients).invalidate(1);
    assertTrue(subscriber.records().isEmpty());
  }

  @Test
  void scheduledRefreshPrunesDeletedClientsEvenWithoutCatalogCaching() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "unused", "old-bucket"));
    S3BlobStorageFactory clients = clientsCaching(1);
    BlobStorageRegistry registry = new BlobStorageRegistry(dao, clients, null,
        new S3StorageProperties(), false);

    dao.remove(1);
    registry.syncDatabaseToMemory();

    verify(clients).invalidate(1);
  }

  @Test
  void scheduledRefreshRecoversWhenDeletionBroadcastWasMissed() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "unused", "old-bucket"));
    S3BlobStorageFactory clients = clientsCaching(1);
    BlobStorageRegistry registry = new BlobStorageRegistry(dao, clients, null,
        new S3StorageProperties(), true);
    registry.warmUpBlobStoreCatalog();

    dao.remove(1);
    registry.syncDatabaseToMemory();

    verify(clients).invalidate(1);
    assertTrue(registry.records().isEmpty());
  }

  @Test
  void uncachedMutationPrunesClientsAndDatabaseOutagesDoNotBreakCacheReconciliation() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "unused", "old-bucket"));
    S3BlobStorageFactory clients = clientsCaching(1);
    BlobStorageRegistry registry = new BlobStorageRegistry(dao, clients, null,
        new S3StorageProperties(), false);

    dao.remove(1);
    registry.refreshAll();
    verify(clients).invalidate(1);

    dao.failList = true;
    registry.refreshAll();
    registry.syncDatabaseToMemory();
  }

  private static S3BlobStorageFactory clientsCaching(long id) {
    S3BlobStorageFactory factory = mock(S3BlobStorageFactory.class);
    when(factory.cachedStoreIdsMissingFrom(anySet())).thenAnswer(invocation -> {
      Set<Long> liveIds = invocation.getArgument(0);
      return liveIds.contains(id) ? Set.of() : Set.of(id);
    });
    return factory;
  }

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

  @Test
  void defaultCredentialSelectionPropagatesAcrossReplicasWithoutGlobalKeyFallback() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "default", "bucket"));
    S3StorageProperties defaults = new S3StorageProperties();
    defaults.setAccessKey("global-ak");
    defaults.setSecretKey("global-sk");
    InMemoryBroadcaster broadcaster = new InMemoryBroadcaster();
    BlobStorageRegistry first = new BlobStorageRegistry(dao, null, null, defaults, true, broadcaster);
    BlobStorageRegistry second = new BlobStorageRegistry(dao, null, null, defaults, true, broadcaster);
    first.warmUpBlobStoreCatalog();
    second.warmUpBlobStoreCatalog();
    assertEquals("ak", second.configFor(1).accessKey());
    dao.put(new BlobStoreRecord(1L, "default", "s3", "https://s3.us-east-1.amazonaws.com",
        "us-east-1", "bucket", "", Map.of("engine", "aws-s3", "accessKey", "", "secretKey", "")));
    first.refreshAllAndBroadcast();
    assertTrue(first.configFor(1).usesDefaultCredentials());
    assertTrue(second.configFor(1).usesDefaultCredentials());
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
    private boolean failList;

    private InMemoryBlobStoreDao() {
      super(null, null);
    }

    private void put(BlobStoreRecord record) {
      records.put(record.id(), record);
    }

    private void remove(long id) {
      records.remove(id);
    }

    @Override
    public Optional<BlobStoreRecord> findById(long id) {
      findByIdCalls++;
      return Optional.ofNullable(records.get(id));
    }

    @Override
    public List<BlobStoreRecord> list() {
      if (failList) throw new IllegalStateException("database unavailable");
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
