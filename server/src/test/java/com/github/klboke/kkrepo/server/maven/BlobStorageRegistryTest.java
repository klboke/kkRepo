package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.BlobStoreDeletionService;
import com.github.klboke.kkrepo.server.catalog.CatalogCacheBroadcaster;
import com.github.klboke.kkrepo.server.support.dao.BlobStoreDaoAdapter;
import com.github.klboke.kkrepo.storage.file.FileBlobStorageFactory;
import com.github.klboke.kkrepo.storage.file.FileBlobStoreConfig;
import com.github.klboke.kkrepo.storage.file.config.FileStorageProperties;
import com.github.klboke.kkrepo.storage.s3.S3BlobStoreConfig;
import com.github.klboke.kkrepo.storage.s3.S3BlobStorage;
import com.github.klboke.kkrepo.storage.s3.S3BlobStorageFactory;
import com.github.klboke.kkrepo.storage.s3.config.S3StorageProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

class BlobStorageRegistryTest {
  @ParameterizedTest
  @ValueSource(strings = {"writer", "subscriber", "scheduled", "uncached"})
  void deletingAnotherStorePreservesMultipartUploads(String refreshPath, @TempDir Path tempDir) throws Exception {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "deleted", "unused-bucket"));
    dao.put(s3Store(2, "uploading", "bucket"));
    InMemoryBroadcaster broadcaster = new InMemoryBroadcaster();
    S3BlobStorageFactory factory = clientsCaching(1);
    S3Client client = mock(S3Client.class);
    BlobStorage deleted = mock(BlobStorage.class);
    S3StorageProperties defaults = new S3StorageProperties();
    defaults.setMultipartThresholdBytes(1);
    defaults.setMultipartPartSizeBytes(5L * 1024 * 1024);
    BlobStorageRegistry registry = new BlobStorageRegistry(dao, factory, null,
        defaults, !refreshPath.equals("uncached"), broadcaster);
    when(factory.forStore(any())).thenAnswer(invocation -> {
      S3BlobStoreConfig config = invocation.getArgument(0);
      return config.id() == 1 ? deleted : new S3BlobStorage(client, config);
    });
    registry.warmUpBlobStoreCatalog();
    registry.forBlobStoreId(1);
    BlobStorage uploading = registry.forBlobStoreId(2);
    assertNotSame(deleted, uploading);
    BlobStorageRegistry writer = refreshPath.equals("subscriber")
        ? registry(dao, broadcaster) : registry;
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    BlobStoreDeletionService deletion = new BlobStoreDeletionService(dao, writer, transactions);
    when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class))).thenAnswer(invocation -> {
      // Deterministically delete between multipart initialization and executor submission.
      if (refreshPath.equals("writer") || refreshPath.equals("subscriber")) {
        deletion.deleteEmpty(1);
      } else {
        dao.remove(1); // Missed broadcast, recovered on this node's periodic refresh.
        registry.syncDatabaseToMemory();
      }
      verify(deleted, timeout(1000)).close();
      return CreateMultipartUploadResponse.builder().uploadId("upload-1").build();
    });
    when(client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
        .thenReturn(UploadPartResponse.builder().eTag("etag").build());
    Path file = Files.write(tempDir.resolve("multipart.bin"), new byte[6 * 1024 * 1024]);
    try {
      assertEquals(Files.size(file), uploading.putFile("repo", "large.bin", file, "c".repeat(64)).size());
      assertSame(uploading, registry.forBlobStoreId(2));
      verify(client).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
      verify(client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
      verify(factory, atLeastOnce()).invalidate(1);
      verify(factory, never()).invalidate(2);
    } finally {
      registry.shutdown();
      uploading.close();
    }
  }

  @Test
  void scheduledRefreshEvictsDeletedFileWrappersWithoutAnSdkFactory(@TempDir Path tempDir) {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(new BlobStoreRecord(1L, "deleted", "file", null, null, null, "", Map.of("path", "deleted")));
    dao.put(new BlobStoreRecord(2L, "retained", "file", null, null, null, "", Map.of("path", "retained")));
    FileStorageProperties properties = new FileStorageProperties();
    properties.setBaseDir(tempDir.toString());
    FileBlobStorageFactory files = new FileBlobStorageFactory(properties) {
      @Override
      public BlobStorage forStore(FileBlobStoreConfig config) {
        return mock(BlobStorage.class);
      }
    };
    BlobStorageRegistry registry = new BlobStorageRegistry(dao, null, files, new S3StorageProperties(), false);
    BlobStorage deleted = registry.forBlobStoreId(1);
    BlobStorage retained = registry.forBlobStoreId(2);
    try {
      dao.remove(1);
      registry.syncDatabaseToMemory();
      verify(deleted, timeout(1000)).close();
      assertSame(retained, registry.forBlobStoreId(2));
      verify(retained, never()).close();
    } finally {
      registry.shutdown();
    }
  }

  @Test
  void identicalConfigurationsStillHaveSeparateWrapperOwnership() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "deleted", "bucket"));
    dao.put(s3Store(2, "retained", "bucket"));
    S3BlobStorageFactory factory = mock(S3BlobStorageFactory.class);
    when(factory.forStore(any())).thenAnswer(invocation -> mock(BlobStorage.class));
    BlobStorageRegistry registry = new BlobStorageRegistry(dao, factory, null,
        new S3StorageProperties(), true);
    BlobStorage deleted = registry.forBlobStoreId(1);
    BlobStorage retained = registry.forBlobStoreId(2);
    try {
      assertNotSame(deleted, retained);
      dao.remove(1);
      registry.invalidate(1);
      registry.refreshAll();
      verify(deleted, timeout(1000)).close();
      assertSame(retained, registry.forBlobStoreId(2));
      verify(retained, never()).close();
    } finally {
      registry.shutdown();
    }
  }

  @Test
  void refreshReplacesOnlyChangedStorageConfigurations() {
    InMemoryBlobStoreDao dao = new InMemoryBlobStoreDao();
    dao.put(s3Store(1, "changed", "old-bucket"));
    dao.put(s3Store(2, "unchanged", "bucket"));
    S3BlobStorageFactory factory = mock(S3BlobStorageFactory.class);
    when(factory.forStore(any())).thenAnswer(invocation -> mock(BlobStorage.class));
    BlobStorageRegistry registry = new BlobStorageRegistry(dao, factory, null,
        new S3StorageProperties(), true);
    BlobStorage changed = registry.forBlobStoreId(1);
    BlobStorage unchanged = registry.forBlobStoreId(2);
    try {
      dao.put(s3Store(1, "changed", "new-bucket"));
      registry.refreshAll();
      verify(changed, timeout(1000)).close();
      assertNotSame(changed, registry.forBlobStoreId(1));
      assertSame(unchanged, registry.forBlobStoreId(2));
      verify(unchanged, never()).close();
    } finally {
      registry.shutdown();
    }
  }

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
    public DeleteResult deleteEmptyById(long id) {
      return records.remove(id) == null ? DeleteResult.NOT_FOUND : DeleteResult.DELETED;
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
