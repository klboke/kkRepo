package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import java.time.Instant;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Durable policy aggregate and run history for kkRepo-native cleanup. */
public interface CleanupPolicyDao {
  /** Database-authoritative wall clock used by cross-replica leases and deletion cutoffs. */
  Instant currentTime();

  List<CleanupPolicy> listPolicies();

  /** Stable keyset page used by the administration API. */
  default List<CleanupPolicy> listPolicies(long afterId, int maxItems) {
    return listPolicies().stream()
        .filter(policy -> policy.id() != null && policy.id() > Math.max(0, afterId))
        .sorted(java.util.Comparator.comparingLong(CleanupPolicy::id))
        .limit(Math.max(1, maxItems))
        .toList();
  }

  /** Batch lookup used by cluster reconciliation paths. */
  default Map<Long, CleanupPolicy> findPolicies(Collection<Long> policyIds) {
    if (policyIds == null || policyIds.isEmpty()) return Map.of();
    Map<Long, CleanupPolicy> result = new java.util.LinkedHashMap<>();
    policyIds.stream().filter(java.util.Objects::nonNull).distinct().forEach(policyId ->
        findPolicy(policyId).ifPresent(policy -> result.put(policyId, policy)));
    return Map.copyOf(result);
  }

  Optional<CleanupPolicy> findPolicy(long policyId);

  long createPolicy(CleanupPolicy policy);

  boolean updatePolicy(CleanupPolicy policy, long expectedRevision);

  boolean markPolicyDeleted(long policyId, long expectedRevision, Instant updatedAt);

  List<TargetRepository> listTargets(long policyId);

  /** Batch target lookup that avoids one query per policy. */
  default Map<Long, List<TargetRepository>> listTargets(Collection<Long> policyIds) {
    if (policyIds == null || policyIds.isEmpty()) return Map.of();
    Map<Long, List<TargetRepository>> result = new java.util.LinkedHashMap<>();
    policyIds.stream().filter(java.util.Objects::nonNull).distinct()
        .forEach(policyId -> result.put(policyId, listTargets(policyId)));
    return Map.copyOf(result);
  }

  default boolean isPolicyTarget(long policyId, long repositoryId) {
    return listTargets(policyId).stream().anyMatch(target -> target.id() == repositoryId);
  }

  /** Prevents repository removal from invalidating a policy aggregate or stranding active work. */
  boolean hasRepositoryReferences(long repositoryId);

  void replaceTargets(long policyId, List<Long> repositoryIds);

  Optional<CleanupSchedule> findSchedule(long policyId);

  List<CleanupSchedule> listSchedules();

  /** Batch schedule lookup that avoids one query per policy view. */
  default Map<Long, CleanupSchedule> findSchedules(Collection<Long> policyIds) {
    if (policyIds == null || policyIds.isEmpty()) return Map.of();
    Map<Long, CleanupSchedule> result = new java.util.LinkedHashMap<>();
    policyIds.stream().filter(java.util.Objects::nonNull).distinct().forEach(policyId ->
        findSchedule(policyId).ifPresent(schedule -> result.put(policyId, schedule)));
    return Map.copyOf(result);
  }

  void upsertSchedule(CleanupSchedule schedule);

  void deleteSchedule(long policyId);

  long createRun(CleanupRun run);

  /** Duplicate-safe insert used by clustered Quartz fires. */
  OptionalLong tryCreateRun(CleanupRun run);

  Optional<CleanupRun> findRun(long runId);

  Optional<CleanupRun> findScheduledRun(long policyId, Instant scheduledFor);

  List<CleanupRun> listRuns(Long policyId, long afterId, int maxItems);

  /**
   * Lists runs newest first, optionally continuing before an exclusive run id.
   *
   * <p>A non-positive {@code beforeId} starts at the newest run. Implementations must use keyset
   * pagination rather than an offset so the cost remains bounded on deep pages.
   */
  List<CleanupRun> listRunsBefore(Long policyId, long beforeId, int maxItems);

  /** Completes a parent run once; returns false when another replica already won aggregation. */
  boolean completeRun(
      long runId,
      String state,
      long scannedSubjects,
      long matchedSubjects,
      long wouldDeleteSubjects,
      long deletedSubjects,
      long failedSubjects,
      int truncatedRepositories,
      String errorSummary,
      Instant completedAt);

  long createRunRepository(CleanupRunRepository runRepository);

  /** Creates all repository shards for one run using bounded backend batches. */
  default void createRunRepositories(List<CleanupRunRepository> runRepositories) {
    if (runRepositories == null) return;
    runRepositories.forEach(this::createRunRepository);
  }

  List<CleanupRunRepository> listRunRepositories(long runId);

  default Optional<CleanupRunRepository> findRunRepository(
      long runId, long runRepositoryId) {
    return listRunRepositories(runId).stream()
        .filter(repository -> repository.id() != null && repository.id() == runRepositoryId)
        .findFirst();
  }

  void ensureRepositoryLease(long repositoryId);

  /**
   * Claims runnable repository shards while also acquiring their repository-wide execution lease.
   * Implementations must use row locks and increment the durable fencing token on every claim or
   * takeover.
   */
  List<ClaimedRunRepository> claimRunRepositories(
      String workerId, Instant now, Instant leaseUntil, int maxItems);

  boolean heartbeatRunRepository(
      long runRepositoryId,
      String leaseToken,
      long fencingToken,
      Instant leaseUntil,
      Instant heartbeatAt);

  /** Locks and verifies both the shard lease and repository fence in the caller transaction. */
  boolean lockCurrentRunRepositoryLease(
      long runRepositoryId, String leaseToken, long fencingToken, Instant now);

  boolean completeClaimedRunRepository(
      long runRepositoryId,
      String leaseToken,
      long fencingToken,
      String state,
      long scannedSubjects,
      long matchedSubjects,
      long wouldDeleteSubjects,
      long deletedSubjects,
      long failedSubjects,
      boolean truncated,
      String errorSummary,
      Instant completedAt);

  /**
   * Snapshots the policy/repository scan cursor into an owned shard.
   *
   * <p>Retries keep the same snapshot. A later run sees cursor advancement only after the shard
   * terminal transition commits.
   */
  CleanupScanCursor acquireRunRepositoryScanCursor(
      long runRepositoryId,
      String leaseToken,
      long fencingToken,
      String initialPhase,
      Instant now);

  /** Atomically completes an owned shard and advances its durable cursor with revision CAS. */
  CleanupCursorCompletion completeClaimedRunRepositoryAndAdvanceCursor(
      long runRepositoryId,
      String leaseToken,
      long fencingToken,
      String state,
      long scannedSubjects,
      long matchedSubjects,
      long wouldDeleteSubjects,
      long deletedSubjects,
      long failedSubjects,
      boolean truncated,
      String errorSummary,
      Instant completedAt,
      CleanupScanCursor expectedCursor,
      CleanupScanCursor nextCursor);

  boolean retryClaimedRunRepository(
      long runRepositoryId,
      String leaseToken,
      long fencingToken,
      Instant nextAttemptAt,
      String errorCode,
      String errorSummary,
      Instant updatedAt);

  boolean requestRunCancellation(long runId, Instant cancelledAt);

  boolean isRunCancellationRequested(long runId);

  /**
   * Durably reserves this shard's share of the server-wide Try Run scan budget. A retry receives
   * the same reservation; concurrent shards are serialized by the parent run row.
   */
  int reserveTryRunScanBudget(
      long runId, long runRepositoryId, int requestedSubjects, int totalSubjectLimit);

  void completeRunRepository(
      long runRepositoryId,
      String state,
      long scannedSubjects,
      long matchedSubjects,
      long wouldDeleteSubjects,
      long deletedSubjects,
      long failedSubjects,
      boolean truncated,
      String errorSummary,
      Instant completedAt);

  void insertRunItems(List<CleanupRunItem> items);

  void upsertRunItem(CleanupRunItem item);

  /** Idempotently persists one bounded decision batch. */
  default void upsertRunItems(List<CleanupRunItem> items) {
    if (items == null) return;
    items.forEach(this::upsertRunItem);
  }

  List<CleanupRunItem> listRunItems(long runRepositoryId, long afterId, int maxItems);

  /** Returns a bounded first page for each requested shard without scanning unrelated decisions. */
  default Map<Long, List<CleanupRunItem>> listRunItems(
      Collection<Long> runRepositoryIds, int maxItemsPerRepository) {
    Map<Long, List<CleanupRunItem>> result = new java.util.LinkedHashMap<>();
    if (runRepositoryIds == null) return Map.of();
    for (Long runRepositoryId : runRepositoryIds) {
      if (runRepositoryId == null) continue;
      result.put(
          runRepositoryId,
          listRunItems(runRepositoryId, 0, Math.max(1, maxItemsPerRepository)));
    }
    return Map.copyOf(result);
  }

  /** Returns at most {@code maxItemsPerRepository} decisions for every shard in one run. */
  default Map<Long, List<CleanupRunItem>> listRunItemsByRun(
      long runId, int maxItemsPerRepository) {
    return listRunItems(
        listRunRepositories(runId).stream().map(CleanupRunRepository::id).toList(),
        maxItemsPerRepository);
  }

  /** Deletes a small locked batch of old terminal runs while retaining recent policy history. */
  int deleteTerminalRunsBefore(
      Instant completedBefore, int maxItems, int minimumRunsPerPolicy);

  /**
   * Prunes history with independent run and item bounds so foreign-key cascades stay small.
   */
  default CleanupHistoryPruneResult pruneTerminalRunHistory(
      Instant completedBefore,
      int maxRuns,
      int minimumRunsPerPolicy,
      int maxRunItems) {
    int deletedRuns = deleteTerminalRunsBefore(
        completedBefore, maxRuns, minimumRunsPerPolicy);
    return new CleanupHistoryPruneResult(deletedRuns, 0);
  }

  CleanupOperationalSummary operationalSummary();

  /** Reconciles the projection and returns whether its durable membership changed. */
  boolean synchronizeUsageTracking(Map<Long, Instant> repositoryTrackingStartedAt, Instant now);

  /** Cheap scalar checked by every replica before refreshing the complete local snapshot. */
  long usageTrackingRevision();

  /**
   * Serializes a complete usage-policy projection calculation across replicas.
   *
   * <p>The caller must hold a transaction and acquire this lock before reading policies or the
   * current projection. Otherwise an older policy snapshot could be committed after a newer one.
   */
  void lockUsageTrackingProjection();

  List<UsageTrackingRepository> listUsageTrackingRepositories();

  Optional<CleanupUsage> upsertAssetUsage(long assetId, Instant observedAt);

  /**
   * Records a download for a known source repository with cluster-wide write coalescing.
   *
   * <p>The implementation must lock the asset before reading or advancing its watermark so
   * concurrent replicas serialize on the same durable row.
   */
  CleanupUsageWriteOutcome recordAssetUsage(
      long assetId, long sourceRepositoryId, Duration coalescingTtl);

  Map<Long, CleanupUsage> findAssetUsage(Collection<Long> assetIds);

  long createProtection(CleanupProtection protection);

  Optional<CleanupProtection> findProtection(long protectionId);

  List<CleanupProtection> listProtections(long afterId, int maxItems, Instant activeAt);

  boolean updateProtection(CleanupProtection protection, Instant expectedUpdatedAt);

  boolean deleteProtection(long protectionId, Instant expectedUpdatedAt);

  Optional<CleanupProtection> findActiveProtection(
      long repositoryId,
      String subjectKind,
      String subjectKey,
      byte[] subjectKeyHash,
      Instant activeAt);

  /**
   * Resolves active protections for a bounded subject page without one query per subject.
   *
   * <p>The result is keyed by the caller-provided lookup id. The default preserves lightweight
   * adapters while production JDBC implementations perform batched indexed lookups.
   */
  default Map<String, CleanupProtection> findActiveProtections(
      long repositoryId,
      Collection<CleanupProtectionLookup> lookups,
      Instant activeAt) {
    if (lookups == null || lookups.isEmpty()) return Map.of();
    Map<String, CleanupProtection> result = new java.util.LinkedHashMap<>();
    for (CleanupProtectionLookup lookup : lookups) {
      findActiveProtection(
              repositoryId,
              lookup.subjectKind(),
              lookup.subjectKey(),
              lookup.subjectKeyHash(),
              activeAt)
          .ifPresent(protection -> result.put(lookup.lookupId(), protection));
    }
    return Map.copyOf(result);
  }

  record CleanupPolicy(
      Long id,
      String name,
      RepositoryFormat format,
      String notes,
      Map<String, Object> criteria,
      long revision,
      String state,
      int scanLimitPerRepository,
      int deleteLimitPerRepository,
      Instant createdAt,
      Instant updatedAt) {
  }

  record TargetRepository(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      boolean online) {
  }

  record CleanupSchedule(
      long policyId,
      String cronExpression,
      String timeZone,
      boolean enabled,
      Instant nextRunAt,
      Instant createdAt,
      Instant updatedAt) {
  }

  record CleanupRun(
      Long id,
      long policyId,
      long policyRevision,
      String mode,
      String triggerKind,
      String state,
      boolean cancelRequested,
      String requestedBy,
      Instant scheduledFor,
      int scanLimitPerRepository,
      int deleteLimitPerRepository,
      Map<String, Object> criteriaSnapshot,
      List<Map<String, Object>> repositorySnapshot,
      long scannedSubjects,
      long matchedSubjects,
      long wouldDeleteSubjects,
      long deletedSubjects,
      long failedSubjects,
      int truncatedRepositories,
      String errorSummary,
      Instant startedAt,
      Instant completedAt,
      Instant cancelledAt,
      Instant createdAt,
      Instant updatedAt) {
    public CleanupRun(
        Long id,
        long policyId,
        long policyRevision,
        String mode,
        String triggerKind,
        String state,
        String requestedBy,
        Instant scheduledFor,
        int scanLimitPerRepository,
        int deleteLimitPerRepository,
        Map<String, Object> criteriaSnapshot,
        List<Map<String, Object>> repositorySnapshot,
        long scannedSubjects,
        long matchedSubjects,
        long deletedSubjects,
        long failedSubjects,
        int truncatedRepositories,
        String errorSummary,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {
      this(
          id,
          policyId,
          policyRevision,
          mode,
          triggerKind,
          state,
          false,
          requestedBy,
          scheduledFor,
          scanLimitPerRepository,
          deleteLimitPerRepository,
          criteriaSnapshot,
          repositorySnapshot,
          scannedSubjects,
          matchedSubjects,
          0,
          deletedSubjects,
          failedSubjects,
          truncatedRepositories,
          errorSummary,
          startedAt,
          completedAt,
          null,
          createdAt,
          updatedAt);
    }
  }

  record CleanupRunRepository(
      Long id,
      long runId,
      long repositoryId,
      String repositoryName,
      RepositoryFormat format,
      RepositoryType repositoryType,
      String state,
      Integer scanBudget,
      int attemptCount,
      int maxAttempts,
      Instant nextAttemptAt,
      String leaseOwner,
      Instant leaseUntil,
      Instant lastHeartbeatAt,
      long fencingToken,
      String lastErrorCode,
      long scannedSubjects,
      long matchedSubjects,
      long wouldDeleteSubjects,
      long deletedSubjects,
      long failedSubjects,
      boolean truncated,
      String errorSummary,
      Instant startedAt,
      Instant completedAt,
      Instant createdAt,
      Instant updatedAt) {
    public CleanupRunRepository(
        Long id,
        long runId,
        long repositoryId,
        String repositoryName,
        RepositoryFormat format,
        RepositoryType repositoryType,
        String state,
        long scannedSubjects,
        long matchedSubjects,
        long deletedSubjects,
        long failedSubjects,
        boolean truncated,
        String errorSummary,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {
      this(
          id,
          runId,
          repositoryId,
          repositoryName,
          format,
          repositoryType,
          state,
          null,
          0,
          3,
          createdAt,
          null,
          null,
          null,
          0,
          null,
          scannedSubjects,
          matchedSubjects,
          0,
          deletedSubjects,
          failedSubjects,
          truncated,
          errorSummary,
          startedAt,
          completedAt,
          createdAt,
          updatedAt);
    }
  }

  record CleanupRunItem(
      Long id,
      long runRepositoryId,
      String subjectKind,
      String subjectKey,
      byte[] subjectKeyHash,
      String familyKey,
      String displayName,
      String version,
      String deletePath,
      Instant lastDownloadedAt,
      Instant publishedAt,
      int assetCount,
      long estimatedBytes,
      String expectedContentToken,
      long expectedUsageRevision,
      Long protectionId,
      Instant evaluatedAt,
      String decision,
      Map<String, Object> reason,
      String errorSummary,
      Instant createdAt,
      Instant updatedAt) {
    public CleanupRunItem(
        Long id,
        long runRepositoryId,
        String subjectKind,
        String subjectKey,
        byte[] subjectKeyHash,
        String familyKey,
        String displayName,
        String version,
        String deletePath,
        Instant lastDownloadedAt,
        Instant publishedAt,
        int assetCount,
        long estimatedBytes,
        String decision,
        Map<String, Object> reason,
        String errorSummary,
        Instant createdAt,
        Instant updatedAt) {
      this(
          id,
          runRepositoryId,
          subjectKind,
          subjectKey,
          subjectKeyHash,
          familyKey,
          displayName,
          version,
          deletePath,
          lastDownloadedAt,
          publishedAt,
          assetCount,
          estimatedBytes,
          null,
          0,
          null,
          null,
          decision,
          reason,
          errorSummary,
          createdAt,
          updatedAt);
    }
  }

  record ClaimedRunRepository(
      long id,
      long runId,
      long repositoryId,
      String repositoryName,
      RepositoryFormat format,
      RepositoryType repositoryType,
      String leaseOwner,
      String leaseToken,
      long fencingToken,
      int attemptCount,
      int maxAttempts,
      Instant leaseUntil,
      boolean takeover) {
    public ClaimedRunRepository(
        long id,
        long runId,
        long repositoryId,
        String repositoryName,
        RepositoryFormat format,
        RepositoryType repositoryType,
        String leaseOwner,
        String leaseToken,
        long fencingToken,
        int attemptCount,
        int maxAttempts,
        Instant leaseUntil) {
      this(
          id,
          runId,
          repositoryId,
          repositoryName,
          format,
          repositoryType,
          leaseOwner,
          leaseToken,
          fencingToken,
          attemptCount,
          maxAttempts,
          leaseUntil,
          false);
    }
  }

  record CleanupScanCursor(
      long policyId,
      long repositoryId,
      String phase,
      String componentNamespace,
      String componentName,
      String componentKind,
      long subjectId,
      long revision,
      long wrappedCount) {
  }

  record CleanupCursorCompletion(boolean completed, boolean cursorAdvanced) {
  }

  record CleanupOperationalSummary(
      long pendingShards,
      long retryWaitingShards,
      long runningShards,
      long expiredRunningLeases,
      Instant oldestOutstandingCreatedAt) {
  }

  record CleanupHistoryPruneResult(int deletedRuns, int deletedRunItems) {
    public boolean workPerformed() {
      return deletedRuns > 0 || deletedRunItems > 0;
    }
  }

  record UsageTrackingRepository(long repositoryId, Instant trackingStartedAt) {
  }

  record CleanupUsage(
      long assetId,
      long repositoryId,
      Instant firstDownloadedAt,
      Instant lastDownloadedAt,
      long usageRevision,
      Instant updatedAt) {
  }

  enum CleanupUsageWriteOutcome {
    WRITTEN,
    COALESCED,
    NOT_TRACKED
  }

  record CleanupProtection(
      Long id,
      String scope,
      Long repositoryId,
      String subjectKind,
      String subjectKey,
      byte[] subjectKeyHash,
      String source,
      String externalId,
      String reason,
      boolean enabled,
      Instant expiresAt,
      Instant freshnessAt,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {
  }

  record CleanupProtectionLookup(
      String lookupId,
      String subjectKind,
      String subjectKey,
      byte[] subjectKeyHash) {
  }
}
