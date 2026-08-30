package com.github.klboke.kkrepo.server.helm;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
  static final String MEMBER_INDEX_FRESH_UNTIL_ATTRIBUTE = "helmGroupMemberIndexFreshUntil";
  static final String CONFIGURATION_FINGERPRINT_ATTRIBUTE = "helmGroupConfiguration";

  private static final Logger log = LoggerFactory.getLogger(HelmGroupIndexCache.class);
  private static final Object PENDING_GROUPS_KEY =
      HelmGroupIndexCache.class.getName() + ".PENDING_GROUPS";

  private final RepositoryDao repositoryDao;
  private final AssetDao assetDao;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;
  private final RepositoryIndexRebuildDao rebuildQueue;
  private final boolean enabled;

  public HelmGroupIndexCache(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      RepositoryIndexRebuildDao rebuildQueue,
      @Value("${kkrepo.cache.helm-group-index.enabled:true}") boolean enabled) {
    this.repositoryDao = repositoryDao;
    this.assetDao = assetDao;
    this.assetMetadataCache = assetMetadataCache;
    this.cacheController = cacheController;
    this.rebuildQueue = rebuildQueue;
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
    if (!configurationFingerprint(group).equals(
        stringAttribute(attributes, CONFIGURATION_FINGERPRINT_ATTRIBUTE))) {
      return Optional.empty();
    }
    if (attributes.containsKey(MEMBER_INDEX_FRESH_UNTIL_ATTRIBUTE)) {
      Instant memberIndexFreshUntil = instantAttribute(
          attributes, MEMBER_INDEX_FRESH_UNTIL_ATTRIBUTE);
      if (memberIndexFreshUntil == null || !now.isBefore(memberIndexFreshUntil)) {
        return Optional.empty();
      }
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

  public Map<String, Object> freshAttributes(
      RepositoryRuntime group,
      NexusLikeCacheInfo cacheInfo,
      Instant memberIndexFreshUntil) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(GROUP_INDEX_ATTRIBUTE, true);
    attributes.put(CONFIGURATION_FINGERPRINT_ATTRIBUTE, configurationFingerprint(group));
    if (memberIndexFreshUntil != null) {
      attributes.put(MEMBER_INDEX_FRESH_UNTIL_ATTRIBUTE, memberIndexFreshUntil.toString());
    }
    return NexusLikeCacheInfo.applyToAttributes(attributes, cacheInfo);
  }

  public Instant memberIndexFreshUntil(CachedAssetMetadata metadata) {
    return metadata == null
        ? null
        : instantAttribute(metadata.attributes(), MEMBER_INDEX_FRESH_UNTIL_ATTRIBUTE);
  }

  private static Instant instantAttribute(Map<String, Object> attributes, String name) {
    if (attributes == null) return null;
    Object raw = attributes.get(name);
    if (raw == null || raw.toString().isBlank()) return null;
    try {
      return Instant.parse(raw.toString());
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  static String configurationFingerprint(RepositoryRuntime group) {
    StringBuilder material = new StringBuilder();
    appendRuntimeConfiguration(material, group, new HashSet<>());
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(material.toString().getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JRE", e);
    }
  }

  private static void appendRuntimeConfiguration(
      StringBuilder material, RepositoryRuntime runtime, Set<Long> resolvingGroups) {
    if (runtime == null) {
      appendFingerprintField(material, null);
      return;
    }
    appendFingerprintField(material, Long.toString(runtime.id()));
    appendFingerprintField(material, runtime.format() == null ? null : runtime.format().name());
    appendFingerprintField(material, runtime.type() == null ? null : runtime.type().name());
    appendFingerprintField(material, Boolean.toString(runtime.online()));
    if (runtime.isProxy()) {
      appendFingerprintField(material, HelmProxyService.configurationFingerprint(runtime));
    }
    if (!runtime.isGroup()) return;
    if (!resolvingGroups.add(runtime.id())) {
      appendFingerprintField(material, "cycle");
      return;
    }
    try {
      if (runtime.members() != null) {
        for (RepositoryRuntime member : runtime.members()) {
          appendRuntimeConfiguration(material, member, resolvingGroups);
        }
      }
    } finally {
      resolvingGroups.remove(runtime.id());
    }
  }

  private static void appendFingerprintField(StringBuilder material, String value) {
    String normalized = value == null ? "" : value;
    material.append(normalized.length()).append(':').append(normalized).append(';');
  }

  private static String stringAttribute(Map<String, Object> attributes, String name) {
    Object raw = attributes == null ? null : attributes.get(name);
    return raw == null ? null : raw.toString();
  }

  public void invalidateMemberAfterCommit(long memberRepositoryId) {
    if (!enabled) return;
    Set<Long> groups = new LinkedHashSet<>();
    collectContainingGroups(memberRepositoryId, groups);
    enqueueInvalidations(groups);
  }

  public void invalidateGroupAfterCommit(long groupId) {
    if (!enabled) return;
    Set<Long> groups = new LinkedHashSet<>();
    collectGroupAndAncestors(groupId, groups);
    enqueueInvalidations(groups);
  }

  /** Called by the durable repository-index worker after it claims an invalidation marker. */
  public void retryInvalidation(long groupId) {
    if (!enabled) return;
    cacheController.invalidateOrThrow(groupId, NexusCacheType.METADATA);
  }

  private void collectContainingGroups(long memberRepositoryId, Set<Long> groups) {
    for (RepositoryRecord group : repositoryDao.listGroupsContaining(memberRepositoryId)) {
      Long groupId = group.id();
      if (groupId == null || !groups.add(groupId)) continue;
      collectContainingGroups(groupId, groups);
    }
  }

  private void collectGroupAndAncestors(long groupId, Set<Long> groups) {
    if (!groups.add(groupId)) return;
    for (RepositoryRecord parent : repositoryDao.listGroupsContaining(groupId)) {
      if (parent.id() != null) collectGroupAndAncestors(parent.id(), groups);
    }
  }

  private void enqueueInvalidations(Set<Long> groups) {
    for (long groupId : groups) {
      // The marker participates in the member-index write transaction. A committed member change
      // therefore always leaves durable retry work before the best-effort afterCommit fast path.
      rebuildQueue.enqueue(
          groupId,
          RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION,
          RepositoryIndexRebuildDao.ROOT_SCOPE);
      deferAfterCommit(PENDING_GROUPS_KEY, groupId, this::attemptQueuedInvalidation);
    }
  }

  private void attemptQueuedInvalidation(long groupId) {
    try {
      cacheController.invalidateOrThrow(groupId, NexusCacheType.METADATA);
    } catch (RuntimeException e) {
      log.warn(
          "Failed invalidating Helm group index token for group {}; durable retry retained",
          groupId,
          e);
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
