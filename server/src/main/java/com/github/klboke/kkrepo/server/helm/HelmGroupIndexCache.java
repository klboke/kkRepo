package com.github.klboke.kkrepo.server.helm;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
  static final String MEMBER_ASSET_GENERATION_ATTRIBUTE = "helmGroupMemberAssetGeneration";
  static final String CONFIGURATION_FINGERPRINT_ATTRIBUTE = "helmGroupConfiguration";

  private static final Logger log = LoggerFactory.getLogger(HelmGroupIndexCache.class);
  private static final Object PENDING_GROUPS_KEY =
      HelmGroupIndexCache.class.getName() + ".PENDING_GROUPS";

  private final RepositoryDao repositoryDao;
  private final AssetDao assetDao;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;
  private final RepositoryIndexRebuildDao rebuildQueue;
  private final TransactionOperations transactions;
  private final boolean enabled;

  @Autowired
  public HelmGroupIndexCache(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      RepositoryIndexRebuildDao rebuildQueue,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.cache.helm-group-index.enabled:true}") boolean enabled) {
    this(
        repositoryDao,
        assetDao,
        assetMetadataCache,
        cacheController,
        rebuildQueue,
        newInvalidationTransactions(transactionManager),
        enabled);
  }

  HelmGroupIndexCache(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      RepositoryIndexRebuildDao rebuildQueue,
      boolean enabled) {
    this(
        repositoryDao,
        assetDao,
        assetMetadataCache,
        cacheController,
        rebuildQueue,
        TransactionOperations.withoutTransaction(),
        enabled);
  }

  private HelmGroupIndexCache(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      RepositoryIndexRebuildDao rebuildQueue,
      TransactionOperations transactions,
      boolean enabled) {
    this.repositoryDao = repositoryDao;
    this.assetDao = assetDao;
    this.assetMetadataCache = assetMetadataCache;
    this.cacheController = cacheController;
    this.rebuildQueue = rebuildQueue;
    this.transactions = transactions;
    this.enabled = enabled;
  }

  private static TransactionOperations newInvalidationTransactions(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    // afterCommit callbacks still run before the original transaction resources are unbound.
    // Always suspend those resources so the marker acknowledgement and watermark bumps receive
    // their own commit or rollback boundary.
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transactions;
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
    String storedMemberGeneration =
        stringAttribute(attributes, MEMBER_ASSET_GENERATION_ATTRIBUTE);
    String currentMemberGeneration;
    try {
      currentMemberGeneration = memberAssetGeneration(group);
    } catch (RuntimeException e) {
      log.warn(
          "Failed reading Helm group member generation for {}; invalidating cached state",
          group.name(),
          e);
      invalidateGroupAfterCommit(group.id());
      return Optional.empty();
    }
    if (storedMemberGeneration == null
        || !storedMemberGeneration.equals(currentMemberGeneration)) {
      // The source binding changed without this group observing its normal marker path. Convert
      // that mixed-version or failed-callback write into the same durable CONTENT+METADATA
      // invalidation used by new writers before this request can consult a cached member winner.
      invalidateGroupAfterCommit(group.id());
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
    return freshAttributes(
        group, cacheInfo, memberIndexFreshUntil, memberAssetGeneration(group));
  }

  public Map<String, Object> freshAttributes(
      RepositoryRuntime group,
      NexusLikeCacheInfo cacheInfo,
      Instant memberIndexFreshUntil,
      String memberAssetGeneration) {
    if (memberAssetGeneration == null || memberAssetGeneration.isBlank()) {
      throw new IllegalArgumentException("Helm group member asset generation is required");
    }
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(GROUP_INDEX_ATTRIBUTE, true);
    attributes.put(CONFIGURATION_FINGERPRINT_ATTRIBUTE, configurationFingerprint(group));
    attributes.put(MEMBER_ASSET_GENERATION_ATTRIBUTE, memberAssetGeneration);
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
    return sha256(material);
  }

  /**
   * Hashes the transactionally durable {@code index.yaml} asset binding for every direct and
   * nested member.
   *
   * <p>The asset row and blob binding are the relational source used to build a group index and
   * are inserted, replaced, or deleted in the writer transaction. Reading those bindings directly
   * avoids an after-commit callback gap, so the fence remains effective while replicas are
   * upgraded gradually and when a writer's best-effort cache eviction fails.
   */
  public String memberAssetGeneration(RepositoryRuntime group) {
    return assetGeneration(group, List.of(HelmHostedService.INDEX_PATH));
  }

  /**
   * Hashes both the member indexes that select a release and the exact requested asset binding.
   *
   * <p>The path binding closes the rolling-upgrade gap for package and provenance writes made by
   * an older replica that does not enqueue Helm group invalidation markers. A later winner read
   * observes the committed asset row directly even when the group CONTENT token did not advance.
   */
  public String winnerAssetGeneration(RepositoryRuntime group, String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("Helm winner asset path is required");
    }
    return assetGeneration(
        group,
        HelmHostedService.INDEX_PATH.equals(path)
            ? List.of(HelmHostedService.INDEX_PATH)
            : List.of(HelmHostedService.INDEX_PATH, path));
  }

  private String assetGeneration(RepositoryRuntime group, List<String> paths) {
    Set<Long> memberRepositoryIds = new LinkedHashSet<>();
    collectMemberRepositoryIds(group, memberRepositoryIds, new HashSet<>());
    StringBuilder material = new StringBuilder();
    for (String path : paths) {
      appendFingerprintField(material, path);
      Map<Long, AssetRecord> bindings = assetDao.findAssetsByPathHash(
          memberRepositoryIds, PersistenceHashes.pathHash(path));
      if (bindings == null) {
        throw new IllegalStateException("Asset DAO returned no Helm member asset bindings");
      }
      for (long memberRepositoryId : memberRepositoryIds) {
        appendFingerprintField(material, Long.toString(memberRepositoryId));
        AssetRecord asset = bindings.get(memberRepositoryId);
        appendFingerprintField(material, asset == null || asset.id() == null
            ? null
            : Long.toString(asset.id()));
        appendFingerprintField(material, asset == null || asset.assetBlobId() == null
            ? null
            : Long.toString(asset.assetBlobId()));
        appendFingerprintField(material, asset == null || asset.lastUpdatedAt() == null
            ? null
            : asset.lastUpdatedAt().toString());
      }
    }
    return sha256(material);
  }

  private static void collectMemberRepositoryIds(
      RepositoryRuntime runtime,
      Set<Long> memberRepositoryIds,
      Set<Long> resolvingGroups) {
    if (runtime == null || !runtime.isGroup() || !resolvingGroups.add(runtime.id())) return;
    try {
      if (runtime.members() == null) return;
      for (RepositoryRuntime member : runtime.members()) {
        if (member == null || !member.online() || member.format() != RepositoryFormat.HELM) {
          continue;
        }
        memberRepositoryIds.add(member.id());
        if (member.isGroup()) {
          collectMemberRepositoryIds(member, memberRepositoryIds, resolvingGroups);
        }
      }
    } finally {
      resolvingGroups.remove(runtime.id());
    }
  }

  private static String sha256(CharSequence material) {
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
    invalidateMemberAfterCommit(
        memberRepositoryId, RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION);
  }

  /**
   * Invalidates only ordered content winners after a package or provenance asset changes.
   *
   * <p>The member index did not change, so rebuilding every containing group's merged index would
   * only create avoidable metadata work.
   */
  public void invalidateMemberContentAfterCommit(long memberRepositoryId) {
    invalidateMemberAfterCommit(
        memberRepositoryId, RepositoryIndexRebuildDao.HELM_GROUP_CONTENT_INVALIDATION);
  }

  private void invalidateMemberAfterCommit(long memberRepositoryId, String invalidationKind) {
    Set<Long> groups = new LinkedHashSet<>();
    collectContainingGroups(memberRepositoryId, groups);
    enqueueInvalidations(groups, invalidationKind);
  }

  public void invalidateGroupAfterCommit(long groupId) {
    Set<Long> groups = new LinkedHashSet<>();
    collectGroupAndAncestors(groupId, groups);
    enqueueInvalidations(groups, RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION);
  }

  /** Called by the durable repository-index worker after it claims an invalidation marker. */
  public void retryInvalidation(long groupId) {
    retryInvalidation(groupId, RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION);
  }

  /** Called by the durable repository-index worker after it claims an invalidation marker. */
  public void retryInvalidation(long groupId, String invalidationKind) {
    boolean invalidateMetadata =
        RepositoryIndexRebuildDao.HELM_GROUP_INVALIDATION.equals(invalidationKind);
    if (!invalidateMetadata
        && !RepositoryIndexRebuildDao.HELM_GROUP_CONTENT_INVALIDATION.equals(invalidationKind)) {
      throw new IllegalArgumentException(
          "Unsupported Helm group invalidation kind: " + invalidationKind);
    }
    // Winner selection must move first. If the metadata bump then fails, the durable marker stays
    // queued while no replica can keep serving a member winner selected before this content change.
    cacheController.invalidateOrThrow(groupId, NexusCacheType.CONTENT);
    if (invalidateMetadata) {
      cacheController.invalidateOrThrow(groupId, NexusCacheType.METADATA);
    }
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

  private void enqueueInvalidations(Set<Long> groups, String invalidationKind) {
    for (long groupId : groups) {
      // The marker participates in the member-asset write transaction. A committed member change
      // therefore always leaves durable retry work before the best-effort afterCommit fast path.
      String requestToken =
          rebuildQueue.enqueueHelmGroupInvalidation(groupId, invalidationKind);
      if (requestToken == null || requestToken.isBlank()) {
        throw new IllegalStateException(
            "Repository index rebuild queue returned an empty Helm invalidation token");
      }
      deferAfterCommit(new PendingInvalidation(groupId, invalidationKind, requestToken));
    }
  }

  private void attemptQueuedInvalidation(PendingInvalidation invalidation) {
    try {
      Boolean applied = transactions.execute(status -> {
        boolean claimed = rebuildQueue.acknowledgeHelmGroupInvalidationIfRequestToken(
            invalidation.groupId(),
            invalidation.invalidationKind(),
            invalidation.requestToken());
        if (!claimed) return false;
        // The conditional marker delete and both watermark bumps share one transaction. A failure
        // restores the marker, while a worker or newer enqueue that wins the row lock makes this
        // fast path a no-op instead of advancing the same generation twice.
        retryInvalidation(invalidation.groupId(), invalidation.invalidationKind());
        return true;
      });
      if (!Boolean.TRUE.equals(applied)) {
        log.debug(
            "Helm group {} invalidation marker for group {} was already claimed or superseded",
            invalidation.invalidationKind(),
            invalidation.groupId());
      }
    } catch (RuntimeException e) {
      log.warn(
          "Failed applying Helm group {} invalidation token for group {}; durable retry retained",
          invalidation.invalidationKind(),
          invalidation.groupId(),
          e);
    }
  }

  private void deferAfterCommit(PendingInvalidation invalidation) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      attemptQueuedInvalidation(invalidation);
      return;
    }
    @SuppressWarnings("unchecked")
    Map<PendingInvalidationKey, PendingInvalidation> pending =
        (Map<PendingInvalidationKey, PendingInvalidation>)
            TransactionSynchronizationManager.getResource(PENDING_GROUPS_KEY);
    if (pending == null) {
      pending = new LinkedHashMap<>();
      TransactionSynchronizationManager.bindResource(PENDING_GROUPS_KEY, pending);
      Map<PendingInvalidationKey, PendingInvalidation> snapshot = pending;
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          for (PendingInvalidation pendingItem : snapshot.values()) {
            attemptQueuedInvalidation(pendingItem);
          }
        }

        @Override
        public void afterCompletion(int status) {
          TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_GROUPS_KEY);
        }
      });
    }
    // Multiple writes for the same group and invalidation kind inside one transaction leave only
    // the newest marker token. A successful fast path can then acknowledge exactly that generation
    // without deleting a concurrent invalidation committed by another transaction.
    pending.put(
        new PendingInvalidationKey(invalidation.groupId(), invalidation.invalidationKind()),
        invalidation);
  }

  private record PendingInvalidationKey(long groupId, String invalidationKind) {}

  private record PendingInvalidation(
      long groupId, String invalidationKind, String requestToken) {}
}
