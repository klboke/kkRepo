package com.github.klboke.kkrepo.server.repositories;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.catalog.CatalogCacheBroadcaster;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * In-memory snapshot of the full repository catalog (records + blob-store names + group members)
 * for the admin/browse repository listing path.
 *
 * <p>Mirrors {@link com.github.klboke.kkrepo.server.security.SecurityCatalogCache}: the snapshot
 * is loaded in a bounded number of queries (repository list + blob-store list + a single batched
 * group-member query) so {@code GET /internal/repositories} no longer fans out one
 * {@code listMembers} query per group repository. Mutations refresh the local snapshot after commit
 * and broadcast a refresh so sibling replicas converge within the broadcast poll interval rather
 * than waiting on a TTL.
 */
@Service
public class RepositoryCatalogCache {
  private static final Logger log = LoggerFactory.getLogger(RepositoryCatalogCache.class);
  private static final String CATALOG_NAME = "repository";
  private static final Object REFRESH_RESOURCE_KEY =
      RepositoryCatalogCache.class.getName() + ".REFRESH";

  private final RepositoryDao repositoryDao;
  private final BlobStoreDao blobStoreDao;
  private final boolean enabled;
  private final CatalogCacheBroadcaster broadcaster;
  private final AtomicReference<RepositoryCatalog> catalog = new AtomicReference<>();
  private final AtomicBoolean subscribed = new AtomicBoolean();
  private final ReentrantLock refreshLock = new ReentrantLock();

  @Autowired
  public RepositoryCatalogCache(
      RepositoryDao repositoryDao,
      BlobStoreDao blobStoreDao,
      @Value("${kkrepo.catalog-cache.enabled:true}") boolean enabled,
      ObjectProvider<CatalogCacheBroadcaster> broadcasterProvider) {
    this(repositoryDao, blobStoreDao, enabled,
        broadcasterProvider == null ? null : broadcasterProvider.getIfAvailable());
  }

  RepositoryCatalogCache(
      RepositoryDao repositoryDao,
      BlobStoreDao blobStoreDao,
      boolean enabled,
      CatalogCacheBroadcaster broadcaster) {
    this.repositoryDao = repositoryDao;
    this.blobStoreDao = blobStoreDao;
    this.enabled = enabled;
    this.broadcaster = broadcaster;
  }

  RepositoryCatalogCache(RepositoryDao repositoryDao, BlobStoreDao blobStoreDao, boolean enabled) {
    this(repositoryDao, blobStoreDao, enabled, (CatalogCacheBroadcaster) null);
  }

  public Optional<RepositoryCatalog> current() {
    if (!enabled) {
      return Optional.empty();
    }
    RepositoryCatalog local = catalog.get();
    if (local != null) {
      return Optional.of(local);
    }
    return Optional.ofNullable(refreshBlocking());
  }

  @EventListener(ApplicationReadyEvent.class)
  public void warmUp() {
    if (!enabled) {
      return;
    }
    subscribeToBroadcast();
    refreshSafely("startup");
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.catalog-cache.refresh-interval-ms:60000}",
      initialDelayString = "${kkrepo.catalog-cache.initial-delay-ms:60000}")
  public void syncDatabaseToMemory() {
    if (!enabled) {
      return;
    }
    if (!refreshLock.tryLock()) {
      return;
    }
    try {
      refreshLocked("scheduled");
    } catch (RuntimeException e) {
      log.warn("Failed refreshing repository catalog from MySQL; keeping previous in-memory snapshot", e);
    } finally {
      refreshLock.unlock();
    }
  }

  public void refreshAfterCommit() {
    if (!enabled) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      refreshSafely("mutation");
      publishRefresh();
      return;
    }
    if (TransactionSynchronizationManager.hasResource(REFRESH_RESOURCE_KEY)) {
      return;
    }
    TransactionSynchronizationManager.bindResource(REFRESH_RESOURCE_KEY, Boolean.TRUE);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        refreshSafely("mutation");
        publishRefresh();
      }

      @Override
      public void afterCompletion(int status) {
        TransactionSynchronizationManager.unbindResourceIfPossible(REFRESH_RESOURCE_KEY);
      }
    });
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
    if (broadcaster == null) {
      return;
    }
    broadcaster.publishRefresh(CATALOG_NAME);
  }

  private RepositoryCatalog refreshBlocking() {
    refreshLock.lock();
    try {
      return refreshLocked("lazy");
    } catch (RuntimeException e) {
      log.warn("Failed loading repository catalog from MySQL; falling back to direct queries", e);
      return null;
    } finally {
      refreshLock.unlock();
    }
  }

  private void refreshSafely(String reason) {
    refreshLock.lock();
    try {
      refreshLocked(reason);
    } catch (RuntimeException e) {
      log.warn("Failed refreshing repository catalog from MySQL; keeping previous in-memory snapshot", e);
    } finally {
      refreshLock.unlock();
    }
  }

  private RepositoryCatalog refreshLocked(String reason) {
    RepositoryCatalog loaded = loadCatalog();
    catalog.set(loaded);
    log.debug("Refreshed repository catalog from MySQL by {}: repositories={}, groups={}",
        reason, loaded.records().size(), loaded.membersByGroupId().size());
    return loaded;
  }

  private RepositoryCatalog loadCatalog() {
    List<RepositoryRecord> records = repositoryDao.list();

    Map<Long, String> blobStoreNames = new LinkedHashMap<>();
    for (BlobStoreRecord record : blobStoreDao.list()) {
      if (record.id() != null) {
        blobStoreNames.put(record.id(), record.name());
      }
    }

    Map<Long, List<String>> membersByGroupId = new LinkedHashMap<>();
    repositoryDao.listAllGroupMembers().forEach((groupId, members) ->
        membersByGroupId.put(groupId, List.copyOf(members)));

    return new RepositoryCatalog(
        Instant.now(),
        List.copyOf(records),
        Collections.unmodifiableMap(blobStoreNames),
        Collections.unmodifiableMap(membersByGroupId));
  }

  public record RepositoryCatalog(
      Instant loadedAt,
      List<RepositoryRecord> records,
      Map<Long, String> blobStoreNames,
      Map<Long, List<String>> membersByGroupId) {

    public List<String> membersOf(Long groupId) {
      return groupId == null ? List.of() : membersByGroupId.getOrDefault(groupId, List.of());
    }
  }
}
