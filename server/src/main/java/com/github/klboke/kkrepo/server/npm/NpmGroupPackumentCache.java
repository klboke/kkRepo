package com.github.klboke.kkrepo.server.npm;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Nexus-like cache metadata for npm group package roots.
 *
 * <p>The merged packument body itself is stored as a package-root asset/blob in the group
 * repository's blob store. The large JSON payload is intentionally not stored in process memory.
 * This class only coordinates freshness and invalidation, using the same cache-info semantics as proxy
 * metadata: cached assets are reusable while their cache token and max-age are still fresh, member
 * package changes mark the matching group package-root asset invalidated, and group configuration
 * changes invalidate the group's metadata token.
 */
@Service
public class NpmGroupPackumentCache {
  private static final Logger log = LoggerFactory.getLogger(NpmGroupPackumentCache.class);
  private static final String VARIANT_ATTRIBUTE = "packumentVariant";
  private static final String POLICY_VALID_UNTIL_ATTRIBUTE = "minimumReleaseAgeValidUntil";
  private static final Object PENDING_MEMBERS_KEY =
      NpmGroupPackumentCache.class.getName() + ".PENDING_MEMBERS";
  private static final Object PENDING_MEMBER_PACKAGES_KEY =
      NpmGroupPackumentCache.class.getName() + ".PENDING_MEMBER_PACKAGES";
  private static final Object PENDING_GROUPS_KEY =
      NpmGroupPackumentCache.class.getName() + ".PENDING_GROUPS";

  private final RepositoryDao repositoryDao;
  private final AssetDao assetDao;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;
  private final boolean enabled;

  public NpmGroupPackumentCache(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      @Value("${kkrepo.cache.npm-group-packument.enabled:true}") boolean enabled) {
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
      NpmPackageId packageId,
      Instant now) {
    return findFresh(group, packageId, NpmPackumentVariant.FULL, now);
  }

  public Optional<CachedAssetMetadata> findFresh(
      RepositoryRuntime group,
      NpmPackageId packageId,
      NpmPackumentVariant variant,
      Instant now) {
    if (!enabled) {
      return Optional.empty();
    }
    String path = variant.cachePath(packageId);
    Optional<CachedAssetMetadata> cached = assetMetadataCache.find(
        group.id(),
        path,
        () -> AssetMetadataCache.Loaded.from(
            assetDao.findAssetByPath(group.id(), path), assetDao));
    if (cached.isEmpty() || cached.get().blob() == null) {
      return Optional.empty();
    }
    CachedAssetMetadata snapshot = cached.get();
    if (!variant.assetKind().equals(snapshot.kind())) {
      return Optional.empty();
    }
    Instant policyValidUntil = instantAttribute(
        snapshot.attributes(), POLICY_VALID_UNTIL_ATTRIBUTE);
    if (policyValidUntil != null && !now.isBefore(policyValidUntil)) {
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

  public Map<String, Object> freshAttributes(RepositoryRuntime group, Instant now) {
    return freshAttributes(group, now, NpmPackumentVariant.FULL);
  }

  public Map<String, Object> freshAttributes(
      RepositoryRuntime group,
      Instant now,
      NpmPackumentVariant variant) {
    return freshAttributes(group, now, variant, null);
  }

  public Map<String, Object> freshAttributes(
      RepositoryRuntime group,
      Instant now,
      NpmPackumentVariant variant,
      Instant minimumReleaseAgeValidUntil) {
    if (!enabled) {
      return Map.of();
    }
    Map<String, Object> base = new LinkedHashMap<>();
    base.put(VARIANT_ATTRIBUTE, variant.name());
    if (minimumReleaseAgeValidUntil != null) {
      base.put(POLICY_VALID_UNTIL_ATTRIBUTE, minimumReleaseAgeValidUntil.toString());
    }
    return NexusLikeCacheInfo.applyToAttributes(
        base,
        cacheController.current(group.id(), NexusCacheType.METADATA, now));
  }

  public Instant minimumReleaseAgeValidUntil(CachedAssetMetadata metadata) {
    return metadata == null
        ? null
        : instantAttribute(metadata.attributes(), POLICY_VALID_UNTIL_ATTRIBUTE);
  }

  private static Instant instantAttribute(Map<String, Object> attributes, String name) {
    if (attributes == null) {
      return null;
    }
    Object value = attributes.get(name);
    if (value == null || value.toString().isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value.toString());
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  /**
   * Invalidate all cached package roots in every group containing {@code memberRepositoryId}.
   * Used for repository-level changes where the affected package id is not known.
   */
  public void invalidateMemberAfterCommit(long memberRepositoryId) {
    if (!enabled) {
      return;
    }
    deferAfterCommit(PENDING_MEMBERS_KEY, memberRepositoryId, this::invalidateMemberGroups);
  }

  /**
   * Mark the matching package-root cache asset invalidated in every group containing the member,
   * plus ancestor groups.
   */
  public void invalidateMemberPackageAfterCommit(long memberRepositoryId, String packageId) {
    if (!enabled) {
      return;
    }
    if (packageId == null || packageId.isBlank()) {
      invalidateMemberAfterCommit(memberRepositoryId);
      return;
    }
    deferAfterCommit(
        PENDING_MEMBER_PACKAGES_KEY,
        new MemberPackageInvalidation(memberRepositoryId, packageId),
        this::invalidateMemberPackage);
  }

  /**
   * Invalidate the group's metadata token, then recursively invalidate ancestor group tokens.
   * Used after membership, online flag, or storage configuration changes.
   */
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

  private void invalidateMemberPackage(MemberPackageInvalidation invalidation) {
    invalidateMemberPackage(invalidation.memberRepositoryId(), invalidation.packageId(), new HashSet<>());
  }

  private void invalidateMemberPackage(long memberRepositoryId, String packageId, Set<Long> visited) {
    for (RepositoryRecord group : repositoryDao.listGroupsContaining(memberRepositoryId)) {
      Long groupId = group.id();
      if (groupId == null || !visited.add(groupId)) {
        continue;
      }
      invalidateGroupPackage(groupId, packageId);
      invalidateMemberPackage(groupId, packageId, visited);
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
      log.warn("Failed invalidating npm group packument token for group {}", groupId, e);
    }
  }

  private void invalidateGroupPackage(long groupId, String packageId) {
    try {
      for (String path : cachePaths(packageId)) {
        Optional<AssetRecord> existing = assetDao.findAssetByPath(groupId, path);
        if (existing.isEmpty()) {
          continue;
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
      }
    } catch (RuntimeException e) {
      log.warn("Failed invalidating npm group packument asset for group {} package {}",
          groupId, packageId, e);
    }
  }

  private static List<String> cachePaths(String packageId) {
    try {
      NpmPackageId parsed = NpmPackageId.parse(packageId);
      List<String> paths = new ArrayList<>();
      for (NpmPackumentVariant variant : NpmPackumentVariant.values()) {
        paths.add(variant.cachePath(parsed));
      }
      return paths;
    } catch (RuntimeException e) {
      return List.of(packageId);
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

  private record MemberPackageInvalidation(long memberRepositoryId, String packageId) {}
}
