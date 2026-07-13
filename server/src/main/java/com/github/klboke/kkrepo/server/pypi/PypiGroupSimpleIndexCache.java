package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
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
 * Nexus-like cache metadata for PyPI group simple indexes.
 *
 * <p>The merged root/project index HTML is stored as an index asset/blob in the group repository's
 * blob store. MySQL carries the small cache-info token through {@link NexusLikeCacheController}
 * and {@link AssetMetadataCache} only keeps optional node-local snapshots, so large simple-index
 * pages do not create oversized cache entries or stale versioned copies.
 */
@Service
public class PypiGroupSimpleIndexCache {
  static final String ROOT_INDEX = "root-index";
  static final String INDEX = "index";

  private static final Logger log = LoggerFactory.getLogger(PypiGroupSimpleIndexCache.class);
  private static final String GROUP_INDEX_ATTRIBUTE = "pypiGroupSimpleIndex";
  private static final Object PENDING_MEMBERS_KEY =
      PypiGroupSimpleIndexCache.class.getName() + ".PENDING_MEMBERS";
  private static final Object PENDING_MEMBER_ROOTS_KEY =
      PypiGroupSimpleIndexCache.class.getName() + ".PENDING_MEMBER_ROOTS";
  private static final Object PENDING_MEMBER_PROJECTS_KEY =
      PypiGroupSimpleIndexCache.class.getName() + ".PENDING_MEMBER_PROJECTS";
  private static final Object PENDING_GROUPS_KEY =
      PypiGroupSimpleIndexCache.class.getName() + ".PENDING_GROUPS";

  private final RepositoryDao repositoryDao;
  private final AssetDao assetDao;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;
  private final boolean enabled;

  public PypiGroupSimpleIndexCache(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      @Value("${kkrepo.cache.pypi-group-simple-index.enabled:true}") boolean enabled) {
    this.repositoryDao = repositoryDao;
    this.assetDao = assetDao;
    this.assetMetadataCache = assetMetadataCache;
    this.cacheController = cacheController;
    this.enabled = enabled;
  }

  public boolean enabled() {
    return enabled;
  }

  public Optional<CachedAssetMetadata> findFresh(
      RepositoryRuntime group,
      String path,
      String kind,
      Instant now) {
    if (!enabled) {
      return Optional.empty();
    }
    Optional<CachedAssetMetadata> cached = assetMetadataCache.find(
        group.id(),
        path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(group.id(), path), assetDao));
    if (cached.isEmpty() || cached.get().blob() == null) {
      return Optional.empty();
    }
    CachedAssetMetadata snapshot = cached.get();
    if (!kind.equals(snapshot.kind())) {
      return Optional.empty();
    }
    NexusLikeCacheInfo cacheInfo = NexusLikeCacheInfo.fromAttributes(snapshot.attributes()).orElse(null);
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

  public Map<String, Object> freshAttributes(RepositoryRuntime group, String projectName, Instant now) {
    if (!enabled) {
      return Map.of();
    }
    Map<String, Object> base = new LinkedHashMap<>();
    base.put(GROUP_INDEX_ATTRIBUTE, true);
    if (projectName != null && !projectName.isBlank()) {
      base.put("name", PypiPaths.normalizeName(projectName));
    }
    return NexusLikeCacheInfo.applyToAttributes(
        base,
        cacheController.current(group.id(), NexusCacheType.METADATA, now));
  }

  public void invalidateMemberAfterCommit(long memberRepositoryId) {
    if (!enabled) {
      return;
    }
    deferAfterCommit(PENDING_MEMBERS_KEY, memberRepositoryId, this::invalidateMemberGroups);
  }

  public void invalidateMemberRootAfterCommit(long memberRepositoryId) {
    if (!enabled) {
      return;
    }
    deferAfterCommit(PENDING_MEMBER_ROOTS_KEY, memberRepositoryId, this::invalidateMemberRoot);
  }

  public void invalidateMemberProjectAfterCommit(long memberRepositoryId, String projectName) {
    if (!enabled) {
      return;
    }
    if (projectName == null || projectName.isBlank()) {
      invalidateMemberAfterCommit(memberRepositoryId);
      return;
    }
    deferAfterCommit(
        PENDING_MEMBER_PROJECTS_KEY,
        new MemberProjectInvalidation(memberRepositoryId, PypiPaths.normalizeName(projectName)),
        this::invalidateMemberProject);
  }

  public void invalidateGroupAfterCommit(long groupId) {
    if (!enabled) {
      return;
    }
    deferAfterCommit(PENDING_GROUPS_KEY, groupId, this::invalidateGroupAndAncestors);
  }

  private void invalidateMemberGroups(long memberRepositoryId) {
    invalidateMemberGroups(memberRepositoryId, new HashSet<>());
  }

  private void invalidateMemberGroups(long memberRepositoryId, Set<Long> visited) {
    for (RepositoryRecord group : repositoryDao.listGroupsContaining(memberRepositoryId)) {
      Long groupId = group.id();
      if (groupId == null || !visited.add(groupId)) {
        continue;
      }
      invalidateGroupToken(groupId);
      invalidateMemberGroups(groupId, visited);
    }
  }

  private void invalidateMemberRoot(long memberRepositoryId) {
    invalidateMemberRoot(memberRepositoryId, new HashSet<>());
  }

  private void invalidateMemberRoot(long memberRepositoryId, Set<Long> visited) {
    for (RepositoryRecord group : repositoryDao.listGroupsContaining(memberRepositoryId)) {
      Long groupId = group.id();
      if (groupId == null || !visited.add(groupId)) {
        continue;
      }
      invalidateGroupPath(groupId, PypiPaths.INDEX_PREFIX);
      invalidateMemberRoot(groupId, visited);
    }
  }

  private void invalidateMemberProject(MemberProjectInvalidation invalidation) {
    invalidateMemberProject(invalidation.memberRepositoryId(), invalidation.projectName(), new HashSet<>());
  }

  private void invalidateMemberProject(long memberRepositoryId, String projectName, Set<Long> visited) {
    for (RepositoryRecord group : repositoryDao.listGroupsContaining(memberRepositoryId)) {
      Long groupId = group.id();
      if (groupId == null || !visited.add(groupId)) {
        continue;
      }
      invalidateGroupPath(groupId, PypiPaths.INDEX_PREFIX);
      invalidateGroupPath(groupId, PypiPaths.indexPath(projectName));
      invalidateMemberProject(groupId, projectName, visited);
    }
  }

  private void invalidateGroupAndAncestors(long groupId) {
    invalidateGroupAndAncestors(groupId, new HashSet<>());
  }

  private void invalidateGroupAndAncestors(long groupId, Set<Long> visited) {
    if (!visited.add(groupId)) {
      return;
    }
    invalidateGroupToken(groupId);
    for (RepositoryRecord parent : repositoryDao.listGroupsContaining(groupId)) {
      Long parentId = parent.id();
      if (parentId != null) {
        invalidateGroupAndAncestors(parentId, visited);
      }
    }
  }

  private void invalidateGroupToken(long groupId) {
    try {
      cacheController.invalidateAfterCommit(groupId, NexusCacheType.METADATA);
    } catch (RuntimeException e) {
      log.warn("Failed invalidating PyPI group simple-index token for group {}", groupId, e);
    }
  }

  private void invalidateGroupPath(long groupId, String path) {
    try {
      Optional<AssetRecord> existing = assetDao.findAssetByPath(groupId, path);
      if (existing.isEmpty()) {
        return;
      }
      AssetRecord asset = existing.get();
      Map<String, Object> attributes = new LinkedHashMap<>();
      if (asset.attributes() != null) {
        attributes.putAll(asset.attributes());
      }
      Instant lastVerified = NexusLikeCacheInfo.fromAttributes(attributes)
          .map(NexusLikeCacheInfo::lastVerified)
          .orElse(Instant.now());
      Map<String, Object> updated = NexusLikeCacheInfo.applyToAttributes(
          attributes,
          new NexusLikeCacheInfo(lastVerified, NexusLikeCacheInfo.INVALIDATED, NexusCacheType.METADATA));
      assetDao.updateAssetAttributes(asset.id(), updated);
      assetMetadataCache.evictAfterCommit(groupId, path);
    } catch (RuntimeException e) {
      log.warn("Failed invalidating PyPI group simple-index asset for group {} path {}", groupId, path, e);
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
          for (T pendingItem : snapshot) {
            resolver.accept(pendingItem);
          }
        }

        @Override
        public void afterCompletion(int status) {
          TransactionSynchronizationManager.unbindResourceIfPossible(resourceKey);
        }
      });
    }
    pending.add(item);
  }

  private record MemberProjectInvalidation(long memberRepositoryId, String projectName) {}
}
