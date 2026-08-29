package com.github.klboke.kkrepo.server.helm;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Durable Helm group index cache metadata.
 *
 * <p>The merged YAML body remains an ordinary asset/blob in the group repository. This class only
 * stores a small version watermark in the asset attributes and recursively invalidates containing
 * groups after a member index changes. Node-local asset snapshots are optional and rebuildable;
 * the database watermark is the cross-replica source of freshness.
 */
@Service
public class HelmGroupIndexCache {
  static final String GROUP_INDEX_ATTRIBUTE = "helmGroupIndex";

  private static final Logger log = LoggerFactory.getLogger(HelmGroupIndexCache.class);
  private static final Object PENDING_MEMBERS_KEY =
      HelmGroupIndexCache.class.getName() + ".PENDING_MEMBERS";
  private static final Object PENDING_GROUPS_KEY =
      HelmGroupIndexCache.class.getName() + ".PENDING_GROUPS";

  private final RepositoryDao repositoryDao;
  private final AssetDao assetDao;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;
  private final boolean enabled;

  public HelmGroupIndexCache(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      @Value("${kkrepo.cache.helm-group-index.enabled:true}") boolean enabled) {
    this.repositoryDao = repositoryDao;
    this.assetDao = assetDao;
    this.assetMetadataCache = assetMetadataCache;
    this.cacheController = cacheController;
    this.enabled = enabled;
  }

  public boolean enabled() {
    return enabled;
  }

  public Optional<CachedAssetMetadata> findFresh(RepositoryRuntime group, Instant now) {
    if (!enabled) return Optional.empty();
    Optional<CachedAssetMetadata> cached = assetMetadataCache.find(
        group.id(),
        HelmHostedService.INDEX_PATH,
        () -> AssetMetadataCache.Loaded.from(
            assetDao.findAssetByPath(group.id(), HelmHostedService.INDEX_PATH), assetDao));
    if (cached.isEmpty() || cached.orElseThrow().blob() == null) return Optional.empty();
    CachedAssetMetadata snapshot = cached.orElseThrow();
    Map<String, Object> attributes = snapshot.attributes() == null
        ? Map.of()
        : snapshot.attributes();
    if (!"INDEX".equals(snapshot.kind())
        || !Boolean.TRUE.equals(attributes.get(GROUP_INDEX_ATTRIBUTE))) {
      return Optional.empty();
    }
    NexusLikeCacheInfo cacheInfo = NexusLikeCacheInfo.fromAttributes(attributes).orElse(null);
    if (cacheController.isStale(
        group.id(),
        NexusCacheType.METADATA,
        cacheInfo,
        group.effectiveMetadataMaxAgeMinutesOrDefault(),
        now)) {
      return Optional.empty();
    }
    return cached;
  }

  public NexusLikeCacheInfo current(RepositoryRuntime group, Instant now) {
    return cacheController.current(group.id(), NexusCacheType.METADATA, now);
  }

  public Map<String, Object> freshAttributes(NexusLikeCacheInfo cacheInfo) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(GROUP_INDEX_ATTRIBUTE, true);
    return NexusLikeCacheInfo.applyToAttributes(attributes, cacheInfo);
  }

  public void invalidateMemberAfterCommit(long memberRepositoryId) {
    if (!enabled) return;
    deferAfterCommit(PENDING_MEMBERS_KEY, memberRepositoryId, this::invalidateContainingGroups);
  }

  public void invalidateGroupAfterCommit(long groupId) {
    if (!enabled) return;
    deferAfterCommit(PENDING_GROUPS_KEY, groupId, this::invalidateGroupAndAncestors);
  }

  private void invalidateContainingGroups(long memberRepositoryId) {
    invalidateContainingGroups(memberRepositoryId, new HashSet<>());
  }

  private void invalidateContainingGroups(long memberRepositoryId, Set<Long> visited) {
    for (RepositoryRecord group : repositoryDao.listGroupsContaining(memberRepositoryId)) {
      Long groupId = group.id();
      if (groupId == null || !visited.add(groupId)) continue;
      invalidateToken(groupId);
      invalidateContainingGroups(groupId, visited);
    }
  }

  private void invalidateGroupAndAncestors(long groupId) {
    invalidateGroupAndAncestors(groupId, new HashSet<>());
  }

  private void invalidateGroupAndAncestors(long groupId, Set<Long> visited) {
    if (!visited.add(groupId)) return;
    invalidateToken(groupId);
    for (RepositoryRecord parent : repositoryDao.listGroupsContaining(groupId)) {
      if (parent.id() != null) invalidateGroupAndAncestors(parent.id(), visited);
    }
  }

  private void invalidateToken(long groupId) {
    try {
      // The public entrypoints already defer this resolver until commit. Invalidate directly here
      // so a callback running in afterCommit does not enqueue a second synchronization too late.
      cacheController.invalidate(groupId, NexusCacheType.METADATA);
    } catch (RuntimeException e) {
      log.warn("Failed invalidating Helm group index token for group {}", groupId, e);
    }
  }

  private <T> void deferAfterCommit(Object resourceKey, T item, Consumer<T> resolver) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      resolver.accept(item);
      return;
    }
    @SuppressWarnings("unchecked")
    Set<T> pending = (Set<T>) TransactionSynchronizationManager.getResource(resourceKey);
    if (pending == null) {
      pending = new HashSet<>();
      TransactionSynchronizationManager.bindResource(resourceKey, pending);
      Set<T> snapshot = pending;
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          for (T pendingItem : snapshot) resolver.accept(pendingItem);
        }

        @Override
        public void afterCompletion(int status) {
          TransactionSynchronizationManager.unbindResourceIfPossible(resourceKey);
        }
      });
    }
    pending.add(item);
  }
}
