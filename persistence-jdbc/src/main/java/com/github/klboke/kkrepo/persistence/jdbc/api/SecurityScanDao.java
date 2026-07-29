package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.security.scan.ScanEnums.BackfillStatus;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.OciPlatformPolicy;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shared relational contract for durable, multi-replica security scan coordination. */
public interface SecurityScanDao {
  int MAX_DOWNLOAD_POLICY_BATCH = 1024;
  int MAX_WAIVER_ADVISORY_SELECTOR_LENGTH = 255;
  int MAX_WAIVER_PACKAGE_SELECTOR_LENGTH = 2_048;
  int MAX_WAIVER_REASON_LENGTH = 2_048;

  Optional<ScanProfile> findProfile(long profileId);

  List<ScanProfile> listProfiles();

  ScanProfile createProfile(ScanProfile profile);

  Optional<RepositoryScanConfig> findRepositoryConfig(long repositoryId);

  /**
   * Loads configurations for an authorization-filtered repository scope with one bounded
   * parameter. Implementations must not expand the IDs into an unbounded JDBC placeholder list.
   */
  List<RepositoryScanConfig> findRepositoryConfigs(List<Long> repositoryIds);

  RepositoryScanConfig upsertRepositoryConfig(RepositoryScanConfig config);

  /**
   * Loads every authoritative input needed by the download gate in one database round trip.
   *
   * <p>The result contains the source repository plus every configured group on an actual path
   * from the entry repository to that source. Unrelated groups are excluded. Assets without a
   * blob or without an applicable configuration produce an empty result.
   */
  List<DownloadPolicySnapshot> findDownloadPolicySnapshots(
      long assetId, Long entryRepositoryId);

  /**
   * Batch form of {@link #findDownloadPolicySnapshots(long, Long)} for shared Docker blobs.
   *
   * <p>Callers must pass at most {@value #MAX_DOWNLOAD_POLICY_BATCH} distinct asset IDs. The bound
   * keeps the hot-path statement below database parameter limits while avoiding one policy query
   * per referencing manifest.
   */
  List<DownloadPolicySnapshot> findDownloadPolicySnapshots(
      List<Long> assetIds, Long entryRepositoryId);

  /**
   * Loads the source and entry-path repository configurations for content that has not been
   * materialized locally yet.
   *
   * <p>This is used to apply the same fail-closed policy to successful remote HEAD responses
   * without downloading or inventing an asset row.
   */
  List<DownloadPolicyContext> findDownloadPolicyContexts(
      long sourceRepositoryId, Long entryRepositoryId);

  /**
   * Folds a generic artifact content-change event into the scan-specific candidate projection.
   * The current asset/blob binding is re-read so a delayed event cannot restore stale content.
   */
  int recordArtifactContentChange(long assetId);

  Optional<ScanCandidate> findCandidate(long assetId);

  /**
   * Locks a bounded candidate batch. Must be called in the transaction that enqueues tasks and
   * advances {@link ScanCandidate#enqueuedGeneration()}.
   */
  List<ScanCandidate> claimCandidates(int maxItems);

  boolean markCandidateEnqueued(long assetId, long expectedGeneration);

  /**
   * Locks and projects the next contiguous asset page for a repository.
   *
   * <p>The implementation must wait for concurrent asset writers rather than skip locked rows:
   * the returned cursor is durable and advancing it past an omitted row would permanently lose
   * that asset from the backfill.
   */
  BackfillPage markRepositoryAssetsForBackfill(
      long repositoryId, long afterAssetId, int maxItems);

  long createTask(TaskDraft task);

  Optional<ScanTask> findTask(long taskId);

  /**
   * Returns the task while holding its row lock until the caller transaction completes.
   *
   * <p>Administrative transitions use the locked projection when they must act on the exact
   * currently claimed lease. Claim and takeover queries use {@code SKIP LOCKED}, so they cannot
   * replace that lease between this read and the terminal update.
   */
  Optional<ScanTask> findTaskForUpdate(long taskId);

  /**
   * Locks the task row and verifies that the supplied lease still owns a running attempt.
   *
   * <p>The caller must keep this check and any attempt-scoped publication in the same
   * transaction. Terminal transitions take the same row lock, so either publication commits
   * before the transition and is cleaned by it, or it observes the lost lease and is rejected.
   */
  boolean lockCurrentTaskLease(long taskId, String leaseToken);

  List<ScanTask> listTasks(Long repositoryId, TaskStatus status, long afterId, int maxItems);

  List<ScanTask> listTasks(
      Long repositoryId,
      TaskStatus status,
      String query,
      long afterId,
      int maxItems);

  List<ScanTask> listTasksByRepositories(
      List<Long> repositoryIds,
      TaskStatus status,
      String query,
      long afterId,
      int maxItems);

  /** Claims tasks with row locks and assigns a distinct lease token to every task. */
  List<ScanTask> claimTasks(
      String workerId, Instant now, Instant leaseUntil, int maxItems);

  /**
   * Fences expired tasks whose final permitted attempt was lost with the worker process.
   *
   * <p>The returned tasks receive a fresh lease token without incrementing {@code attempts}. The
   * caller must materialize terminal failure state and finish the task with that token.
   */
  List<ScanTask> claimExpiredExhaustedTasks(
      String workerId, Instant now, Instant leaseUntil, int maxItems);

  boolean heartbeatTask(long taskId, String leaseToken, Instant leaseUntil, Instant heartbeatAt);

  boolean completeTask(long taskId, String leaseToken, Instant completedAt);

  boolean retryTask(
      long taskId,
      String leaseToken,
      Instant nextAttemptAt,
      String errorCode,
      String errorSummary,
      Instant updatedAt);

  boolean failTask(
      long taskId,
      String leaseToken,
      String errorCode,
      String errorSummary,
      Instant completedAt);

  boolean cancelTask(long taskId, Instant cancelledAt);

  boolean cancelClaimedTask(long taskId, String leaseToken, Instant cancelledAt);

  boolean requeueTask(long taskId, Instant requestedAt, String requestedBy);

  boolean reactivateSnapshotTask(
      long taskId, long requestedScannerSnapshotId, Instant requestedAt, String requestedBy);

  ScannerSnapshot insertSnapshotOrFindExisting(ScannerSnapshot snapshot);

  Optional<ScannerSnapshot> findScannerSnapshot(long snapshotId);

  Optional<ScannerSnapshot> latestScannerSnapshot();

  /**
   * Returns the newest ready vulnerability-database build that is not beyond the supplied clock
   * bound.
   *
   * <p>Database update time, rather than the auto-increment snapshot ID or observation time, is
   * the durable monotonic epoch. This prevents a later observation from a lagging scanner replica
   * from rolling shared scan state back to an older database.
   */
  Optional<ScannerSnapshot> latestReadyScannerSnapshot(Instant maximumDatabaseUpdatedAt);

  Sbom insertSbomOrFindExisting(Sbom sbom);

  Sbom publishSbom(Sbom sbom, List<SbomComponent> components);

  Optional<Sbom> findSbom(long sbomId);

  Optional<Sbom> findSbomByCatalogFingerprint(String catalogFingerprint);

  Optional<Sbom> findReusableSbom(
      SubjectKind subjectKind,
      byte[] subjectIdentityHash,
      String catalogEngine,
      String catalogEngineVersion,
      String catalogConfigurationDigest);

  int insertSbomComponents(long sbomId, List<SbomComponent> components);

  List<SbomComponent> listSbomComponents(long sbomId, long afterId, int maxItems);

  ScanRun insertRunOrFindExisting(ScanRun run);

  Optional<ScanRun> findRun(long runId);

  Optional<ScanRun> findRunByMatchFingerprint(String matchFingerprint);

  List<ScanRun> listRuns(Long repositoryId, long afterId, int maxItems);

  List<ScanRun> listRuns(
      Long repositoryId, String query, long afterId, int maxItems);

  List<ScanRun> listRunsByRepositories(
      List<Long> repositoryIds, String query, long afterId, int maxItems);

  void associateRun(
      long scanRunId,
      long repositoryId,
      long assetId,
      long profileId,
      long contentGeneration,
      Instant associatedAt);

  List<Long> listRepositoryIdsForRun(long scanRunId);

  /**
   * Lists distinct repository/asset associations with a bounded keyset cursor.
   *
   * <p>The same reusable run can be associated with many profiles or generations, while waiver
   * scope only depends on repository and asset identity.
   */
  List<ScanRunSubject> listRunSubjects(
      long scanRunId, long afterRepositoryId, long afterAssetId, int maxItems);

  boolean runSubjectExists(long scanRunId, long repositoryId, long assetId);

  List<Long> listRepositoryIdsForSbom(long sbomId);

  int insertFindings(long scanRunId, List<ScanFinding> findings);

  Optional<ScanFinding> findFinding(long findingId);

  /**
   * Loads and locks one finding until the surrounding transaction completes.
   *
   * <p>Finding-scoped waiver creation uses this lock to serialize duplicate checks across
   * application replicas.
   */
  Optional<ScanFinding> findFindingForUpdate(long findingId);

  List<ScanFinding> listFindings(
      Long repositoryId, Long scanRunId, Severity severity, long afterId, int maxItems);

  List<ScanFinding> listFindings(
      Long repositoryId,
      Long scanRunId,
      Severity severity,
      String query,
      long afterId,
      int maxItems);

  List<ScanFinding> listFindingsByRepositories(
      List<Long> repositoryIds,
      Long scanRunId,
      Severity severity,
      String query,
      long afterId,
      int maxItems);

  Optional<AssetSecurityState> findAssetState(long assetId, long profileId);

  /**
   * Locks the durable publication authority for one asset and returns its current profile state.
   *
   * <p>Callers must already be in a transaction. The lock is shared with successful, failed,
   * cancelled, and retried scan-state publication so a task can decide whether it has been
   * superseded without racing another replica.
   */
  Optional<AssetSecurityState> findAssetStateForUpdate(long assetId, long profileId);

  /**
   * Returns whether durable work newer than {@code taskId} already owns this task's projection.
   *
   * <p>Callers must already be in a transaction. The implementation locks the same per-asset
   * publication authority used by finalization, cancellation, and retry.
   */
  boolean taskProjectionIsSuperseded(long taskId);

  List<AssetSecurityState> listAssetStates(long assetId);

  /**
   * Lists current assets that need a database rematch or a first-scan observation recovery.
   */
  List<AssetSecurityState> listAssetStatesNeedingSnapshot(
      long profileId, long scannerSnapshotId, long afterAssetId, int maxItems);

  /**
   * Makes a terminal first-time scan pending again after scanner observation has recovered.
   *
   * <p>The state and candidate marker are fenced in one statement so concurrent completion,
   * content replacement, or another replica's recovery pass cannot enqueue duplicate work.
   */
  boolean requeueCandidateAfterObservationFailure(
      long assetId, long profileId, long expectedContentGeneration, Instant changedAt);

  AssetSecurityState upsertAssetStateIfCurrent(AssetSecurityState state);

  Optional<AssetPolicyState> findAssetPolicyState(
      long assetId, long profileId, long repositoryId);

  AssetPolicyState upsertAssetPolicyStateIfCurrent(AssetPolicyState state);

  List<PolicyEvaluationTarget> listPolicyEvaluationTargets(
      long sourceRepositoryId,
      long contextRepositoryId,
      long profileId,
      long configRevision,
      Long policyId,
      Long policyRevision,
      long afterAssetId,
      Instant evaluatedAt,
      int maxItems);

  boolean markAssetStateStale(
      long assetId,
      long profileId,
      long expectedScanRunId,
      Instant staleAt);

  int markStatesStaleForSnapshot(
      long profileId, Instant staleAt, int maxItems);

  List<ScanPolicy> listPolicies();

  List<ScanPolicy> listPolicies(String query, long afterId, int maxItems);

  Optional<ScanPolicy> findPolicy(long policyId);

  ScanPolicy createPolicy(ScanPolicy policy);

  /**
   * Atomically creates the first revision for a normalized policy name. Returns empty when another
   * replica already created the same logical policy name.
   */
  Optional<ScanPolicy> createPolicyIfAbsent(ScanPolicy policy);

  /**
   * Locks the durable head for {@link ScanPolicy#name()} and creates its next immutable revision
   * only when {@code expectedHeadPolicyId} is still current. The caller-supplied revision is
   * ignored; empty means the client attempted to revise a stale head.
   */
  Optional<ScanPolicy> createNextPolicyRevision(
      long expectedHeadPolicyId, ScanPolicy policy);

  /**
   * Moves repository configurations pinned to one immutable policy revision to its replacement.
   * Historical scan results and waivers keep their original policy identity.
   */
  int replaceRepositoryPolicy(
      long currentPolicyId, long replacementPolicyId, Instant updatedAt);

  ScanWaiver createWaiver(ScanWaiver waiver);

  Optional<ScanWaiver> findWaiver(long waiverId);

  /**
   * Advances the durable waiver revision and invalidates materialized policy contexts within the
   * waiver's repository, asset, and policy scope. Global waiver changes advance an O(1) watermark
   * instead of deleting the complete policy-state table. Missing or stale rows are rebuilt by the
   * bounded policy reconciler.
   */
  int invalidatePolicyStatesForWaiver(ScanWaiver waiver);

  WaiverRevision waiverRevision();

  List<ScanWaiver> listWaivers(Long repositoryId, long afterId, int maxItems);

  List<ScanWaiver> listWaivers(
      Long repositoryId, String query, long afterId, int maxItems);

  List<ScanWaiver> listActiveWaivers(
      long repositoryId, Long assetId, Instant evaluatedAt, long afterId, int maxItems);

  /**
   * Loads only approved waiver candidates that can match the supplied findings and run subjects.
   * Exact subject/finding matching remains in the service so this query can stay portable across
   * MySQL and PostgreSQL.
   */
  List<ScanWaiver> listWaiversForFindings(
      List<Long> findingIds,
      List<Long> scanRunIds,
      List<String> advisorySelectors,
      List<String> packageSelectors,
      long afterId,
      int maxItems);

  boolean deleteWaiver(long waiverId);

  BackfillJob createBackfillJob(long repositoryId, String createdBy, Instant now);

  List<BackfillJob> claimBackfillJobs(
      String workerId, Instant now, Instant leaseUntil, int maxItems);

  boolean updateBackfillProgress(
      long jobId,
      String leaseToken,
      long cursorAssetId,
      long scannedAssets,
      long markedAssets,
      BackfillStatus status,
      String errorSummary,
      Instant leaseUntil,
      Instant updatedAt);

  boolean requeueBackfill(
      long jobId,
      String leaseToken,
      long cursorAssetId,
      long scannedAssets,
      long markedAssets,
      String errorSummary,
      Instant nextAttemptAt,
      Instant updatedAt);

  ScanSummary summary();

  ScanSummary summary(long repositoryId);

  ScanSummary summary(List<Long> repositoryIds);

  /**
   * Returns bounded operational gauges. Counts saturate at {@code maxCount} so periodic metrics
   * collection never turns into an unbounded table scan.
   */
  ScanMetricSummary metricSummary(int maxCount);

  Optional<Instant> oldestPendingTaskCreatedAt();

  RetentionResult cleanupRetainedData(
      Instant terminalTaskCutoff, Instant resultCutoff, int maxItems);

  record ScanProfile(
      Long id,
      String name,
      boolean enabled,
      String catalogEngine,
      String matcherEngine,
      List<String> scannerTypes,
      Map<String, Object> targetRules,
      long maxInputBytes,
      int maxArchiveEntries,
      long maxUncompressedBytes,
      long maxSingleFileBytes,
      int maxNestedDepth,
      int timeoutSeconds,
      OciPlatformPolicy ociPlatformPolicy,
      List<String> requiredPlatforms,
      String configurationDigest,
      long revision,
      Instant createdAt,
      Instant updatedAt) {
    public ScanProfile {
      scannerTypes = scannerTypes == null ? List.of() : List.copyOf(scannerTypes);
      targetRules = targetRules == null ? Map.of() : Map.copyOf(targetRules);
      requiredPlatforms =
          requiredPlatforms == null ? List.of() : List.copyOf(requiredPlatforms);
    }
  }

  record RepositoryScanConfig(
      long repositoryId,
      boolean enabled,
      long profileId,
      boolean scanHostedContent,
      boolean scanProxyContent,
      EnforcementMode enforcementMode,
      PolicyAction pendingAction,
      PolicyAction failureAction,
      PolicyAction partialAction,
      Long maxResultAgeSeconds,
      Long policyId,
      long configRevision,
      Instant createdAt,
      Instant updatedAt) {}

  record DownloadPolicyContext(
      RepositoryScanConfig config,
      ScanProfile profile) {}

  record DownloadPolicySnapshot(
      long assetId,
      long sourceRepositoryId,
      RepositoryFormat format,
      String path,
      String kind,
      String contentType,
      long blobSize,
      RepositoryScanConfig config,
      ScanProfile profile,
      ScanCandidate candidate,
      AssetSecurityState assetState,
      ScanPolicy policy,
      AssetPolicyState policyState,
      long requiredWaiverRevision) {
    public DownloadPolicySnapshot(
        long assetId,
        long sourceRepositoryId,
        RepositoryFormat format,
        String path,
        String kind,
        String contentType,
        long blobSize,
        RepositoryScanConfig config,
        ScanProfile profile,
        ScanCandidate candidate,
        AssetSecurityState assetState,
        ScanPolicy policy,
        AssetPolicyState policyState) {
      this(
          assetId,
          sourceRepositoryId,
          format,
          path,
          kind,
          contentType,
          blobSize,
          config,
          profile,
          candidate,
          assetState,
          policy,
          policyState,
          0);
    }
  }

  record ScanCandidate(
      long assetId,
      Long assetBlobId,
      long contentGeneration,
      long enqueuedGeneration,
      Instant changedAt,
      Instant updatedAt) {}

  record TaskDraft(
      long repositoryId,
      Long assetId,
      SubjectKind subjectKind,
      String subjectKey,
      long contentGeneration,
      long profileId,
      long profileRevision,
      Long requestedScannerSnapshotId,
      ScanStage stage,
      RequestReason requestReason,
      int priority,
      int maxAttempts,
      String requestedBy,
      String requestUuid,
      String idempotencyKey,
      Instant requestedAt) {}

  record ScanTask(
      long id,
      long repositoryId,
      Long assetId,
      SubjectKind subjectKind,
      String subjectKey,
      byte[] subjectKeyHash,
      long contentGeneration,
      long profileId,
      long profileRevision,
      Long requestedScannerSnapshotId,
      ScanStage stage,
      RequestReason requestReason,
      int priority,
      TaskStatus status,
      int attempts,
      int maxAttempts,
      Instant nextAttemptAt,
      String claimedBy,
      String leaseToken,
      Instant leaseUntil,
      Instant lastHeartbeatAt,
      String lastErrorCode,
      String lastErrorSummary,
      String requestedBy,
      String requestUuid,
      Instant requestedAt,
      Instant startedAt,
      Instant finishedAt,
      Instant createdAt,
      Instant updatedAt) {
    public ScanTask {
      subjectKeyHash = subjectKeyHash == null ? null : subjectKeyHash.clone();
    }
  }

  record ScannerSnapshot(
      Long id,
      String adapterName,
      String adapterApiVersion,
      String engineName,
      String engineVersion,
      String vulnerabilityDatabaseRevision,
      Instant vulnerabilityDatabaseUpdatedAt,
      String capabilityDigest,
      String snapshotFingerprint,
      Instant observedAt,
      boolean ready,
      Map<String, Object> details) {
    public ScannerSnapshot {
      details = details == null ? Map.of() : Map.copyOf(details);
    }
  }

  record Sbom(
      Long id,
      SubjectKind subjectKind,
      String subjectIdentity,
      byte[] subjectIdentityHash,
      String catalogEngine,
      String catalogEngineVersion,
      String catalogConfigurationDigest,
      String catalogFingerprint,
      long documentBlobId,
      String documentSha256,
      String specName,
      String specVersion,
      int componentCount,
      int dependencyCount,
      boolean inventoryComplete,
      Instant createdAt) {
    public Sbom {
      subjectIdentityHash = subjectIdentityHash == null ? null : subjectIdentityHash.clone();
    }
  }

  record SbomComponent(
      Long id,
      long sbomId,
      String componentRef,
      byte[] componentRefHash,
      String packageUrl,
      byte[] packageUrlHash,
      String type,
      String namespace,
      String name,
      String version,
      String directness,
      List<String> locations,
      List<String> licenses,
      Map<String, Object> properties) {
    public SbomComponent {
      componentRefHash = componentRefHash == null ? null : componentRefHash.clone();
      packageUrlHash = packageUrlHash == null ? null : packageUrlHash.clone();
      locations = locations == null ? List.of() : List.copyOf(locations);
      licenses = licenses == null ? List.of() : List.copyOf(licenses);
      properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
  }

  record ScanRun(
      Long id,
      Long taskId,
      long sbomId,
      long scannerSnapshotId,
      String matchConfigurationDigest,
      String matchFingerprint,
      ScanState status,
      ScanCompleteness scanCompleteness,
      long rawReportBlobId,
      String rawReportSha256,
      int findingCount,
      int fixableFindingCount,
      int criticalCount,
      int highCount,
      int mediumCount,
      int lowCount,
      int unknownCount,
      Severity maxSeverity,
      List<String> scannedPlatforms,
      List<String> missingPlatforms,
      Instant startedAt,
      Instant completedAt,
      Instant createdAt) {
    public ScanRun {
      scannedPlatforms =
          scannedPlatforms == null ? List.of() : List.copyOf(scannedPlatforms);
      missingPlatforms =
          missingPlatforms == null ? List.of() : List.copyOf(missingPlatforms);
    }

    public ScanRun(
        Long id,
        Long taskId,
        long sbomId,
        long scannerSnapshotId,
        String matchConfigurationDigest,
        String matchFingerprint,
        ScanState status,
        ScanCompleteness scanCompleteness,
        long rawReportBlobId,
        String rawReportSha256,
        int findingCount,
        int fixableFindingCount,
        int criticalCount,
        int highCount,
        int mediumCount,
        int lowCount,
        int unknownCount,
        Severity maxSeverity,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt) {
      this(
          id,
          taskId,
          sbomId,
          scannerSnapshotId,
          matchConfigurationDigest,
          matchFingerprint,
          status,
          scanCompleteness,
          rawReportBlobId,
          rawReportSha256,
          findingCount,
          fixableFindingCount,
          criticalCount,
          highCount,
          mediumCount,
          lowCount,
          unknownCount,
          maxSeverity,
          List.of(),
          List.of(),
          startedAt,
          completedAt,
          createdAt);
    }
  }

  record ScanRunSubject(
      long scanRunId,
      long repositoryId,
      long assetId,
      long profileId,
      long contentGeneration,
      Instant associatedAt) {}

  record ScanFinding(
      Long id,
      long scanRunId,
      String findingKey,
      byte[] findingKeyHash,
      String advisoryId,
      List<String> aliases,
      String dataSource,
      String packageUrl,
      String packageName,
      String installedVersion,
      List<String> fixedVersions,
      Severity severity,
      String severitySource,
      String cvssVector,
      Double cvssScore,
      String title,
      String description,
      String primaryUrl,
      List<String> locations,
      String sourceStatus,
      Instant createdAt) {
    public ScanFinding {
      findingKeyHash = findingKeyHash == null ? null : findingKeyHash.clone();
      aliases = aliases == null ? List.of() : List.copyOf(aliases);
      fixedVersions = fixedVersions == null ? List.of() : List.copyOf(fixedVersions);
      locations = locations == null ? List.of() : List.copyOf(locations);
    }
  }

  record AssetSecurityState(
      long assetId,
      long profileId,
      long contentGeneration,
      byte[] subjectIdentityHash,
      Long latestScanRunId,
      ScanState scanState,
      ScanCompleteness scanCompleteness,
      boolean inventoryComplete,
      Severity maxSeverity,
      Map<String, Integer> findingCounts,
      Long policyId,
      Long policyRevision,
      PolicyDecision policyDecision,
      String policyReasonCode,
      Instant staleAt,
      Instant lastEvaluatedAt,
      long version) {
    public AssetSecurityState {
      subjectIdentityHash = subjectIdentityHash == null ? null : subjectIdentityHash.clone();
      findingCounts = findingCounts == null ? Map.of() : Map.copyOf(findingCounts);
    }
  }

  record AssetPolicyState(
      long assetId,
      long profileId,
      long repositoryId,
      long contentGeneration,
      Long latestScanRunId,
      Long policyId,
      Long policyRevision,
      long configRevision,
      PolicyDecision policyDecision,
      String policyReasonCode,
      int waivedFindings,
      Instant staleAt,
      Instant nextWaiverExpiry,
      Instant lastEvaluatedAt,
      long version,
      long waiverRevision) {
    public AssetPolicyState(
        long assetId,
        long profileId,
        long repositoryId,
        long contentGeneration,
        Long latestScanRunId,
        Long policyId,
        Long policyRevision,
        long configRevision,
        PolicyDecision policyDecision,
        String policyReasonCode,
        int waivedFindings,
        Instant staleAt,
        Instant nextWaiverExpiry,
        Instant lastEvaluatedAt,
        long version) {
      this(
          assetId,
          profileId,
          repositoryId,
          contentGeneration,
          latestScanRunId,
          policyId,
          policyRevision,
          configRevision,
          policyDecision,
          policyReasonCode,
          waivedFindings,
          staleAt,
          nextWaiverExpiry,
          lastEvaluatedAt,
          version,
          0);
    }
  }

  record PolicyEvaluationTarget(
      long assetId,
      long sourceRepositoryId,
      long contentGeneration,
      Long stateContentGeneration,
      Long latestScanRunId,
      ScanState scanState,
      long policyStateVersion,
      Instant staleAt,
      Instant nextWaiverExpiry,
      long waiverRevision) {
    public PolicyEvaluationTarget(
        long assetId,
        long sourceRepositoryId,
        long contentGeneration,
        Long stateContentGeneration,
        Long latestScanRunId,
        ScanState scanState,
        long policyStateVersion,
        Instant nextWaiverExpiry) {
      this(
          assetId,
          sourceRepositoryId,
          contentGeneration,
          stateContentGeneration,
          latestScanRunId,
          scanState,
          policyStateVersion,
          null,
          nextWaiverExpiry,
          0);
    }

    public PolicyEvaluationTarget(
        long assetId,
        long sourceRepositoryId,
        long contentGeneration,
        Long stateContentGeneration,
        Long latestScanRunId,
        ScanState scanState,
        long policyStateVersion,
        Instant nextWaiverExpiry,
        long waiverRevision) {
      this(
          assetId,
          sourceRepositoryId,
          contentGeneration,
          stateContentGeneration,
          latestScanRunId,
          scanState,
          policyStateVersion,
          null,
          nextWaiverExpiry,
          waiverRevision);
    }
  }

  record ScanPolicy(
      Long id,
      String name,
      boolean enabled,
      Severity blockSeverity,
      boolean onlyFixable,
      boolean blockUnknownSeverity,
      boolean requireCompleteInventory,
      Long maxResultAgeSeconds,
      List<String> requiredPlatforms,
      long revision,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {
    public ScanPolicy {
      requiredPlatforms =
          requiredPlatforms == null ? List.of() : List.copyOf(requiredPlatforms);
    }
  }

  record ScanWaiver(
      Long id,
      String scopeType,
      Long repositoryId,
      Long assetId,
      Long findingId,
      String advisorySelector,
      String packageSelector,
      Map<String, Object> selector,
      String reason,
      Long policyId,
      Long policyRevision,
      String createdBy,
      String approvedBy,
      Instant expiresAt,
      Instant createdAt,
      Instant updatedAt) {
    public ScanWaiver {
      selector = selector == null ? Map.of() : Map.copyOf(selector);
    }
  }

  record WaiverRevision(long currentRevision, long globalInvalidationRevision) {}

  record BackfillJob(
      Long id,
      long repositoryId,
      BackfillStatus status,
      long cursorAssetId,
      long scannedAssets,
      long markedAssets,
      int attempts,
      String claimedBy,
      String leaseToken,
      Instant leaseUntil,
      Instant nextAttemptAt,
      String lastErrorSummary,
      String createdBy,
      Instant createdAt,
      Instant updatedAt,
      Instant completedAt) {}

  record BackfillPage(
      int scannedAssets,
      int markedAssets,
      long nextAssetId,
      boolean complete) {}

  record ScanSummary(
      long candidateBacklog,
      long pendingTasks,
      long runningTasks,
      long failedTasks,
      long completeAssets,
      long partialAssets,
      long staleAssets,
      long blockedAssets,
      long criticalFindings,
      long highFindings) {}

  record ScanMetricSummary(
      long pendingTasks,
      long runningTasks,
      long failedTasks,
      long partialAssets,
      long highRiskFindings) {}

  record RetentionResult(
      int taskCount,
      int backfillJobCount,
      int runSubjectCount,
      int runCount,
      int sbomCount,
      int scannerSnapshotCount) {
    public int total() {
      return taskCount
          + backfillJobCount
          + runSubjectCount
          + runCount
          + sbomCount
          + scannerSnapshotCount;
    }
  }
}
