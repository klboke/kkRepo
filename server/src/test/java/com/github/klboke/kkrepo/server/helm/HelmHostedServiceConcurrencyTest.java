package com.github.klboke.kkrepo.server.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.BrowseNodeDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.ComponentDaoAdapter;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class HelmHostedServiceConcurrencyTest {
  @Test
  void concurrentInitialIndexGenerationAndRebuildConvergeOnOneAsset() throws Exception {
    RacingAssetDao assetDao = new RacingAssetDao();
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    AssetMetadataCache cache = new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0);
    BlobStorage storage = new NoopBlobStorage();
    HelmAssetWriter writer = new HelmAssetWriter(
        assetDao,
        new FixedComponentDao(),
        new NoopBrowseNodeDao(),
        cache,
        new TransientTransactionRetry(transactions, 3, 0));
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    when(registry.forBlobStoreId(7L)).thenReturn(storage);
    HelmAssetReader reader = mock(HelmAssetReader.class);
    when(reader.serveSnapshot(any(CachedAssetMetadata.class), eq(true), eq("index.yaml")))
        .thenReturn(MavenResponse.noBody(200));
    HelmHostedService service = new HelmHostedService(
        assetDao,
        mock(RepositoryIndexRebuildDao.class),
        registry,
        writer,
        reader,
        cache);

    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<MavenResponse> initialGet = executor.submit(
          () -> service.get(runtime(), "index.yaml", true));
      assetDao.awaitInitialIndexCheck();
      Future<?> rebuild = executor.submit(() -> {
        service.rebuildIndex(runtime(), storage, 7L, "system", null);
        return null;
      });

      assertEquals(200, get(initialGet).status());
      get(rebuild);
    }

    assertEquals(1, assetDao.assetCount());
    assertEquals(2, assetDao.tryInsertCalls.get());
    assertEquals(1, assetDao.updateAssetCalls.get());
    assertEquals(1, assetDao.markDeletedCalls.get());
    assertEquals(2, transactions.begun.get());
    assertEquals(0, transactions.rolledBack.get());
    assertEquals(2, transactions.committed.get());
  }

  private static <T> T get(Future<T> future) throws Exception {
    try {
      return future.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw e;
    } catch (TimeoutException e) {
      throw new AssertionError("Concurrent Helm index test timed out", e);
    }
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        10L, "helm-hosted", RepositoryFormat.HELM, RepositoryType.HOSTED, "helm-hosted",
        true, 7L, "ALLOW", null, null, true, null, 60, 60, true, null, List.of());
  }

  private static final class RacingAssetDao extends AssetDaoAdapter {
    private final AtomicLong blobIds = new AtomicLong(900);
    private final AtomicLong assetIds = new AtomicLong(100);
    private final ConcurrentHashMap<Long, AssetBlobRecord> blobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AssetRecord> assets = new ConcurrentHashMap<>();
    private final CountDownLatch initialIndexCheck = new CountDownLatch(1);
    private final CountDownLatch persistFinds = new CountDownLatch(2);
    private final CountDownLatch insertAttempts = new CountDownLatch(2);
    private final CountDownLatch successfulInsert = new CountDownLatch(1);
    private final AtomicInteger findCalls = new AtomicInteger();
    private final AtomicInteger tryInsertCalls = new AtomicInteger();
    private final AtomicInteger updateAssetCalls = new AtomicInteger();
    private final AtomicInteger markDeletedCalls = new AtomicInteger();

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      int call = findCalls.incrementAndGet();
      if (call == 1) {
        initialIndexCheck.countDown();
        return Optional.empty();
      }
      if (call <= 3) {
        persistFinds.countDown();
        await(persistFinds, "Helm writers did not reach the initial asset lookup");
        return Optional.empty();
      }
      return Optional.ofNullable(assets.get(key(repositoryId, path)));
    }

    @Override
    public Optional<AssetBlobRecord> findReusableBlobBySha256(
        long blobStoreId, String sha256, long size) {
      return Optional.empty();
    }

    @Override
    public Optional<AssetBlobRecord> recoverDeletedBlobBySha256(
        long blobStoreId, String sha256, long size) {
      return Optional.empty();
    }

    @Override
    public long insertBlob(AssetBlobRecord record) {
      long id = blobIds.incrementAndGet();
      blobs.put(id, record.withId(id));
      return id;
    }

    @Override
    public Optional<AssetBlobRecord> findBlobById(long blobId) {
      return Optional.ofNullable(blobs.get(blobId));
    }

    @Override
    public OptionalLong tryInsertAsset(AssetRecord record) {
      int call = tryInsertCalls.incrementAndGet();
      insertAttempts.countDown();
      await(insertAttempts, "Helm writers did not race the asset insert");
      if (call == 1) {
        await(successfulInsert, "Concurrent Helm asset insert did not complete");
        return OptionalLong.empty();
      }
      long id = assetIds.incrementAndGet();
      assets.put(key(record.repositoryId(), record.path()), withId(record, id));
      successfulInsert.countDown();
      return OptionalLong.of(id);
    }

    @Override
    public int updateAssetBlobBindingAndMetadata(
        long assetId,
        Long componentId,
        long assetBlobId,
        String kind,
        String contentType,
        long size,
        Instant lastUpdatedAt,
        Map<String, Object> attributes) {
      updateAssetCalls.incrementAndGet();
      assets.computeIfPresent(key(10L, "index.yaml"), (ignored, prior) -> new AssetRecord(
          prior.id(), prior.repositoryId(), componentId, assetBlobId, prior.format(), prior.path(),
          prior.pathHash(), prior.name(), kind, contentType, size, prior.lastDownloadedAt(),
          lastUpdatedAt, attributes));
      return 1;
    }

    @Override
    public int markBlobDeletedIfUnreferenced(long blobId, String reason) {
      markDeletedCalls.incrementAndGet();
      return 1;
    }

    @Override
    public List<HelmIndexRow> listHelmIndexRows(long repositoryId) {
      return List.of();
    }

    void awaitInitialIndexCheck() {
      await(initialIndexCheck, "Initial Helm index request did not check for index.yaml");
    }

    int assetCount() {
      return assets.size();
    }

    private static String key(long repositoryId, String path) {
      return repositoryId + ":" + path;
    }

    private static AssetRecord withId(AssetRecord record, long id) {
      return new AssetRecord(
          id, record.repositoryId(), record.componentId(), record.assetBlobId(), record.format(),
          record.path(), record.pathHash(), record.name(), record.kind(), record.contentType(),
          record.size(), record.lastDownloadedAt(), record.lastUpdatedAt(), record.attributes());
    }

    private static void await(CountDownLatch latch, String message) {
      try {
        if (!latch.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError(message);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  private static final class FixedComponentDao extends ComponentDaoAdapter {
    @Override
    public long upsertReturningId(ComponentRecord record) {
      return 501L;
    }
  }

  private static final class NoopBrowseNodeDao extends BrowseNodeDaoAdapter {
    @Override
    public void upsertPathAncestors(
        long repositoryId, String fullPath, Long assetId, Long componentId) {
    }
  }

  private static final class NoopBlobStorage implements BlobStorage {
    private final AtomicInteger puts = new AtomicInteger();

    @Override
    public BlobReference put(
        String repository, String logicalPath, InputStream content, long size, String sha256) {
      return new BlobReference(
          "default", "helm-index/" + puts.incrementAndGet(), sha256, size);
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public boolean exists(BlobReference reference) {
      return false;
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
    }
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private final AtomicInteger begun = new AtomicInteger();
    private final AtomicInteger committed = new AtomicInteger();
    private final AtomicInteger rolledBack = new AtomicInteger();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
        throws TransactionException {
      begun.incrementAndGet();
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
      committed.incrementAndGet();
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
      rolledBack.incrementAndGet();
    }
  }
}
