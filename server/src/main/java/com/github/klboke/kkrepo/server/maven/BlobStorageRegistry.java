package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreDescriptor;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreProviders;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreTypes;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.metrics.InstrumentedBlobStorage;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import com.github.klboke.kkrepo.server.plugin.PluginRuntime;
import com.github.klboke.kkrepo.storage.file.FileBlobStorageFactory;
import com.github.klboke.kkrepo.storage.file.FileBlobStoreProvider;
import com.github.klboke.kkrepo.storage.s3.S3BlobStorageFactory;
import com.github.klboke.kkrepo.storage.s3.S3BlobStoreConfig;
import com.github.klboke.kkrepo.storage.s3.S3BlobStoreProvider;
import com.github.klboke.kkrepo.storage.s3.config.S3StorageProperties;
import com.github.klboke.kkrepo.server.catalog.CatalogCacheBroadcaster;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Resolves the {@link BlobStorage} bound to a repository. Each replica owns wrappers cached by
 * store ID and configuration signature. Database catalog refreshes and broadcasts evict deleted
 * or changed entries while preserving unchanged wrappers and their in-flight multipart uploads.
 *
 * <p>Backends are pluggable: each row is matched to a {@link BlobStoreProvider} by
 * {@code blob_store.type}, so adding a storage technology needs no change here.
 */
@Component
public class BlobStorageRegistry {
  private static final Logger log = LoggerFactory.getLogger(BlobStorageRegistry.class);
  private static final String CATALOG_NAME = "blob-store";

  private final BlobStoreDao blobStoreDao;
  private final BlobStoreProviders providers;
  private final KkRepoMetrics metrics;
  private final LocalCache<StorageCacheKey, BlobStorage> cache = LocalCacheFactory.standard()
      .<StorageCacheKey, BlobStorage>builder("blob-storages")
      .removalListener((key, storage, cause) -> closeQuietly(storage))
      .build();
  private final boolean catalogCacheEnabled;
  private final CatalogCacheBroadcaster broadcaster;
  private final AtomicReference<BlobStoreCatalog> catalog = new AtomicReference<>();
  private final AtomicBoolean subscribed = new AtomicBoolean();
  private final ReentrantLock refreshLock = new ReentrantLock();

  @Autowired
  public BlobStorageRegistry(
      BlobStoreDao blobStoreDao,
      List<BlobStoreProvider> providers,
      ObjectProvider<PluginRuntime> pluginRuntime,
      @Value("${kkrepo.catalog-cache.enabled:true}") boolean catalogCacheEnabled,
      ObjectProvider<CatalogCacheBroadcaster> broadcasterProvider,
      KkRepoMetrics metrics) {
    this(
        blobStoreDao,
        providers,
        mergeProviders(providers, pluginRuntime),
        catalogCacheEnabled,
        broadcasterProvider == null ? null : broadcasterProvider.getIfAvailable(),
        metrics);
  }

  public BlobStorageRegistry(
      BlobStoreDao blobStoreDao,
      List<BlobStoreProvider> providers,
      boolean catalogCacheEnabled,
      CatalogCacheBroadcaster broadcaster,
      KkRepoMetrics metrics) {
    this(blobStoreDao, providers, mergeProviders(providers, null),
        catalogCacheEnabled, broadcaster, metrics);
  }

  private BlobStorageRegistry(
      BlobStoreDao blobStoreDao,
      List<BlobStoreProvider> providers,
      List<BlobStoreProvider> mergedProviders,
      boolean catalogCacheEnabled,
      CatalogCacheBroadcaster broadcaster,
      KkRepoMetrics metrics) {
    this.blobStoreDao = blobStoreDao;
    this.providers = new BlobStoreProviders(mergedProviders);
    this.metrics = metrics;
    this.catalogCacheEnabled = catalogCacheEnabled;
    this.broadcaster = broadcaster;
  }

  private static List<BlobStoreProvider> mergeProviders(
      List<BlobStoreProvider> builtins, ObjectProvider<PluginRuntime> plugins) {
    List<BlobStoreProvider> merged = new ArrayList<>();
    Set<String> types = new HashSet<>();
    if (plugins != null) {
      plugins.ifAvailable(runtime -> runtime.blobStoreProviders().forEach(provider -> {
        if (types.add(BlobStoreDescriptor.normalize(provider.type()))) {
          merged.add(provider);
        }
      }));
    }
    for (BlobStoreProvider provider : builtins == null ? List.<BlobStoreProvider>of() : builtins) {
      if (types.add(BlobStoreDescriptor.normalize(provider.type()))) {
        merged.add(provider);
      }
    }
    return merged;
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
    this.providers = new BlobStoreProviders(legacyProviders(s3Factory, fileFactory, fallback));
    this.metrics = metrics;
    this.catalogCacheEnabled = catalogCacheEnabled;
    this.broadcaster = broadcaster;
  }

  /**
   * Adapts the pre-SPI factory constructors, which callers and tests still use directly. Both
   * providers are always registered so a row still resolves to the backend its type names; a
   * missing factory surfaces when storage is actually built rather than changing dispatch.
   */
  private static List<BlobStoreProvider> legacyProviders(
      S3BlobStorageFactory s3Factory, FileBlobStorageFactory fileFactory, S3StorageProperties fallback) {
    List<BlobStoreProvider> providers = new ArrayList<>(2);
    providers.add(new S3BlobStoreProvider(
        s3Factory, fallback == null ? new S3StorageProperties() : fallback));
    providers.add(new FileBlobStoreProvider(fileFactory));
    return providers;
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

  /** Reload blob_store after an admin mutation, retaining unchanged storage wrappers. */
  public void refreshAll() {
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
    invalidateStorageCache(blobStoreId);
    for (BlobStoreProvider provider : providers.all()) {
      provider.invalidate(blobStoreId);
    }
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
    // Only reconcile wrappers that predate this query. An uncached lookup can create a
    // wrapper for a newer committed configuration while this database read is in flight.
    Set<StorageCacheKey> cachedKeys = Set.copyOf(cache.asMap().keySet());
    List<BlobStoreRecord> records = blobStoreDao.list();
    reconcileStorageEntries(records, cachedKeys);
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
    if (catalogCacheEnabled) catalog.set(loaded);
    log.debug("Refreshed blob_store catalog from MySQL by {}: stores={}", reason, loaded.records().size());
    return loaded;
  }

  private void reconcileStorageEntries(List<BlobStoreRecord> records, Set<StorageCacheKey> cachedKeys) {
    Map<Long, BlobStoreRecord> active = new LinkedHashMap<>();
    for (BlobStoreRecord record : records) {
      if (record.id() != null) active.put(record.id(), record);
    }
    Set<Long> deletedIds = new HashSet<>();
    for (StorageCacheKey key : cachedKeys) {
      if (!active.containsKey(key.storeId())) deletedIds.add(key.storeId());
    }
    for (BlobStoreProvider provider : providers.all()) {
      Set<Long> stale = provider.cachedStoreIdsMissingFrom(active.keySet());
      if (stale != null) {
        deletedIds.addAll(stale);
      }
    }
    // A store may have been created after the list query. Confirm absence before closing
    // a client that another request could still be using.
    deletedIds.removeIf(id -> blobStoreDao.findById(id).isPresent());
    // Broadcasts and periodic refreshes must not stop multipart uploads on unchanged stores.
    // Include store ID in the key: identical configurations still own separate SDK clients.
    for (StorageCacheKey key : cachedKeys) {
      BlobStoreRecord record = active.get(key.storeId());
      if (deletedIds.contains(key.storeId())
          || (record != null && !key.signature().equals(storageSignature(record)))) {
        cache.invalidate(key);
      }
    }
    cache.cleanUp();
    for (BlobStoreProvider provider : providers.all()) {
      deletedIds.forEach(provider::invalidate);
    }
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

  private record StorageCacheKey(long storeId, String signature) {
  }

  private String storageSignature(BlobStoreRecord record) {
    BlobStoreDescriptor descriptor = toDescriptor(record);
    return signature(providers.require(descriptor), descriptor);
  }

  public BlobStorage forRecord(BlobStoreRecord record) {
    BlobStoreDescriptor descriptor = toDescriptor(record);
    BlobStoreProvider provider = providers.require(descriptor);
    return cache.get(new StorageCacheKey(descriptor.id(), signature(provider, descriptor)),
        key -> instrument(
            provider.create(descriptor), record.name(), record.type(), provider.engine(descriptor)));
  }

  /** The provider type participates so that retyping a store rebuilds its wrapper. */
  private static String signature(BlobStoreProvider provider, BlobStoreDescriptor descriptor) {
    return provider.type() + "|" + provider.signature(descriptor);
  }

  private static BlobStoreDescriptor toDescriptor(BlobStoreRecord record) {
    return new BlobStoreDescriptor(
        record.id() == null ? 0 : record.id(),
        record.name(),
        record.type(),
        record.endpoint(),
        record.region(),
        record.bucket(),
        record.prefix(),
        record.attributes());
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

  private void invalidateStorageCache(long storeId) {
    for (StorageCacheKey key : Set.copyOf(cache.asMap().keySet())) {
      if (key.storeId() == storeId) cache.invalidate(key);
    }
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

  /**
   * The store's row interpreted as S3 configuration, with {@code kkrepo.storage.s3.*} defaults
   * applied. Only meaningful for stores the S3 provider owns; throws if no S3 provider is
   * registered.
   */
  public S3BlobStoreConfig configFor(long blobStoreId) {
    BlobStoreDescriptor descriptor = toDescriptor(catalogRecord(blobStoreId));
    BlobStoreProvider provider = providers.byType(BlobStoreTypes.S3).orElse(null);
    if (!(provider instanceof S3BlobStoreProvider s3Provider)) {
      throw new IllegalStateException("No S3 blob store provider is registered");
    }
    return s3Provider.configFor(descriptor);
  }
}
