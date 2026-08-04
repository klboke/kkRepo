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

  Optional<CleanupPolicy> findPolicy(long policyId);

  long createPolicy(CleanupPolicy policy);

  boolean updatePolicy(CleanupPolicy policy, long expectedRevision);

  boolean markPolicyDeleted(long policyId, long expectedRevision, Instant updatedAt);

  List<TargetRepository> listTargets(long policyId);

  /** Prevents repository removal from invalidating a policy aggregate or stranding active work. */
  boolean hasRepositoryReferences(long repositoryId);

  void replaceTargets(long policyId, List<Long> repositoryIds);

  Optional<CleanupSchedule> findSchedule(long policyId);

  List<CleanupSchedule> listSchedules();

  void upsertSchedule(CleanupSchedule schedule);

  void deleteSchedule(long policyId);

  long createRun(CleanupRun run);

  /** Duplicate-safe insert used by clustered Quartz fires. */
  OptionalLong tryCreateRun(CleanupRun run);

  Optional<CleanupRun> findRun(long runId);

  Optional<CleanupRun> findScheduledRun(long policyId, Instant scheduledFor);

  List<CleanupRun> listRuns(Long policyId, long afterId, int maxItems);

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

  List<CleanupRunRepository> listRunRepositories(long runId);

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

  /** Deletes a small locked batch of old terminal runs while retaining recent policy history. */
  int deleteTerminalRunsBefore(
      Instant completedBefore, int maxItems, int minimumRunsPerPolicy);

  CleanupOperationalSummary operationalSummary();

  void synchronizeUsageTracking(Map<Long, Instant> repositoryTrackingStartedAt, Instant now);

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
