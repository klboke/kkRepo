package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.BrowseNodeDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.ComponentDaoAdapter;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class NpmAssetWriterConcurrencyTest {
  private static final NpmPackageId PACKAGE_ID = NpmPackageId.parse("@radix-ui/react-alert-dialog");
  private static final String VERSION = "1.0.5";
  private static final String TARBALL_NAME = "react-alert-dialog-1.0.5.tgz";
  private static final String PATH = PACKAGE_ID.tarballPath(TARBALL_NAME);

  @Test
  void concurrentWritesHandleDuplicateInsertAndUpdateExistingAsset() throws Exception {
    DuplicateRaceAssetDao assetDao = new DuplicateRaceAssetDao();
    NoopBrowseNodeDao browseNodeDao = new NoopBrowseNodeDao();
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    NpmAssetWriter writer = writer(assetDao, browseNodeDao, transactionManager);

    StoredPair stored = runConcurrentWrites(writer);

    assertSameAsset(stored);
    assertEquals(1, createdCount(stored));
    assertEquals(2, assetDao.tryInsertCalls.get());
    assertEquals(1, assetDao.updateAssetCalls.get());
    assertEquals(1, assetDao.markDeletedCalls.get());
    assertEquals(2, browseNodeDao.upsertCalls.get());
    assertEquals(2, transactionManager.begun.get());
    assertEquals(0, transactionManager.rolledBack.get());
    assertEquals(2, transactionManager.committed.get());
  }

  @Test
  void concurrentWritesRetryTransientDeadlockAndConvergeOnExistingAsset() throws Exception {
    DeadlockRaceAssetDao assetDao = new DeadlockRaceAssetDao();
    NoopBrowseNodeDao browseNodeDao = new NoopBrowseNodeDao();
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    NpmAssetWriter writer = writer(assetDao, browseNodeDao, transactionManager);

    StoredPair stored = runConcurrentWrites(writer);

    assertSameAsset(stored);
    assertEquals(1, createdCount(stored));
    assertEquals(2, assetDao.tryInsertCalls.get());
    assertEquals(1, assetDao.updateAssetCalls.get());
    assertEquals(1, assetDao.markDeletedCalls.get());
    assertEquals(2, browseNodeDao.upsertCalls.get());
    assertEquals(3, transactionManager.begun.get());
    assertEquals(1, transactionManager.rolledBack.get());
    assertEquals(2, transactionManager.committed.get());
  }

  private static NpmAssetWriter writer(
      RaceAssetDao assetDao,
      NoopBrowseNodeDao browseNodeDao,
      RecordingTransactionManager transactionManager) {
    return new NpmAssetWriter(
        assetDao,
        new FixedComponentDao(),
        browseNodeDao,
        new TransientTransactionRetry(transactionManager, 3, 0),
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0),
        null,
        null);
  }

  private static StoredPair runConcurrentWrites(NpmAssetWriter writer) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<NpmAssetWriter.Stored> first = executor.submit(writeTask(writer, start));
      Future<NpmAssetWriter.Stored> second = executor.submit(writeTask(writer, start));
      start.countDown();
      return new StoredPair(get(first), get(second));
    } finally {
      executor.shutdownNow();
    }
  }

  private static Callable<NpmAssetWriter.Stored> writeTask(
      NpmAssetWriter writer, CountDownLatch start) {
    return () -> {
      start.await(5, TimeUnit.SECONDS);
      return writer.writeTarball(
          runtime(),
          new NoopBlobStorage(),
          1L,
          PACKAGE_ID,
          VERSION,
          TARBALL_NAME,
          new ByteArrayInputStream("tarball-bytes".getBytes(StandardCharsets.UTF_8)),
          NpmResponseSupport.TARBALL,
          "system",
          "127.0.0.1",
          Map.of());
    };
  }

  private static NpmAssetWriter.Stored get(Future<NpmAssetWriter.Stored> future) throws Exception {
    try {
      return future.get(5, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new AssertionError("concurrent npm writer test timed out", e);
    }
  }

  private static void assertSameAsset(StoredPair stored) {
    assertEquals(PATH, stored.first().asset().path());
    assertEquals(PATH, stored.second().asset().path());
    assertEquals(stored.first().asset().id(), stored.second().asset().id());
  }

  private static long createdCount(StoredPair stored) {
    return List.of(stored.first(), stored.second()).stream()
        .filter(NpmAssetWriter.Stored::created)
        .count();
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        10L,
        "npm-proxy",
        RepositoryFormat.NPM,
        RepositoryType.PROXY,
        "npm-proxy",
        true,
        1L,
        null,
        "MIXED",
        "PERMISSIVE",
        true,
        "https://registry.npmjs.org",
        1440,
        1440,
        true, null, List.of());
  }

  private record StoredPair(NpmAssetWriter.Stored first, NpmAssetWriter.Stored second) {}

  private abstract static class RaceAssetDao extends AssetDaoAdapter {
    private final AtomicLong blobIds = new AtomicLong(900);
    private final AtomicLong assetIds = new AtomicLong(100);
    private final ConcurrentHashMap<Long, AssetBlobRecord> blobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AssetRecord> assets = new ConcurrentHashMap<>();
    private final CountDownLatch firstFinds = new CountDownLatch(2);
    protected final CountDownLatch firstWaveInserts = new CountDownLatch(2);
    protected final CountDownLatch successfulInsert = new CountDownLatch(1);
    protected final AtomicInteger tryInsertCalls = new AtomicInteger();
    protected final AtomicInteger updateAssetCalls = new AtomicInteger();
    protected final AtomicInteger markDeletedCalls = new AtomicInteger();
    private final AtomicInteger findCalls = new AtomicInteger();

    RaceAssetDao() {
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
    public long insertAsset(AssetRecord record) {
      throw new AssertionError("npm writer must use duplicate-aware tryInsertAsset");
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      int call = findCalls.incrementAndGet();
      if (call <= 2) {
        firstFinds.countDown();
        await(firstFinds);
        return Optional.empty();
      }
      return Optional.ofNullable(assets.get(key(repositoryId, path)));
    }

    @Override
    public int updateAssetBlobBindingAndMetadata(long assetId, Long componentId, long assetBlobId,
        String kind, String contentType, long size, Instant lastUpdatedAt, Map<String, Object> attributes) {
      updateAssetCalls.incrementAndGet();
      assets.computeIfPresent(key(10L, PATH), (ignored, prior) -> new AssetRecord(
          prior.id(),
          prior.repositoryId(),
          componentId,
          assetBlobId,
          prior.format(),
          prior.path(),
          prior.pathHash(),
          prior.name(),
          kind,
          contentType,
          size,
          prior.lastDownloadedAt(),
          lastUpdatedAt,
          attributes));
      return 1;
    }

    @Override
    public int markBlobDeletedIfUnreferenced(long assetBlobId, String reason) {
      markDeletedCalls.incrementAndGet();
      return 1;
    }

    protected OptionalLong insertSuccessfully(AssetRecord record) {
      long id = assetIds.incrementAndGet();
      assets.put(key(record.repositoryId(), record.path()), withId(record, id));
      successfulInsert.countDown();
      return OptionalLong.of(id);
    }

    protected static void await(CountDownLatch latch) {
      try {
        if (!latch.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting for concurrent npm asset write");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }

    private static String key(long repositoryId, String path) {
      return repositoryId + ":" + path;
    }

    private static AssetBlobRecord withId(AssetBlobRecord record, long id) {
      return new AssetBlobRecord(
          id,
          record.blobStoreId(),
          record.blobRef(),
          record.blobRefHash(),
          record.objectKey(),
          record.objectKeyHash(),
          record.sha1(),
          record.sha256(),
          record.md5(),
          record.size(),
          record.contentType(),
          record.createdBy(),
          record.createdByIp(),
          record.blobCreatedAt(),
          record.blobUpdatedAt(),
          record.attributes());
    }

    private static AssetRecord withId(AssetRecord record, long id) {
      return new AssetRecord(
          id,
          record.repositoryId(),
          record.componentId(),
          record.assetBlobId(),
          record.format(),
          record.path(),
          record.pathHash(),
          record.name(),
          record.kind(),
          record.contentType(),
          record.size(),
          record.lastDownloadedAt(),
          record.lastUpdatedAt(),
          record.attributes());
    }
  }

  private static final class DuplicateRaceAssetDao extends RaceAssetDao {
    @Override
    public OptionalLong tryInsertAsset(AssetRecord record) {
      int call = tryInsertCalls.incrementAndGet();
      if (call <= 2) {
        firstWaveInserts.countDown();
        await(firstWaveInserts);
      }
      if (call == 1) {
        await(successfulInsert);
        return OptionalLong.empty();
      }
      return insertSuccessfully(record);
    }
  }

  private static final class DeadlockRaceAssetDao extends RaceAssetDao {
    @Override
    public OptionalLong tryInsertAsset(AssetRecord record) {
      int call = tryInsertCalls.incrementAndGet();
      if (call <= 2) {
        firstWaveInserts.countDown();
        await(firstWaveInserts);
      }
      if (call == 1) {
        await(successfulInsert);
        throw new CannotAcquireLockException("simulated concurrent npm asset deadlock");
      }
      return insertSuccessfully(record);
    }
  }

  private static final class FixedComponentDao extends ComponentDaoAdapter {
    FixedComponentDao() {
      super(null, null);
    }

    @Override
    public long upsertReturningId(ComponentRecord record) {
      return 501L;
    }
  }

  private static final class NoopBrowseNodeDao extends BrowseNodeDaoAdapter {
    private final AtomicInteger upsertCalls = new AtomicInteger();

    NoopBrowseNodeDao() {
      super(null);
    }

    @Override
    public void upsertPathAncestors(long repositoryId, String fullPath, Long assetId, Long componentId) {
      upsertCalls.incrementAndGet();
    }
  }

  private static final class NoopBlobStorage implements BlobStorage {
    private final AtomicInteger puts = new AtomicInteger();

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      return new BlobReference("default", "objects/" + puts.incrementAndGet(), sha256, size);
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
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
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
