package com.github.klboke.kkrepo.server.cache;

import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Shared cache for group file fan-out winners.
 *
 * <p>The cached value is only the member repository id that most recently served a path. The blob
 * still comes from the member repository's hosted/proxy path, so losing this cache only reverts to
 * member fan-out. Correctness is guarded by group repository cache tokens: member writes/deletes and
 * repository membership/config changes invalidate the token for every containing group after
 * commit, causing stale entries to be ignored by all replicas.
 */
@Service
public class GroupMemberAssetCache {
  private static final Logger log = LoggerFactory.getLogger(GroupMemberAssetCache.class);
  private static final String NAMESPACE = "group-member-asset";
  private static final Object PENDING_MEMBERS =
      GroupMemberAssetCache.class.getName() + ".PENDING_MEMBERS";
  private static final Object PENDING_GROUPS =
      GroupMemberAssetCache.class.getName() + ".PENDING_GROUPS";

  private final SharedCache sharedCache;
  private final RepositoryDao repositoryDao;
  private final NexusLikeCacheController cacheController;
  private final boolean enabled;
  private final Duration entryTtl;
  private final int maxAgeMinutes;

  public GroupMemberAssetCache(
      SharedCache sharedCache,
      RepositoryDao repositoryDao,
      NexusLikeCacheController cacheController,
      @Value("${kkrepo.cache.group-member-asset.enabled:true}") boolean enabled,
      @Value("${kkrepo.cache.group-member-asset.ttl-seconds:60}") long ttlSeconds) {
    this.sharedCache = sharedCache;
    this.repositoryDao = repositoryDao;
    this.cacheController = cacheController;
    this.enabled = enabled;
    this.entryTtl = ttlSeconds <= 0 ? Duration.ZERO : Duration.ofSeconds(ttlSeconds);
    this.maxAgeMinutes = ttlSeconds <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (ttlSeconds + 59) / 60);
  }

  public Optional<Long> get(RepositoryRuntime group, String path, NexusCacheType type) {
    if (!enabled || entryTtl.isZero() || group == null || path == null || cacheController == null) {
      return Optional.empty();
    }
    try {
      Optional<Entry> entry = sharedCache.getJson(NAMESPACE, key(group.id(), type, path), Entry.class);
      if (entry.isEmpty()) {
        return Optional.empty();
      }
      NexusCacheType cacheType = type == null ? NexusCacheType.CONTENT : type;
      if (cacheController.isStale(group.id(), cacheType, entry.get().cacheInfo(), maxAgeMinutes, Instant.now())) {
        return Optional.empty();
      }
      return Optional.of(entry.get().memberRepositoryId());
    } catch (RuntimeException e) {
      log.warn("Failed reading group member asset cache for group {} path {}", group.id(), path, e);
      return Optional.empty();
    }
  }

  public void put(RepositoryRuntime group, String path, NexusCacheType type, long memberRepositoryId) {
    if (!enabled || entryTtl.isZero() || group == null || path == null || cacheController == null) {
      return;
    }
    NexusCacheType cacheType = type == null ? NexusCacheType.CONTENT : type;
    try {
      sharedCache.putJson(
          NAMESPACE,
          key(group.id(), cacheType, path),
          new Entry(memberRepositoryId, cacheController.current(group.id(), cacheType, Instant.now())),
          entryTtl);
    } catch (RuntimeException e) {
      log.warn("Failed writing group member asset cache for group {} path {}", group.id(), path, e);
    }
  }

  public void evict(RepositoryRuntime group, String path, NexusCacheType type) {
    if (!enabled || group == null || path == null) {
      return;
    }
    try {
      sharedCache.evict(NAMESPACE, key(group.id(), type, path));
    } catch (RuntimeException e) {
      log.warn("Failed evicting group member asset cache for group {} path {}", group.id(), path, e);
    }
  }

  public void invalidateMemberAfterCommit(long memberRepositoryId) {
    if (!enabled || cacheController == null) {
      return;
    }
    deferAfterCommit(PENDING_MEMBERS, memberRepositoryId, this::invalidateContainingGroups);
  }

  public void invalidateGroupAfterCommit(long groupId) {
    if (!enabled || cacheController == null) {
      return;
    }
    deferAfterCommit(PENDING_GROUPS, groupId, this::invalidateGroupAndAncestors);
  }

  private void invalidateContainingGroups(long memberRepositoryId) {
    invalidateContainingGroups(memberRepositoryId, new HashSet<>());
  }

  private void invalidateContainingGroups(long memberRepositoryId, Set<Long> visited) {
    for (RepositoryRecord group : repositoryDao.listGroupsContaining(memberRepositoryId)) {
      Long groupId = group.id();
      if (groupId == null || !visited.add(groupId)) {
        continue;
      }
      cacheController.invalidateAll(groupId);
      invalidateContainingGroups(groupId, visited);
    }
  }

  private void invalidateGroupAndAncestors(long groupId) {
    invalidateGroupAndAncestors(groupId, new HashSet<>());
  }

  private void invalidateGroupAndAncestors(long groupId, Set<Long> visited) {
    if (!visited.add(groupId)) {
      return;
    }
    cacheController.invalidateAll(groupId);
    for (RepositoryRecord parent : repositoryDao.listGroupsContaining(groupId)) {
      if (parent.id() != null) {
        invalidateGroupAndAncestors(parent.id(), visited);
      }
    }
  }

  private void deferAfterCommit(Object resourceKey, long id, java.util.function.LongConsumer action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.accept(id);
      return;
    }
    @SuppressWarnings("unchecked")
    Set<Long> pending = (Set<Long>) TransactionSynchronizationManager.getResource(resourceKey);
    if (pending == null) {
      pending = new HashSet<>();
      TransactionSynchronizationManager.bindResource(resourceKey, pending);
      Set<Long> snapshot = pending;
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          for (Long pendingId : snapshot) {
            action.accept(pendingId);
          }
        }

        @Override
        public void afterCompletion(int status) {
          TransactionSynchronizationManager.unbindResourceIfPossible(resourceKey);
        }
      });
    }
    pending.add(id);
  }

  private static String key(long groupId, NexusCacheType type, String path) {
    NexusCacheType cacheType = type == null ? NexusCacheType.CONTENT : type;
    return groupId + ":" + cacheType.name() + ":" + path;
  }

  public record Entry(long memberRepositoryId, NexusLikeCacheInfo cacheInfo) {}
}
