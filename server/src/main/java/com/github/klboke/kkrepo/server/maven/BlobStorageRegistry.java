package com.github.klboke.kkrepo.server.maven;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.metrics.InstrumentedBlobStorage;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import com.github.klboke.kkrepo.storage.file.FileBlobStoreConfig;
import com.github.klboke.kkrepo.storage.file.FileBlobStorageFactory;
import com.github.klboke.kkrepo.storage.s3.S3BlobStorageFactory;
import com.github.klboke.kkrepo.storage.s3.S3BlobStoreConfig;
import com.github.klboke.kkrepo.storage.s3.config.S3StorageProperties;
import com.github.klboke.kkrepo.server.catalog.CatalogCacheBroadcaster;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link BlobStorage} bound to a repository. Caches per {@link S3BlobStoreConfig}
 * signature locally — each replica owns the SDK clients it has built. This is not state that
 * needs to be coherent across replicas (it's just connection pooling), so the cache is safe
 * within the "stateless service" constraint.
 */
@Component
public class BlobStorageRegistry {
  private static final Logger log = LoggerFactory.getLogger(BlobStorageRegistry.class);
  private static final String CATALOG_NAME = "blob-store";

  private final BlobStoreDao blobStoreDao;
  private final S3BlobStorageFactory s3Factory;
  private final FileBlobStorageFactory fileFactory;
  private final S3StorageProperties fallback;
  private final KkRepoMetrics metrics;
  private final Cache<String, BlobStorage> cache = Caffeine.newBuilder()
      .removalListener((String key, BlobStorage storage, RemovalCause cause) -> closeQuietly(storage))
      .build();
  private final boolean catalogCacheEnabled;
  private final CatalogCacheBroadcaster broadcaster;
  private final AtomicReference<BlobStoreCatalog> catalog = new AtomicReference<>();
  private final AtomicBoolean subscribed = new AtomicBoolean();
  private final ReentrantLock refreshLock = new ReentrantLock();

  @Autowired
  public BlobStorageRegistry(
      BlobStoreDao blobStoreDao,
      S3BlobStorageFactory s3Factory,
      FileBlobStorageFactory fileFactory,
      S3StorageProperties fallback,
      @Value("${kkrepo.catalog-cache.enabled:true}") boolean catalogCacheEnabled,
      ObjectProvider<CatalogCacheBroadcaster> broadcasterProvider,
      KkRepoMetrics metrics) {
    this.blobStoreDao = blobStoreDao;
    this.s3Factory = s3Factory;
    this.fileFactory = fileFactory;
    this.fallback = fallback;
    this.metrics = metrics;
    this.catalogCacheEnabled = catalogCacheEnabled;
    this.broadcaster = broadcasterProvider == null ? null : broadcasterProvider.getIfAvailable();
  }

  public BlobStorageRegistry(
      BlobStoreDao blobStoreDao,
      S3BlobStorageFactory s3Factory,
      FileBlobStorageFactory fileFactory,
      S3StorageProperties fallback,
      long recordTtlSeconds) {
    this(blobStoreDao, s3Factory, fileFactory, fallback, recordTtlSeconds > 0,
        (CatalogCacheBroadcaster) null, null);
  }

  public BlobStorageRegistry(
      BlobStoreDao blobStoreDao,
      S3BlobStorageFactory s3Factory,
      FileBlobStorageFactory fileFactory,
      S3StorageProperties fallback,
      boolean catalogCacheEnabled) {
    this(blobStoreDao, s3Factory, fileFactory, fallback, catalogCacheEnabled,
        (CatalogCacheBroadcaster) null, null);
  }

  BlobStorageRegistry(
      BlobStoreDao blobStoreDao,
      S3BlobStorageFactory s3Factory,
      FileBlobStorageFactory fileFactory,
      S3StorageProperties fallback,
      boolean catalogCacheEnabled,
      CatalogCacheBroadcaster broadcaster) {
    this(blobStoreDao, s3Factory, fileFactory, fallback, catalogCacheEnabled, broadcaster, null);
  }

  private BlobStorageRegistry(
      BlobStoreDao blobStoreDao,
      S3BlobStorageFactory s3Factory,
      FileBlobStorageFactory fileFactory,
      S3StorageProperties fallback,
      boolean catalogCacheEnabled,
      CatalogCacheBroadcaster broadcaster,
      KkRepoMetrics metrics) {
    this.blobStoreDao = blobStoreDao;
    this.s3Factory = s3Factory;
    this.fileFactory = fileFactory;
    this.fallback = fallback;
    this.metrics = metrics;
    this.catalogCacheEnabled = catalogCacheEnabled;
    this.broadcaster = broadcaster;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void warmUpBlobStoreCatalog() {
    if (!catalogCacheEnabled) {
      return;
    }
    subscribeToBroadcast();
    refreshSafely("startup");
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.catalog-cache.refresh-interval-ms:60000}",
      initialDelayString = "${kkrepo.catalog-cache.initial-delay-ms:60000}")
  public void syncDatabaseToMemory() {
    if (!catalogCacheEnabled) {
      return;
    }
    if (!refreshLock.tryLock()) {
      return;
    }
    try {
      refreshLocked("scheduled");
    } catch (RuntimeException e) {
      log.warn("Failed refreshing blob_store catalog from MySQL; keeping previous in-memory snapshot", e);
    } finally {
      refreshLock.unlock();
    }
  }

  public BlobStorage forBlobStoreId(long blobStoreId) {
    return forRecord(catalogRecord(blobStoreId));
  }

  public List<BlobStoreRecord> records() {
    if (!catalogCacheEnabled) {
      return blobStoreDao.list();
    }
    BlobStoreCatalog local = currentCatalog();
    if (local == null) {
      return blobStoreDao.list();
    }
    return local.records();
  }

  private BlobStoreRecord catalogRecord(long blobStoreId) {
    if (!catalogCacheEnabled) {
      return loadRecord(blobStoreId);
    }

    BlobStoreCatalog local = currentCatalog();
    BlobStoreRecord record = local == null ? null : local.byId().get(blobStoreId);
    if (record != null) {
      return record;
    }

    local = refreshBlocking("miss");
    record = local == null ? null : local.byId().get(blobStoreId);
    if (record != null) {
      return record;
    }

    record = loadRecord(blobStoreId);
    upsertCatalogRecord(record);
    return record;
  }

  private BlobStoreRecord loadRecord(long blobStoreId) {
    return blobStoreDao.findById(blobStoreId)
        .orElseThrow(() -> new IllegalStateException("Blob store not found: " + blobStoreId));
  }

  /** Reload blob_store after an admin create/update on this node and drop stale SDK clients. */
  public void refreshAll() {
    invalidateStorageCache();
    if (!catalogCacheEnabled) {
      return;
    }
    refreshLock.lock();
    try {
      refreshLocked("mutation");
    } catch (RuntimeException e) {
      catalog.set(null);
      log.warn("Failed refreshing blob_store catalog after mutation; cleared snapshot for lazy reload", e);
    } finally {
      refreshLock.unlock();
    }
  }

  /** Reload this node after an admin write, then ask sibling replicas to reload immediately. */
  public void refreshAllAndBroadcast() {
    refreshAll();
    publishRefresh();
  }

  @PreDestroy
  void shutdown() {
    invalidateStorageCache();
  }

  /** Drop a cached blob-store record (e.g. after an admin edits the blob store). */
  public void invalidate(long blobStoreId) {
    invalidateStorageCache();
    if (!catalogCacheEnabled) {
      return;
    }
    BlobStoreCatalog local = catalog.get();
    if (local == null || !local.byId().containsKey(blobStoreId)) {
      return;
    }
    Map<Long, BlobStoreRecord> byId = new LinkedHashMap<>(local.byId());
    byId.remove(blobStoreId);
    List<BlobStoreRecord> records = byId.values().stream()
        .sorted((left, right) -> left.name().compareTo(right.name()))
        .toList();
    catalog.set(new BlobStoreCatalog(Instant.now(), records, Collections.unmodifiableMap(byId)));
  }

  private void subscribeToBroadcast() {
    if (broadcaster == null || !subscribed.compareAndSet(false, true)) {
      return;
    }
    broadcaster.subscribe(CATALOG_NAME, this::refreshFromBroadcast);
  }

  private void refreshFromBroadcast() {
    invalidateStorageCache();
    refreshLock.lock();
    try {
      refreshLocked("catalog-broadcast");
    } finally {
      refreshLock.unlock();
    }
  }

  private void publishRefresh() {
    if (!catalogCacheEnabled || broadcaster == null) {
      return;
    }
    broadcaster.publishRefresh(CATALOG_NAME);
  }

  private BlobStoreCatalog currentCatalog() {
    BlobStoreCatalog local = catalog.get();
    if (local != null) {
      return local;
    }
    return refreshBlocking("lazy");
  }

  private BlobStoreCatalog refreshBlocking(String reason) {
    refreshLock.lock();
    try {
      return refreshLocked(reason);
    } catch (RuntimeException e) {
      log.warn("Failed loading blob_store catalog from MySQL", e);
      return catalog.get();
    } finally {
      refreshLock.unlock();
    }
  }

  private void refreshSafely(String reason) {
    refreshLock.lock();
    try {
      refreshLocked(reason);
    } catch (RuntimeException e) {
      log.warn("Failed refreshing blob_store catalog from MySQL; keeping previous in-memory snapshot", e);
    } finally {
      refreshLock.unlock();
    }
  }

  private BlobStoreCatalog refreshLocked(String reason) {
    List<BlobStoreRecord> records = blobStoreDao.list();
    Map<Long, BlobStoreRecord> byId = new LinkedHashMap<>();
    for (BlobStoreRecord record : records) {
      if (record.id() != null) {
        byId.put(record.id(), record);
      }
    }
    BlobStoreCatalog loaded = new BlobStoreCatalog(
        Instant.now(),
        List.copyOf(records),
        Collections.unmodifiableMap(byId));
    catalog.set(loaded);
    log.debug("Refreshed blob_store catalog from MySQL by {}: stores={}", reason, loaded.records().size());
    return loaded;
  }

  private void upsertCatalogRecord(BlobStoreRecord record) {
    if (record.id() == null) {
      return;
    }
    BlobStoreCatalog local = catalog.get();
    Map<Long, BlobStoreRecord> byId = local == null ? new LinkedHashMap<>() : new LinkedHashMap<>(local.byId());
    byId.put(record.id(), record);
    List<BlobStoreRecord> records = byId.values().stream()
        .sorted((left, right) -> left.name().compareTo(right.name()))
        .toList();
    catalog.set(new BlobStoreCatalog(Instant.now(), records, Collections.unmodifiableMap(byId)));
  }

  private record BlobStoreCatalog(
      Instant loadedAt,
      List<BlobStoreRecord> records,
      Map<Long, BlobStoreRecord> byId) {
  }

  public BlobStorage forRecord(BlobStoreRecord record) {
    if (isFileStore(record)) {
      FileBlobStoreConfig config = toFileConfig(record);
      return cache.get(config.signature(),
          key -> instrument(fileFactory.forStore(config), record.name(), record.type(), "file"));
    }
    S3BlobStoreConfig config = toConfig(record);
    return cache.get(config.signature(),
        key -> instrument(s3Factory.forStore(config), record.name(), record.type(), config.engine()));
  }

  private BlobStorage instrument(BlobStorage storage, String store, String type, String engine) {
    if (metrics == null) {
      return storage;
    }
    return new InstrumentedBlobStorage(storage, metrics, store, type, engine);
  }

  private void invalidateStorageCache() {
    cache.invalidateAll();
    cache.cleanUp();
  }

  private static void closeQuietly(BlobStorage storage) {
    if (storage == null) {
      return;
    }
    try {
      storage.close();
    } catch (RuntimeException ignored) {
    }
  }

  public S3BlobStoreConfig configFor(long blobStoreId) {
    return toConfig(catalogRecord(blobStoreId));
  }

  private FileBlobStoreConfig toFileConfig(BlobStoreRecord record) {
    Map<String, Object> attrs = record.attributes() == null ? Map.of() : record.attributes();
    return fileFactory.configFor(
        record.id() == null ? 0 : record.id(),
        record.name(),
        stringAttr(attrs, FileBlobStoreConfig.ATTR_PATH, ""));
  }

  private S3BlobStoreConfig toConfig(BlobStoreRecord record) {
    Map<String, Object> attrs = record.attributes() == null ? Map.of() : record.attributes();
    return new S3BlobStoreConfig(
        record.id() == null ? 0 : record.id(),
        record.name(),
        stringAttr(attrs, "engine", fallback.getEngine()),
        valueOrDefault(record.endpoint(), fallback.getEndpoint()),
        valueOrDefault(record.region(), fallback.getRegion()),
        valueOrDefault(record.bucket(), fallback.getBucket()),
        record.prefix() == null ? "" : record.prefix(),
        stringAttr(attrs, "accessKey", fallback.getAccessKey()),
        stringAttr(attrs, "secretKey", fallback.getSecretKey()),
        boolAttr(attrs, "pathStyleAccess", fallback.isPathStyleAccess()),
        intAttr(attrs, "maxConnections", fallback.getMaxConnections()),
        intAttr(attrs, "connectionTimeoutMs", fallback.getConnectionTimeoutMs()),
        intAttr(attrs, "socketTimeoutMs", fallback.getSocketTimeoutMs()),
        intAttr(attrs, "connectionAcquisitionTimeoutMs", fallback.getConnectionAcquisitionTimeoutMs()),
        boolAttr(attrs, "tcpKeepAlive", fallback.isTcpKeepAlive()),
        longAttr(attrs, "multipartThresholdBytes", fallback.getMultipartThresholdBytes()),
        longAttr(attrs, "multipartPartSizeBytes", fallback.getMultipartPartSizeBytes()),
        intAttr(attrs, "multipartConcurrency", fallback.getMultipartConcurrency()));
  }

  private static boolean isFileStore(BlobStoreRecord record) {
    return FileBlobStoreConfig.isFileStore(record.type(), record.attributes());
  }

  private static String stringAttr(Map<String, Object> attrs, String key, String fallback) {
    Object value = attrs.get(key);
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  private static boolean boolAttr(Map<String, Object> attrs, String key, boolean fallback) {
    Object value = attrs.get(key);
    if (value == null) return fallback;
    if (value instanceof Boolean b) return b;
    return Boolean.parseBoolean(value.toString());
  }

  private static int intAttr(Map<String, Object> attrs, String key, int fallback) {
    Object value = attrs.get(key);
    if (value == null) return fallback;
    if (value instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static long longAttr(Map<String, Object> attrs, String key, long fallback) {
    Object value = attrs.get(key);
    if (value == null) return fallback;
    if (value instanceof Number number) return number.longValue();
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String valueOrDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
