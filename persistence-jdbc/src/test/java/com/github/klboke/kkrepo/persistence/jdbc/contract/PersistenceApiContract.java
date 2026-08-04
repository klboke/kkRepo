package com.github.klboke.kkrepo.persistence.jdbc.contract;

import static com.github.klboke.kkrepo.security.scan.ScanEnums.SCANNER_OBSERVATION_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchCriteria;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao.ScannerResourceKind;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao.ScannerTokenResource;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao.TokenKind;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.api.PubUploadSessionDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationCheckpointRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.PubUploadSessionRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadSessionRecord;
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
import com.github.klboke.kkrepo.security.scan.ScanTaskPriorities;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;

/** Reusable black-box contract that every database backend must pass through the public API. */
public abstract class PersistenceApiContract {
  protected abstract PersistenceStores stores();

  protected abstract <T> T inTransaction(Supplier<T> action);

  protected abstract Set<String> databaseTables();

  @Test
  void baselineContainsTheCompleteSharedLogicalSchema() {
    assertEquals(Set.of(
        "api_key",
        "ansible_collection_signature",
        "ansible_collection_version",
        "ansible_group_binding",
        "ansible_import_task",
        "ansible_proxy_inventory",
        "ansible_proxy_inventory_version",
        "ansible_proxy_version_state",
        "ansible_registry_lease",
        "asset",
        "asset_blob",
        "asset_public_identifier",
        "asset_security_policy_state",
        "asset_security_state",
        "artifact_change_event",
        "auth_ticket",
        "blob_store",
        "blob_reference",
        "browse_node",
        "cache_version",
        "cleanup_policy",
        "component",
        "component_search",
        "content_selector",
        "docker_auth_token",
        "docker_manifest",
        "docker_manifest_reference",
        "docker_scanner_token_resource",
        "docker_tag",
        "docker_upload_chunk",
        "docker_upload_session",
        "maintenance_cursor",
        "metadata_rebuild_marker",
        "migration_checkpoint",
        "migration_job",
        "migration_validation_result",
        "npm_release_index_entry",
        "npm_release_index_revision",
        "proxy_remote_state",
        "pub_upload_session",
        "repository",
        "repository_cleanup_policy",
        "repository_data_migration_asset",
        "repository_data_migration_repository",
        "repository_index_rebuild_marker",
        "repository_member",
        "repository_security_scan_config",
        "routing_rule",
        "security_anonymous_config",
        "security_audit_log",
        "security_privilege",
        "security_realm",
        "security_realm_config",
        "security_repository_target",
        "security_role",
        "security_role_inheritance",
        "security_role_privilege",
        "security_sbom",
        "security_sbom_component",
        "security_scan_backfill_job",
        "security_scan_candidate",
        "security_scan_finding",
        "security_scan_policy",
        "security_scan_profile",
        "security_scan_run",
        "security_scan_run_subject",
        "security_scan_task",
        "security_scan_waiver",
        "security_scan_waiver_revision",
        "security_scanner_snapshot",
        "security_user",
        "security_user_role",
        "spring_session",
        "spring_session_attributes",
        "swift_coordinate_lease",
        "swift_group_source_binding",
        "swift_manifest",
        "swift_proxy_inventory",
        "swift_proxy_negative_cache",
        "swift_proxy_source",
        "swift_release",
        "swift_release_tombstone",
        "swift_repository_url",
        "terraform_provider_platform",
        "terraform_provider_signing_state",
        "terraform_publish_lease",
        "terraform_signing_key",
        "terraform_source_binding",
        "ui_settings"), databaseTables());
  }

  @Test
  void securityScanningIsIdempotentFencedAndProtectsImmutableDocuments() throws Exception {
    SecurityScanDao scans = stores().securityScanning();
    long repositoryId = createRepository("scan-contract", RepositoryFormat.MAVEN2);
    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-24T12:00:00Z");

    long artifactBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "scan/app-1.0.jar", "scan-artifact"));
    String path = "com/acme/app/1.0/app-1.0.jar";
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, null, artifactBlobId, RepositoryFormat.MAVEN2, path,
        PersistenceHashes.pathHash(path), "app-1.0.jar", "ARTIFACT",
        "application/java-archive", 42L, null, now, Map.of()));

    assertTrue(
        scans.findCandidate(assetId).isEmpty(),
        "the core asset write path must not update scan-specific tables");
    List<ArtifactChangeDao.ArtifactChange> originalEvents = stores().artifactChanges()
        .listAfter(0, 1000).stream()
        .filter(event -> event.assetId() == assetId)
        .toList();
    assertEquals(1, originalEvents.size());
    assertEquals(ArtifactChangeDao.ChangeKind.CONTENT_CREATED, originalEvents.getFirst().changeKind());

    String artifactChangeCursor = "contract_security_scan_artifact_change";
    assertEquals(1, foldArtifactChanges(artifactChangeCursor));
    SecurityScanDao.ScanCandidate originalCandidate = scans.findCandidate(assetId).orElseThrow();
    assertEquals(1, originalCandidate.contentGeneration());
    stores().assets().touchAssetLastUpdated(assetId, now.plusSeconds(1));
    stores().assets().updateAssetAttributes(assetId, Map.of("metadataOnly", true));
    assertEquals(
        1,
        stores().artifactChanges().listAfter(0, 1000).stream()
            .filter(event -> event.assetId() == assetId)
            .count(),
        "metadata-only changes must not emit content-change events");
    assertEquals(0, foldArtifactChanges(artifactChangeCursor));
    assertEquals(
        originalCandidate.contentGeneration(),
        scans.findCandidate(assetId).orElseThrow().contentGeneration(),
        "metadata-only changes must not create a new content generation");

    SecurityScanDao.BackfillPage firstBackfill = inTransaction(
        () -> scans.markRepositoryAssetsForBackfill(repositoryId, 0, 100));
    SecurityScanDao.BackfillPage repeatedBackfill = inTransaction(
        () -> scans.markRepositoryAssetsForBackfill(repositoryId, 0, 100));
    assertEquals(0, firstBackfill.markedAssets());
    assertEquals(0, repeatedBackfill.markedAssets());
    assertEquals(
        1,
        scans.findCandidate(assetId).orElseThrow().contentGeneration(),
        "backfill must be idempotent for an unchanged blob binding");
    assertEquals(
        assetId,
        inTransaction(() -> scans.claimCandidates(10)).getFirst().assetId(),
        "the generated pending projection must expose unqueued content");
    assertTrue(scans.markCandidateEnqueued(assetId, 1));
    assertTrue(
        inTransaction(() -> scans.claimCandidates(10)).isEmpty(),
        "the generated pending projection must hide the acknowledged generation");
    SecurityScanDao.BackfillPage enabledRepositoryBackfill = inTransaction(
        () -> scans.markRepositoryAssetsForBackfill(repositoryId, 0, 100));
    SecurityScanDao.BackfillPage repeatedEnabledRepositoryBackfill = inTransaction(
        () -> scans.markRepositoryAssetsForBackfill(repositoryId, 0, 100));
    assertEquals(1, enabledRepositoryBackfill.markedAssets());
    assertEquals(0, repeatedEnabledRepositoryBackfill.markedAssets());
    assertEquals(
        1,
        scans.findCandidate(assetId).orElseThrow().contentGeneration(),
        "enabling a profile must requeue an unchanged candidate without inventing new content");
    assertEquals(
        assetId,
        inTransaction(() -> scans.claimCandidates(10)).getFirst().assetId(),
        "a repository backfill must expose an acknowledged unchanged candidate again");
    assertTrue(scans.markCandidateEnqueued(assetId, 1));

    SecurityScanDao.ScanProfile profile = scans.createProfile(new SecurityScanDao.ScanProfile(
        null, "contract-profile", true, "syft", "grype", List.of("vuln"), Map.of(),
        1024 * 1024, 1000, 10 * 1024 * 1024, 1024 * 1024, 2, 60,
        OciPlatformPolicy.REQUIRED_SET, List.of("linux/amd64"), "9".repeat(64), 1, now, now));
    SecurityScanDao.ScanPolicy policy = scans.createPolicy(new SecurityScanDao.ScanPolicy(
        null, "contract-policy", true, Severity.HIGH, false, false, true,
        3600L, List.of("linux/amd64"), 1, "contract", now, now));
    assertTrue(scans.createPolicyIfAbsent(new SecurityScanDao.ScanPolicy(
        null, "CONTRACT-POLICY", true, Severity.HIGH, false, false, true,
        3600L, List.of("linux/amd64"), 1, "contract", now, now)).isEmpty(),
        "logical policy names must be unique independent of case");
    List<Boolean> policyCreateResults = invokeConcurrently(List.of(
        () -> inTransaction(() -> scans.createPolicyIfAbsent(new SecurityScanDao.ScanPolicy(
            null, "Concurrent-Create", true, Severity.HIGH, false, false, true,
            3600L, List.of(), 1, "contract", now, now)).isPresent()),
        () -> inTransaction(() -> scans.createPolicyIfAbsent(new SecurityScanDao.ScanPolicy(
            null, "concurrent-create", true, Severity.HIGH, false, false, true,
            3600L, List.of(), 1, "contract", now, now)).isPresent())), 2);
    assertEquals(
        1,
        policyCreateResults.stream().filter(Boolean::booleanValue).count(),
        "concurrent case variants must atomically create one logical policy");
    SecurityScanDao.ScanPolicy concurrentPolicy = scans.createPolicy(
        new SecurityScanDao.ScanPolicy(
            null, "concurrent-policy", true, Severity.HIGH, false, false, true,
            3600L, List.of("linux/amd64"), 1, "contract", now, now));
    SecurityScanDao.ScanPolicy revisionDraft = new SecurityScanDao.ScanPolicy(
        null, "CONCURRENT-POLICY", true, Severity.HIGH, false, false, true,
        3600L, List.of("linux/amd64"), 1, "contract", now, now);
    List<Long> allocatedRevisions = invokeConcurrently(List.of(
        () -> inTransaction(() -> scans.createNextPolicyRevision(
                concurrentPolicy.id(), revisionDraft)
            .map(SecurityScanDao.ScanPolicy::revision)
            .orElse(0L)),
        () -> inTransaction(() -> scans.createNextPolicyRevision(
                concurrentPolicy.id(), revisionDraft)
            .map(SecurityScanDao.ScanPolicy::revision)
            .orElse(0L))), 2);
    assertEquals(
        List.of(0L, 2L),
        allocatedRevisions.stream().sorted().toList(),
        "only one replica may revise the expected immutable policy head");
    long profileId = profile.id();
    long policyId = policy.id();

    long concurrentConfigRepositoryId =
        createRepository("scan-config-concurrent", RepositoryFormat.MAVEN2);
    SecurityScanDao.RepositoryScanConfig firstConcurrentConfig =
        new SecurityScanDao.RepositoryScanConfig(
            concurrentConfigRepositoryId, true, profileId, true, false,
            EnforcementMode.AUDIT, PolicyAction.ALLOW, PolicyAction.BLOCK,
            PolicyAction.ALLOW, 3600L, null, 1, now, now);
    SecurityScanDao.RepositoryScanConfig secondConcurrentConfig =
        new SecurityScanDao.RepositoryScanConfig(
            concurrentConfigRepositoryId, false, profileId, true, false,
            EnforcementMode.ENFORCE, PolicyAction.BLOCK, PolicyAction.ALLOW,
            PolicyAction.BLOCK, 7200L, null, 1, now.plusSeconds(1), now.plusSeconds(1));
    CyclicBarrier concurrentConfigStart = new CyclicBarrier(2);
    List<SecurityScanDao.RepositoryScanConfig> concurrentConfigs = invokeConcurrently(List.of(
        () -> {
          concurrentConfigStart.await();
          return inTransaction(() -> scans.upsertRepositoryConfig(firstConcurrentConfig));
        },
        () -> {
          concurrentConfigStart.await();
          return inTransaction(() -> scans.upsertRepositoryConfig(secondConcurrentConfig));
        }), 2);
    assertTrue(concurrentConfigs.getFirst().enabled());
    assertEquals(EnforcementMode.AUDIT, concurrentConfigs.getFirst().enforcementMode());
    assertFalse(concurrentConfigs.getLast().enabled());
    assertEquals(EnforcementMode.ENFORCE, concurrentConfigs.getLast().enforcementMode());
    assertEquals(
        List.of(1L, 2L),
        concurrentConfigs.stream()
            .map(SecurityScanDao.RepositoryScanConfig::configRevision)
            .sorted()
            .toList(),
        "both concurrent first writes must be applied and receive distinct revisions");

    SecurityScanDao.RepositoryScanConfig config = scans.upsertRepositoryConfig(
        new SecurityScanDao.RepositoryScanConfig(
            repositoryId, true, profileId, true, true, EnforcementMode.AUDIT,
            PolicyAction.ALLOW, PolicyAction.BLOCK, PolicyAction.ALLOW,
            3600L, policyId, 1, now, now));
    assertTrue(config.enabled());
    assertEquals(1, config.configRevision());
    List<SecurityScanDao.DownloadPolicyContext> directPolicyContexts =
        scans.findDownloadPolicyContexts(repositoryId, repositoryId);
    assertEquals(1, directPolicyContexts.size());
    assertEquals(repositoryId, directPolicyContexts.getFirst().config().repositoryId());
    assertEquals(profileId, directPolicyContexts.getFirst().profile().id());
    long proxyPolicyRepositoryId =
        createRepository(
            "scan-policy-proxy", RepositoryFormat.MAVEN2, RepositoryType.PROXY);
    scans.upsertRepositoryConfig(new SecurityScanDao.RepositoryScanConfig(
        proxyPolicyRepositoryId, true, profileId, false, true, EnforcementMode.ENFORCE,
        PolicyAction.BLOCK, PolicyAction.BLOCK, PolicyAction.BLOCK,
        3600L, null, 1, now, now));
    assertEquals(
        List.of(proxyPolicyRepositoryId),
        scans.findDownloadPolicyContexts(proxyPolicyRepositoryId, null).stream()
            .map(context -> context.config().repositoryId())
            .toList(),
        "uncached proxy policy lookup must honor the proxy-content toggle");
    assertEquals(
        List.of(repositoryId),
        scans.findRepositoryConfigs(List.of(repositoryId, repositoryId, Long.MAX_VALUE)).stream()
            .map(SecurityScanDao.RepositoryScanConfig::repositoryId)
            .toList(),
        "repository configuration scope must be deduplicated and relation-backed");

    SecurityScanDao.TaskDraft draft = new SecurityScanDao.TaskDraft(
        repositoryId,
        assetId,
        SubjectKind.ASSET_BLOB,
        "sha256:" + "2".repeat(64),
        1,
        profileId,
        1,
        null,
        ScanStage.CATALOG_AND_MATCH,
        RequestReason.CONTENT_CHANGED,
        0,
        5,
        "contract",
        null,
        null,
        now);
    long taskId = scans.createTask(draft);
    assertEquals(taskId, scans.createTask(draft), "automatic task creation must deduplicate");
    SecurityScanDao.AssetSecurityState initialPendingState =
        scans.findAssetState(assetId, profileId).orElseThrow();
    assertEquals(ScanState.PENDING, initialPendingState.scanState());
    assertEquals(PolicyDecision.ALLOW, initialPendingState.policyDecision());
    assertEquals("SCAN_PENDING", initialPendingState.policyReasonCode());
    assertArrayEquals(
        PersistenceHashes.sha256("sha256:" + "2".repeat(64)),
        initialPendingState.subjectIdentityHash(),
        "task creation must atomically materialize the pending projection");
    assertEquals(
        taskId,
        scans.listTasks(null, null, "scan-contract", 0, 10).getFirst().id(),
        "task search must include repository names");
    assertEquals(
        taskId,
        scans.listTasksByRepositories(
                List.of(repositoryId), null, "scan-contract", 0, 10)
            .getFirst()
            .id(),
        "global task pages must filter a visible repository relation in one query");
    assertTrue(scans.listTasksByRepositories(
        List.of(Long.MAX_VALUE), null, null, 0, 10).isEmpty());
    assertTrue(scans.listTasks(null, null, "missing-task", 0, 10).isEmpty());
    assertEquals(
        Optional.of(now),
        scans.oldestPendingTaskCreatedAt(),
        "the oldest pending task timestamp must be observable");
    assertEquals(
        1,
        scans.metricSummary(100).pendingTasks(),
        "periodic task metrics must use a bounded status projection");

    scans.upsertAssetPolicyStateIfCurrent(new SecurityScanDao.AssetPolicyState(
        assetId,
        profileId,
        repositoryId,
        1,
        null,
        policyId,
        1L,
        config.configRevision(),
        PolicyDecision.ALLOW,
        "SCAN_PENDING",
        0,
        null,
        null,
        now,
        0));
    assertTrue(scans.cancelTask(taskId, now.plusSeconds(1)));
    SecurityScanDao.AssetSecurityState cancelledState =
        scans.findAssetState(assetId, profileId).orElseThrow();
    assertEquals(ScanState.CANCELLED, cancelledState.scanState());
    assertEquals(PolicyDecision.BLOCK_SCAN_FAILED, cancelledState.policyDecision());
    assertEquals("TASK_CANCELLED", cancelledState.policyReasonCode());
    SecurityScanDao.AssetPolicyState cancelledPolicyState =
        scans.findAssetPolicyState(assetId, profileId, repositoryId).orElseThrow();
    assertEquals(PolicyDecision.BLOCK_SCAN_FAILED, cancelledPolicyState.policyDecision());
    assertEquals("TASK_CANCELLED", cancelledPolicyState.policyReasonCode());
    assertTrue(scans.requeueTask(taskId, now.plusSeconds(2), "contract"));
    assertEquals(
        ScanState.PENDING,
        scans.findAssetState(assetId, profileId).orElseThrow().scanState(),
        "retrying a cancelled task must restore pending-action semantics");
    assertEquals(
        PolicyDecision.ALLOW,
        scans.findAssetPolicyState(assetId, profileId, repositoryId)
            .orElseThrow()
            .policyDecision());

    CountDownLatch administrativeLockHeld = new CountDownLatch(1);
    CountDownLatch claimAttemptFinished = new CountDownLatch(1);
    List<Boolean> lockFenceOutcomes = invokeConcurrently(List.of(
        () -> inTransaction(() -> {
          SecurityScanDao.ScanTask locked =
              scans.findTaskForUpdate(taskId).orElseThrow();
          administrativeLockHeld.countDown();
          try {
            assertTrue(claimAttemptFinished.await(
                30, java.util.concurrent.TimeUnit.SECONDS));
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(stopped);
          }
          return locked.status() == TaskStatus.PENDING;
        }),
        () -> {
          assertTrue(administrativeLockHeld.await(
              30, java.util.concurrent.TimeUnit.SECONDS));
          try {
            return inTransaction(() -> scans.claimTasks(
                "replica-while-admin-locked",
                now.plusSeconds(2),
                now.plusSeconds(30),
                1).isEmpty());
          } finally {
            claimAttemptFinished.countDown();
          }
        }), 2);
    assertEquals(List.of(true, true), lockFenceOutcomes);

    CountDownLatch projectionCandidateLockHeld = new CountDownLatch(1);
    CountDownLatch releaseProjectionCandidateLock = new CountDownLatch(1);
    CountDownLatch administrativeLookupStarted = new CountDownLatch(1);
    CountDownLatch administrativeLookupReturned = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var projection = executor.submit(() -> inTransaction(() -> {
        scans.taskProjectionIsSuperseded(taskId);
        projectionCandidateLockHeld.countDown();
        try {
          assertTrue(releaseProjectionCandidateLock.await(
              30, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException stopped) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(stopped);
        }
        return true;
      }));
      assertTrue(projectionCandidateLockHeld.await(
          30, java.util.concurrent.TimeUnit.SECONDS));
      var administration = executor.submit(() -> {
        administrativeLookupStarted.countDown();
        try {
          return inTransaction(() -> scans.findTaskForUpdate(taskId).isPresent());
        } finally {
          administrativeLookupReturned.countDown();
        }
      });
      assertTrue(administrativeLookupStarted.await(
          30, java.util.concurrent.TimeUnit.SECONDS));
      try {
        assertFalse(
            administrativeLookupReturned.await(
                500, java.util.concurrent.TimeUnit.MILLISECONDS),
            "administrative lookup must wait for the candidate before locking the task");
      } finally {
        releaseProjectionCandidateLock.countDown();
      }
      assertTrue(projection.get(30, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(administration.get(30, java.util.concurrent.TimeUnit.SECONDS));
    }

    SecurityScanDao.ScanTask firstLease = inTransaction(() -> scans.claimTasks(
        "replica-a", now.plusSeconds(2), now.plusSeconds(30), 1).getFirst());
    assertTrue(inTransaction(() -> scans.claimTasks(
        "replica-b", now.plusSeconds(29), now.plusSeconds(59), 1)).isEmpty());
    SecurityScanDao.ScanTask takeover = inTransaction(() -> scans.claimTasks(
        "replica-b", now.plusSeconds(31), now.plusSeconds(61), 1).getFirst());
    assertNotEquals(firstLease.leaseToken(), takeover.leaseToken());
    assertFalse(scans.heartbeatTask(
        taskId, firstLease.leaseToken(), now.plusSeconds(90), now.plusSeconds(32)));
    assertFalse(scans.completeTask(taskId, firstLease.leaseToken(), now.plusSeconds(32)));
    assertFalse(inTransaction(
        () -> scans.lockCurrentTaskLease(taskId, firstLease.leaseToken())));
    assertTrue(inTransaction(
        () -> scans.lockCurrentTaskLease(taskId, takeover.leaseToken())));

    long cancellationFenceBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/cancellation-fence.json", "scan-cancellation-fence"));
    String cancellationKey = "cancel-publication-fence";
    long cancellationTaskId = scans.createTask(new SecurityScanDao.TaskDraft(
        repositoryId,
        assetId,
        SubjectKind.ASSET_BLOB,
        "sha256:" + "2".repeat(64),
        1,
        profileId,
        1,
        null,
        ScanStage.CATALOG_AND_MATCH,
        RequestReason.MANUAL,
        ScanTaskPriorities.MANUAL,
        2,
        "contract",
        cancellationKey,
        cancellationKey,
        now.plusSeconds(2)));
    SecurityScanDao.ScanTask cancellationLease = inTransaction(() ->
        scans.claimTasks(
                "cancellation-fence-replica",
                now.plusSeconds(2),
                now.plusSeconds(62),
                10)
            .stream()
            .filter(task -> task.id() == cancellationTaskId)
            .findFirst()
            .orElseThrow());
    CyclicBarrier cancellationFenceStart = new CyclicBarrier(2);
    List<Boolean> cancellationFenceOutcomes = invokeConcurrently(List.of(
        () -> {
          cancellationFenceStart.await();
          return inTransaction(() -> {
            if (!scans.lockCurrentTaskLease(
                cancellationTaskId, cancellationLease.leaseToken())) {
              return false;
            }
            return stores().blobReferences().retain(
                "security-scan-persisting", cancellationTaskId, cancellationFenceBlobId);
          });
        },
        () -> {
          cancellationFenceStart.await();
          return inTransaction(() -> {
            boolean cancelled =
                scans.cancelTask(cancellationTaskId, now.plusSeconds(3));
            stores().blobReferences().releaseOwner(
                "security-scan-persisting", cancellationTaskId);
            return cancelled;
          });
        }), 2);
    assertTrue(cancellationFenceOutcomes.getLast());
    assertFalse(
        stores().blobReferences().isReferenced(cancellationFenceBlobId),
        "cancellation must either reject a late report owner or remove an earlier publication");
    assertEquals(
        1,
        stores().assets().markBlobDeletedIfUnreferenced(
            cancellationFenceBlobId, "scan-cancellation-fence-cleanup"));
    assertEquals(
        1,
        stores().assets().hardDeleteBlobByIdIfDeleted(cancellationFenceBlobId));

    SecurityScanDao.ScannerSnapshot snapshot = scans.insertSnapshotOrFindExisting(
        new SecurityScanDao.ScannerSnapshot(
            null, "contract-adapter", "v1", "grype", "0.1.0", "fixture-db",
            now.minusSeconds(60), "a".repeat(64), "b".repeat(64), now, true,
            Map.of("catalogEngineVersion", "1.0.0")));
    SecurityScanDao.ScannerSnapshot observedAgain = scans.insertSnapshotOrFindExisting(
        new SecurityScanDao.ScannerSnapshot(
            null, "contract-adapter", "v1", "grype", "0.1.0", "fixture-db",
            now.minusSeconds(30), "a".repeat(64), "b".repeat(64), now.plusSeconds(1), true,
            Map.of("catalogEngineVersion", "1.0.0")));
    assertEquals(snapshot.id(), observedAgain.id());
    assertEquals(now.plusSeconds(1), observedAgain.observedAt());
    SecurityScanDao.ScannerSnapshot staleObservation = scans.insertSnapshotOrFindExisting(
        new SecurityScanDao.ScannerSnapshot(
            null, "contract-adapter", "v1", "grype", "0.1.0", "fixture-db",
            now.minusSeconds(90), "a".repeat(64), "b".repeat(64), now.minusSeconds(1), true,
            Map.of("catalogEngineVersion", "stale")));
    assertEquals(snapshot.id(), staleObservation.id());
    assertEquals(
        now.plusSeconds(1),
        staleObservation.observedAt(),
        "an older replica observation must not move the shared snapshot timestamp backwards");
    assertEquals(
        now.minusSeconds(60),
        staleObservation.vulnerabilityDatabaseUpdatedAt(),
        "database build provenance is immutable for one snapshot fingerprint");
    assertEquals("1.0.0", staleObservation.details().get("catalogEngineVersion"));
    SecurityScanDao.ScannerSnapshot newestDatabase =
        scans.insertSnapshotOrFindExisting(new SecurityScanDao.ScannerSnapshot(
            null, "contract-adapter", "v1", "grype", "0.1.0", "newest-db",
            now.minusSeconds(10), "a".repeat(64), "c".repeat(64), now.plusSeconds(2), true,
            Map.of()));
    SecurityScanDao.ScannerSnapshot laggingReplica =
        scans.insertSnapshotOrFindExisting(new SecurityScanDao.ScannerSnapshot(
            null, "contract-adapter", "v1", "grype", "0.1.0", "lagging-db",
            now.minusSeconds(300), "a".repeat(64), "d".repeat(64), now.plusSeconds(3), true,
            Map.of()));
    assertEquals(
        laggingReplica.id(),
        scans.latestScannerSnapshot().orElseThrow().id(),
        "latest observation still represents scanner health");
    assertEquals(
        newestDatabase.id(),
        scans.latestReadyScannerSnapshot(now.plusSeconds(5)).orElseThrow().id(),
        "a later observation from a lagging replica must not regress the authoritative DB epoch");
    Instant equalDatabaseEpoch = now.minusSeconds(5);
    SecurityScanDao.ScannerSnapshot equalEpochFirst =
        scans.insertSnapshotOrFindExisting(new SecurityScanDao.ScannerSnapshot(
            null, "contract-adapter", "v1", "grype", "0.1.0", "equal-db-first",
            equalDatabaseEpoch, "a".repeat(64), "e".repeat(64), now.plusSeconds(4), true,
            Map.of()));
    SecurityScanDao.ScannerSnapshot equalEpochSecond =
        scans.insertSnapshotOrFindExisting(new SecurityScanDao.ScannerSnapshot(
            null, "contract-adapter", "v1", "grype", "0.2.0", "equal-db-second",
            equalDatabaseEpoch, "a".repeat(64), "f".repeat(64), now.plusSeconds(5), true,
            Map.of()));
    assertTrue(equalEpochSecond.id() > equalEpochFirst.id());
    scans.insertSnapshotOrFindExisting(new SecurityScanDao.ScannerSnapshot(
        null, "contract-adapter", "v1", "grype", "0.1.0", "equal-db-first",
        equalDatabaseEpoch, "a".repeat(64), "e".repeat(64), now.plusSeconds(6), true,
        Map.of()));
    assertEquals(
        equalEpochFirst.id(),
        scans.latestScannerSnapshot().orElseThrow().id(),
        "mutable observation time remains the scanner-health signal");
    assertEquals(
        equalEpochSecond.id(),
        scans.latestReadyScannerSnapshot(now.plusSeconds(7)).orElseThrow().id(),
        "equal database epochs must use the immutable snapshot ID tie-breaker");
    long snapshotTaskId = scans.createTask(new SecurityScanDao.TaskDraft(
        repositoryId,
        assetId,
        SubjectKind.ASSET_BLOB,
        "sha256:" + "2".repeat(64),
        1,
        profileId,
        1,
        snapshot.id(),
        ScanStage.MATCH_ONLY,
        RequestReason.VULNERABILITY_DB_CHANGED,
        25,
        1,
        "contract",
        "snapshot-reactivation",
        "snapshot-reactivation",
        now.plusSeconds(2)));
    SecurityScanDao.ScanTask snapshotLease = inTransaction(() -> scans.claimTasks(
        "snapshot-replica", now.plusSeconds(2), now.plusSeconds(32), 1).getFirst());
    assertEquals(snapshotTaskId, snapshotLease.id());
    assertTrue(scans.completeTask(
        snapshotTaskId, snapshotLease.leaseToken(), now.plusSeconds(3)));
    assertTrue(scans.reactivateSnapshotTask(
        snapshotTaskId, snapshot.id(), now.plusSeconds(4), "snapshot-reconciler"));
    SecurityScanDao.ScanTask reactivatedSnapshotTask =
        scans.findTask(snapshotTaskId).orElseThrow();
    assertEquals(
        com.github.klboke.kkrepo.security.scan.ScanEnums.TaskStatus.PENDING,
        reactivatedSnapshotTask.status());
    assertEquals(0, reactivatedSnapshotTask.attempts());
    assertFalse(scans.reactivateSnapshotTask(
        snapshotTaskId, snapshot.id() + 1, now.plusSeconds(5), "snapshot-reconciler"));
    SecurityScanDao.ScanTask reactivatedSnapshotLease = inTransaction(() -> scans.claimTasks(
        "snapshot-replica-2", now.plusSeconds(5), now.plusSeconds(35), 1).getFirst());
    assertEquals(snapshotTaskId, reactivatedSnapshotLease.id());
    assertTrue(scans.completeTask(
        snapshotTaskId, reactivatedSnapshotLease.leaseToken(), now.plusSeconds(6)));

    long deletedDocumentBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/deleted-sbom.json", "scan-deleted-sbom"));
    assertEquals(
        1,
        stores().assets()
            .markBlobDeletedIfUnreferenced(deletedDocumentBlobId, "gc-race-fixture"));
    assertFalse(
        inTransaction(() ->
            stores()
                .blobReferences()
                .retain("security-scan-persisting", taskId, deletedDocumentBlobId)),
        "a committed soft-delete fence must reject late ownership publication");
    SecurityScanDao.Sbom rejectedSbom = new SecurityScanDao.Sbom(
        null,
        SubjectKind.ASSET_BLOB,
        "sha256:" + "7".repeat(64),
        null,
        "syft",
        "1.0.0",
        "7".repeat(64),
        "8".repeat(64),
        deletedDocumentBlobId,
        "9".repeat(64),
        "CycloneDX",
        "1.6",
        1,
        0,
        true,
        now);
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(() -> scans.insertSbomOrFindExisting(rejectedSbom)),
        "metadata publication must abort when GC committed the document deletion fence");
    assertTrue(
        scans.findSbomByCatalogFingerprint(rejectedSbom.catalogFingerprint()).isEmpty(),
        "a rejected document must not leave committed metadata pointing at deleted content");
    assertEquals(
        1,
        stores().assets().hardDeleteBlobByIdIfDeleted(deletedDocumentBlobId));
    long recoveredDocumentBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/recovered-sbom.json", "scan-recovered-sbom"));
    assertEquals(
        1,
        stores().assets()
            .markBlobDeletedIfUnreferenced(
                recoveredDocumentBlobId, "gc-recovery-fixture"));
    inTransaction(() -> {
      assertTrue(
          stores().assets().restoreDeletedBlobById(recoveredDocumentBlobId).isPresent());
      assertTrue(
          stores().blobReferences().retain(
              "security-scan-persisting", taskId, recoveredDocumentBlobId));
      return null;
    });
    assertEquals(
        0,
        stores().assets()
            .markBlobDeletedIfUnreferenced(
                recoveredDocumentBlobId, "gc-recovery-referenced-fixture"));
    assertEquals(
        1,
        stores().blobReferences().releaseOwner("security-scan-persisting", taskId));
    assertEquals(
        1,
        stores().assets()
            .markBlobDeletedIfUnreferenced(
                recoveredDocumentBlobId, "gc-recovery-cleanup"));
    assertEquals(
        1,
        stores().assets().hardDeleteBlobByIdIfDeleted(recoveredDocumentBlobId));
    long sbomBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/sbom.json", "scan-sbom"));
    assertTrue(inTransaction(() ->
        stores().blobReferences().retain("security-scan-persisting", taskId, sbomBlobId)));
    assertEquals(
        0,
        stores().assets().markBlobDeletedIfUnreferenced(sbomBlobId, "gc-race-fixture"),
        "ownership published first must prevent the soft-delete fence");
    long concurrentFenceBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/concurrent-sbom.json", "scan-concurrent-sbom"));
    CyclicBarrier concurrentFenceStart = new CyclicBarrier(2);
    List<Boolean> concurrentFenceOutcomes = invokeConcurrently(List.of(
        () -> {
          concurrentFenceStart.await();
          return inTransaction(() -> stores().blobReferences().retain(
              "security-scan-persisting", taskId, concurrentFenceBlobId));
        },
        () -> {
          concurrentFenceStart.await();
          return inTransaction(() -> stores().assets().markBlobDeletedIfUnreferenced(
              concurrentFenceBlobId, "gc-concurrent-race-fixture") == 1);
        }), 2);
    assertEquals(
        1,
        concurrentFenceOutcomes.stream().filter(Boolean::booleanValue).count(),
        "concurrent ownership publication and the GC fence must have exactly one winner");
    if (concurrentFenceOutcomes.getFirst()) {
      assertEquals(
          1,
          stores().blobReferences().release(
              "security-scan-persisting", taskId, concurrentFenceBlobId));
      assertEquals(
          1,
          stores().assets().markBlobDeletedIfUnreferenced(
              concurrentFenceBlobId, "gc-concurrent-cleanup"));
    }
    assertEquals(1, stores().assets().hardDeleteBlobByIdIfDeleted(concurrentFenceBlobId));
    long concurrentReconcileBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/reconcile-sbom.json", "scan-reconcile-sbom"));
    CyclicBarrier concurrentReconcileStart = new CyclicBarrier(2);
    List<Boolean> concurrentReconcileOutcomes = invokeConcurrently(List.of(
        () -> {
          concurrentReconcileStart.await();
          return inTransaction(() -> stores().blobReferences().retain(
              "security-scan-persisting", taskId, concurrentReconcileBlobId));
        },
        () -> {
          concurrentReconcileStart.await();
          return inTransaction(() -> stores().assets().markUnreferencedBlobsDeletedAfter(
              concurrentReconcileBlobId - 1,
              1,
              1,
              "gc-concurrent-reconcile-fixture").marked() == 1);
        }), 2);
    assertEquals(
        1,
        concurrentReconcileOutcomes.stream().filter(Boolean::booleanValue).count(),
        "concurrent ownership publication and orphan reconciliation must have one winner");
    if (concurrentReconcileOutcomes.getFirst()) {
      assertEquals(
          1,
          stores().blobReferences().release(
              "security-scan-persisting", taskId, concurrentReconcileBlobId));
      assertEquals(
          1,
          stores().assets().markBlobDeletedIfUnreferenced(
              concurrentReconcileBlobId, "gc-concurrent-reconcile-cleanup"));
    }
    assertEquals(1, stores().assets().hardDeleteBlobByIdIfDeleted(concurrentReconcileBlobId));
    SecurityScanDao.Sbom sbomDraft = new SecurityScanDao.Sbom(
        null, SubjectKind.ASSET_BLOB, "sha256:" + "2".repeat(64), null,
        "syft", "1.0.0", "c".repeat(64), "d".repeat(64), sbomBlobId,
        "e".repeat(64), "CycloneDX", "1.6", 1, 0, true, now);
    List<Long> sbomIds = invokeConcurrently(List.of(
        () -> inTransaction(() -> scans.insertSbomOrFindExisting(sbomDraft).id()),
        () -> inTransaction(() -> scans.insertSbomOrFindExisting(sbomDraft).id())), 2);
    assertEquals(1, new HashSet<>(sbomIds).size());
    long sbomId = sbomIds.getFirst();
    assertEquals(
        1,
        stores().blobReferences().release("security-scan-persisting", taskId, sbomBlobId));
    SecurityScanDao.SbomComponent component = new SecurityScanDao.SbomComponent(
        null, sbomId, "pkg:maven/com.acme/app@1.0", null,
        "pkg:maven/com.acme/app@1.0", null, "library", "com.acme", "app", "1.0",
        "direct", List.of("app.jar"), List.of("Apache-2.0"), Map.of());
    assertEquals(1, inTransaction(() -> scans.insertSbomComponents(sbomId, List.of(component))));
    assertEquals(0, inTransaction(() -> scans.insertSbomComponents(sbomId, List.of(component))));
    long atomicSbomBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/atomic-sbom.json", "scan-atomic-sbom"));
    SecurityScanDao.Sbom atomicSbom = new SecurityScanDao.Sbom(
        null, SubjectKind.ASSET_BLOB, "sha256:" + "4".repeat(64), null,
        "syft", "1.0.0", "f".repeat(64), "atomic-" + "f".repeat(57), atomicSbomBlobId,
        "a".repeat(64), "CycloneDX", "1.6", 1, 0, true, now);
    SecurityScanDao.SbomComponent invalidComponent = new SecurityScanDao.SbomComponent(
        null, 0, "pkg:maven/com.acme/invalid@1.0", null,
        "pkg:maven/com.acme/invalid@1.0", null, "library", "com.acme", null, "1.0",
        "direct", List.of(), List.of(), Map.of());
    assertThrows(
        RuntimeException.class,
        () -> inTransaction(() -> scans.publishSbom(atomicSbom, List.of(invalidComponent))),
        "SBOM metadata and its component projection must roll back together");
    assertTrue(scans.findSbomByCatalogFingerprint(atomicSbom.catalogFingerprint()).isEmpty());
    assertEquals(
        1,
        stores().assets().markBlobDeletedIfUnreferenced(
            atomicSbomBlobId, "atomic-sbom-rollback"));
    assertEquals(1, stores().assets().hardDeleteBlobByIdIfDeleted(atomicSbomBlobId));

    long deletedReportBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/deleted-report.json", "scan-deleted-report"));
    assertEquals(
        1,
        stores().assets()
            .markBlobDeletedIfUnreferenced(deletedReportBlobId, "gc-report-race-fixture"));
    SecurityScanDao.ScanRun rejectedRun = new SecurityScanDao.ScanRun(
        null,
        taskId,
        sbomId,
        snapshot.id(),
        "6".repeat(64),
        "7".repeat(64),
        ScanState.COMPLETE,
        ScanCompleteness.COMPLETE,
        deletedReportBlobId,
        "8".repeat(64),
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        Severity.UNKNOWN,
        List.of(),
        List.of(),
        now,
        now.plusSeconds(2),
        now.plusSeconds(2));
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(() -> scans.insertRunOrFindExisting(rejectedRun)),
        "run publication must abort when GC committed the report deletion fence");
    assertTrue(
        scans.findRunByMatchFingerprint(rejectedRun.matchFingerprint()).isEmpty(),
        "a rejected report must not leave committed run metadata");
    assertEquals(
        1,
        stores().assets().hardDeleteBlobByIdIfDeleted(deletedReportBlobId));

    long reportBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/report.json", "scan-report"));
    SecurityScanDao.ScanRun runDraft = new SecurityScanDao.ScanRun(
        null, taskId, sbomId, snapshot.id(), "f".repeat(64), "0".repeat(64),
        ScanState.COMPLETE, ScanCompleteness.COMPLETE, reportBlobId, "1".repeat(64),
        1, 1, 0, 1, 0, 0, 0, Severity.HIGH,
        List.of("linux/amd64"), List.of("linux/arm64"),
        now, now.plusSeconds(2), now.plusSeconds(2));
    List<Long> runIds = invokeConcurrently(List.of(
        () -> inTransaction(() -> scans.insertRunOrFindExisting(runDraft).id()),
        () -> inTransaction(() -> scans.insertRunOrFindExisting(runDraft).id())), 2);
    assertEquals(1, new HashSet<>(runIds).size());
    long runId = runIds.getFirst();
    assertEquals(List.of("linux/amd64"), scans.findRun(runId).orElseThrow().scannedPlatforms());
    assertEquals(List.of("linux/arm64"), scans.findRun(runId).orElseThrow().missingPlatforms());
    long groupRepositoryId =
        createRepository("scan-contract-group", RepositoryFormat.MAVEN2, RepositoryType.GROUP);
    stores().repositories().addMember(groupRepositoryId, repositoryId, 0);
    inTransaction(() -> {
      scans.associateRun(
          runId, repositoryId, assetId, profileId, 1, now.plusSeconds(2));
      scans.associateRun(
          runId, repositoryId, assetId, profileId, 1, now.plusSeconds(2));
      scans.associateRun(
          runId, groupRepositoryId, assetId, profileId, 1, now.plusSeconds(2));
      return null;
    });
    assertEquals(
        java.util.stream.Stream.of(repositoryId, groupRepositoryId).sorted().toList(),
        scans.listRepositoryIdsForRun(runId));
    assertEquals(
        runId,
        scans.listRuns(repositoryId, "complete", 0, 10).getFirst().id(),
        "run search must include status and completeness");
    assertEquals(
        runId,
        scans.listRunsByRepositories(
                List.of(repositoryId, groupRepositoryId), "scan-contract-group", 0, 10)
            .getFirst()
            .id(),
        "global run pages must search names inside the visible repository relation");

    SecurityScanDao.ScanFinding finding = new SecurityScanDao.ScanFinding(
        null, runId, "GHSA-fixture|pkg:maven/com.acme/app@1.0", null,
        "GHSA-fixture", List.of("CVE-2026-0001"), "fixture",
        "pkg:maven/com.acme/app@1.0", "app", "1.0", List.of("1.1"),
        Severity.HIGH, "fixture", "CVSS:3.1/AV:N", 8.1,
        "<script>fixture</script>", "details", "javascript:alert(1)",
        List.of("app.jar"), "active", now.plusSeconds(2));
    assertEquals(1, inTransaction(() -> scans.insertFindings(runId, List.of(finding))));
    assertEquals(0, inTransaction(() -> scans.insertFindings(runId, List.of(finding))));
    SecurityScanDao.ScanFinding storedFinding =
        scans.listFindings(repositoryId, runId, Severity.HIGH, 0, 10).getFirst();
    assertNull(storedFinding.primaryUrl(), "unsafe finding URLs must not reach the UI projection");
    assertEquals(
        storedFinding.id(),
        inTransaction(() -> scans.findFindingForUpdate(storedFinding.id()).orElseThrow()).id());
    assertEquals(
        storedFinding.id(),
        scans.listFindings(repositoryId, null, null, "ghsa-fixture", 0, 10)
            .getFirst().id(),
        "finding search must include advisory identifiers");
    assertEquals(
        storedFinding.id(),
        scans.listFindingsByRepositories(
                List.of(repositoryId, groupRepositoryId),
                null,
                null,
                "scan-contract-group",
                0,
                10)
            .getFirst()
            .id(),
        "global finding pages must search names inside the visible repository relation");
    assertEquals(
        policyId,
        scans.listPolicies("contract-policy", 0, 10).getFirst().id(),
        "policy search must include policy names");
    SecurityScanDao.ScanWaiver waiver = scans.createWaiver(new SecurityScanDao.ScanWaiver(
        null, "FINDING", repositoryId, assetId, storedFinding.id(), null, null, Map.of(),
        "Accepted until the scheduled upgrade", policyId, 1L, "contract", "contract",
        now.plusSeconds(3600), now.plusSeconds(2), now.plusSeconds(2)));
    assertThrows(
        IllegalArgumentException.class,
        () -> scans.createWaiver(new SecurityScanDao.ScanWaiver(
            null,
            "FINDING",
            repositoryId,
            assetId,
            storedFinding.id(),
            null,
            null,
            Map.of(),
            "r".repeat(SecurityScanDao.MAX_WAIVER_REASON_LENGTH + 1),
            policyId,
            1L,
            "contract",
            "contract",
            now.plusSeconds(3600),
            now.plusSeconds(2),
            now.plusSeconds(2))),
        "persistence must never silently truncate an audit justification");
    assertEquals(
        waiver.id(),
        scans.listWaivers(null, "scheduled upgrade", 0, 10).getFirst().id(),
        "waiver search must include reasons");
    assertEquals(
        waiver.id(),
        scans.listWaivers(null, "com/acme/app", 0, 10).getFirst().id(),
        "waiver search must include artifact paths");
    assertEquals(
        waiver.id(),
        scans.listWaivers(null, "ghsa-fixture", 0, 10).getFirst().id(),
        "waiver search must include exact-finding advisories");
    assertEquals(
        waiver.id(),
        scans.listActiveWaivers(
                repositoryId, assetId, now.plusSeconds(3), 0, 10)
            .getFirst()
            .id(),
        "active waiver lookup must cover repository-and-asset scoped rows");
    assertEquals(
        waiver.id(),
        scans.listWaiversForFindings(
                List.of(storedFinding.id()),
                List.of(runId),
                List.of(storedFinding.advisoryId(), "CVE-2026-0001"),
                List.of(storedFinding.packageUrl(), storedFinding.packageName()),
                0,
                10)
            .getFirst()
            .id(),
        "finding pages must query only waivers matching their finding and subject keys");
    assertTrue(scans.listWaiversForFindings(
        List.of(storedFinding.id()),
        List.of(runId),
        List.of(storedFinding.advisoryId()),
        List.of(storedFinding.packageUrl()),
        waiver.id(),
        10).isEmpty(), "finding waiver keyset cursor must exclude earlier rows");
    assertTrue(scans.listWaiversForFindings(
        List.of(storedFinding.id()),
        List.of(runId + 1),
        List.of(storedFinding.advisoryId()),
        List.of(storedFinding.packageUrl()),
        0,
        10).isEmpty());
    assertTrue(scans.runSubjectExists(runId, repositoryId, assetId));
    assertEquals(
        List.of(repositoryId, groupRepositoryId),
        scans.listRunSubjects(runId, 0, 0, 10).stream()
            .map(SecurityScanDao.ScanRunSubject::repositoryId)
            .toList());

    SecurityScanDao.AssetSecurityState storedState = scans.upsertAssetStateIfCurrent(
        new SecurityScanDao.AssetSecurityState(
            assetId, profileId, 1, PersistenceHashes.sha256("sha256:" + "2".repeat(64)),
            runId, ScanState.COMPLETE, ScanCompleteness.COMPLETE, true, Severity.HIGH,
            Map.of("high", 1), policyId, 1L, PolicyDecision.BLOCK_VULNERABILITY,
            "SEVERITY_THRESHOLD", now.plusSeconds(3600), now.plusSeconds(2), 0));
    assertEquals(runId, storedState.latestScanRunId());
    SecurityScanDao.AssetPolicyState policyState = scans.upsertAssetPolicyStateIfCurrent(
        new SecurityScanDao.AssetPolicyState(
            assetId, profileId, repositoryId, 1, runId, policyId, 1L,
            config.configRevision(), PolicyDecision.BLOCK_VULNERABILITY,
            "SEVERITY_THRESHOLD", 0, now.plusSeconds(3600), null,
            now.plusSeconds(2), 0));
    assertEquals(
        PolicyDecision.BLOCK_VULNERABILITY,
        scans.findAssetPolicyState(assetId, profileId, repositoryId)
            .orElseThrow().policyDecision());

    List<SecurityScanDao.DownloadPolicySnapshot> directSnapshots =
        scans.findDownloadPolicySnapshots(assetId, repositoryId);
    assertEquals(1, directSnapshots.size());
    SecurityScanDao.DownloadPolicySnapshot directSnapshot = directSnapshots.getFirst();
    assertEquals(assetId, directSnapshot.assetId());
    assertEquals(repositoryId, directSnapshot.sourceRepositoryId());
    assertEquals(RepositoryFormat.MAVEN2, directSnapshot.format());
    assertEquals(path, directSnapshot.path());
    assertEquals("ARTIFACT", directSnapshot.kind());
    assertEquals("application/java-archive", directSnapshot.contentType());
    assertEquals(42L, directSnapshot.blobSize());
    assertEquals(repositoryId, directSnapshot.config().repositoryId());
    assertEquals(profileId, directSnapshot.profile().id());
    assertEquals(artifactBlobId, directSnapshot.candidate().assetBlobId());
    assertEquals(runId, directSnapshot.assetState().latestScanRunId());
    assertEquals(policyId, directSnapshot.policy().id());
    assertEquals(
        PolicyDecision.BLOCK_VULNERABILITY,
        directSnapshot.policyState().policyDecision());
    assertEquals(
        List.of(assetId),
        scans.findDownloadPolicySnapshots(
                List.of(assetId, assetId), repositoryId).stream()
            .map(SecurityScanDao.DownloadPolicySnapshot::assetId)
            .distinct()
            .toList(),
        "batch policy lookup must deduplicate asset IDs and preserve snapshot semantics");
    assertEquals(1, scans.findDownloadPolicySnapshots(assetId, null).size());
    SecurityScanDao.ScanSummary visibleSummary = scans.summary(List.of(repositoryId));
    assertEquals(1, visibleSummary.completeAssets());
    assertEquals(1, visibleSummary.highFindings());
    assertEquals(
        1,
        scans.metricSummary(100).highRiskFindings(),
        "operational finding counters must include the current authoritative run");
    assertEquals(
        1,
        scans.summary(List.of(repositoryId, groupRepositoryId)).highFindings(),
        "a finding associated with multiple visible repositories must be counted once");
    assertEquals(
        new SecurityScanDao.ScanSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        scans.summary(List.of()),
        "an empty permission scope must not query or expose global scan totals");

    scans.upsertRepositoryConfig(
        new SecurityScanDao.RepositoryScanConfig(
            groupRepositoryId, true, profileId, false, true, EnforcementMode.ENFORCE,
            PolicyAction.BLOCK, PolicyAction.BLOCK, PolicyAction.BLOCK,
            3600L, null, 1, now, now));
    assertEquals(
        1,
        scans.findDownloadPolicySnapshots(assetId, groupRepositoryId).size(),
        "a group hosted-content toggle must exclude hosted member assets");
    assertTrue(
        scans.listTasksByRepositories(List.of(groupRepositoryId), null, null, 0, 10).isEmpty(),
        "a group task scope must honor the hosted-content toggle");
    SecurityScanDao.RepositoryScanConfig groupConfig = scans.upsertRepositoryConfig(
        new SecurityScanDao.RepositoryScanConfig(
            groupRepositoryId, true, profileId, true, true, EnforcementMode.ENFORCE,
            PolicyAction.BLOCK, PolicyAction.BLOCK, PolicyAction.BLOCK,
            3600L, null, 1, now, now));
    assertEquals(
        taskId,
        scans.listTasksByRepositories(List.of(groupRepositoryId), null, null, 0, 10)
            .getFirst()
            .id(),
        "group-visible task pages must include work stored against the concrete member source");
    assertEquals(
        1,
        scans.summary(List.of(groupRepositoryId)).runningTasks(),
        "group-only overview metrics must use the same member task scope as task pages");
    List<SecurityScanDao.DownloadPolicySnapshot> groupSnapshots =
        scans.findDownloadPolicySnapshots(assetId, groupRepositoryId);
    assertEquals(2, groupSnapshots.size());
    assertEquals(
        List.of(repositoryId, groupRepositoryId),
        scans.findDownloadPolicyContexts(repositoryId, groupRepositoryId).stream()
            .map(context -> context.config().repositoryId())
            .toList(),
        "uncached policy lookup must include only configurations on the entry-to-source path");
    SecurityScanDao.DownloadPolicySnapshot sourceContext = groupSnapshots.stream()
        .filter(row -> row.config().repositoryId() == repositoryId)
        .findFirst()
        .orElseThrow();
    SecurityScanDao.DownloadPolicySnapshot groupContext = groupSnapshots.stream()
        .filter(row -> row.config().repositoryId() == groupRepositoryId)
        .findFirst()
        .orElseThrow();
    assertEquals(policyId, sourceContext.policy().id());
    assertEquals(groupConfig.configRevision(), groupContext.config().configRevision());
    assertEquals(profileId, groupContext.profile().id());
    assertEquals(runId, groupContext.assetState().latestScanRunId());
    assertNull(groupContext.policy());
    assertNull(groupContext.policyState());
    scans.upsertAssetPolicyStateIfCurrent(
        new SecurityScanDao.AssetPolicyState(
            assetId,
            profileId,
            repositoryId,
            1,
            runId,
            policyId,
            1L,
            config.configRevision(),
            PolicyDecision.ALLOW,
            "SOURCE_ALLOW",
            0,
            now.plusSeconds(3600),
            null,
            now.plusSeconds(2),
            policyState.version()));
    scans.upsertAssetPolicyStateIfCurrent(
        new SecurityScanDao.AssetPolicyState(
            assetId,
            profileId,
            groupRepositoryId,
            1,
            runId,
            null,
            null,
            groupConfig.configRevision(),
            PolicyDecision.BLOCK_VULNERABILITY,
            "GROUP_BLOCK",
            0,
            now.plusSeconds(3600),
            null,
            now.plusSeconds(2),
            0));
    SecurityScanDao.ScanSummary sourceOnlySummary = scans.summary(List.of(repositoryId));
    assertEquals(1, sourceOnlySummary.completeAssets());
    assertEquals(0, sourceOnlySummary.blockedAssets());
    SecurityScanDao.ScanSummary groupOnlySummary = scans.summary(List.of(groupRepositoryId));
    assertEquals(1, groupOnlySummary.completeAssets());
    assertEquals(1, groupOnlySummary.blockedAssets());
    assertEquals(
        1,
        groupOnlySummary.highFindings(),
        "group-only visibility must include findings associated with the group policy context");
    scans.upsertRepositoryConfig(new SecurityScanDao.RepositoryScanConfig(
        groupRepositoryId,
        true,
        profileId,
        true,
        true,
        EnforcementMode.ENFORCE,
        PolicyAction.ALLOW,
        PolicyAction.BLOCK,
        PolicyAction.BLOCK,
        3600L,
        null,
        groupConfig.configRevision(),
        now,
        now.plusSeconds(3)));
    assertEquals(
        0,
        scans.summary(List.of(groupRepositoryId)).blockedAssets(),
        "a stale materialized block must not be counted when the current pending action allows");
    scans.upsertRepositoryConfig(new SecurityScanDao.RepositoryScanConfig(
        groupRepositoryId,
        true,
        profileId,
        true,
        true,
        EnforcementMode.ENFORCE,
        PolicyAction.BLOCK,
        PolicyAction.BLOCK,
        PolicyAction.BLOCK,
        3600L,
        null,
        groupConfig.configRevision() + 1,
        now,
        now.plusSeconds(4)));
    assertEquals(
        1,
        scans.summary(List.of(groupRepositoryId)).blockedAssets(),
        "a current blocking pending action must count before a policy row is rematerialized");
    long outerGroupRepositoryId =
        createRepository("scan-contract-outer", RepositoryFormat.MAVEN2, RepositoryType.GROUP);
    stores().repositories().addMember(outerGroupRepositoryId, groupRepositoryId, 0);
    scans.upsertRepositoryConfig(new SecurityScanDao.RepositoryScanConfig(
        outerGroupRepositoryId,
        true,
        profileId,
        true,
        true,
        EnforcementMode.ENFORCE,
        PolicyAction.BLOCK,
        PolicyAction.BLOCK,
        PolicyAction.BLOCK,
        3600L,
        null,
        1,
        now,
        now));
    assertEquals(
        java.util.stream.Stream.of(repositoryId, groupRepositoryId, outerGroupRepositoryId)
            .sorted()
            .toList(),
        scans.findDownloadPolicySnapshots(assetId, outerGroupRepositoryId).stream()
            .map(snapshotRow -> snapshotRow.config().repositoryId())
            .sorted()
            .toList(),
        "nested downloads must enforce every configured group on the actual source-to-entry path");
    long unrelatedGroupRepositoryId =
        createRepository("scan-contract-unrelated", RepositoryFormat.MAVEN2, RepositoryType.GROUP);
    scans.upsertRepositoryConfig(new SecurityScanDao.RepositoryScanConfig(
        unrelatedGroupRepositoryId,
        true,
        profileId,
        true,
        true,
        EnforcementMode.ENFORCE,
        PolicyAction.BLOCK,
        PolicyAction.BLOCK,
        PolicyAction.BLOCK,
        3600L,
        null,
        1,
        now,
        now));
    assertEquals(
        List.of(repositoryId),
        scans.findDownloadPolicySnapshots(assetId, unrelatedGroupRepositoryId).stream()
            .map(snapshotRow -> snapshotRow.config().repositoryId())
            .toList(),
        "an unrelated entry repository must not inject its policy into the download path");
    assertEquals(1, scans.invalidatePolicyStatesForWaiver(waiver));
    assertTrue(scans.findAssetPolicyState(assetId, profileId, repositoryId).isEmpty());
    assertTrue(scans.findAssetPolicyState(assetId, profileId, groupRepositoryId).isPresent());
    assertThrows(
        IllegalStateException.class,
        () -> scans.upsertAssetPolicyStateIfCurrent(policyState),
        "an evaluator that read waivers before their revision changed must be fenced");
    long currentWaiverRevision = scans.waiverRevision().currentRevision();
    SecurityScanDao.AssetPolicyState refreshedPolicyState =
        scans.upsertAssetPolicyStateIfCurrent(new SecurityScanDao.AssetPolicyState(
            policyState.assetId(),
            policyState.profileId(),
            policyState.repositoryId(),
            policyState.contentGeneration(),
            policyState.latestScanRunId(),
            policyState.policyId(),
            policyState.policyRevision(),
            policyState.configRevision(),
            policyState.policyDecision(),
            policyState.policyReasonCode(),
            policyState.waivedFindings(),
            policyState.staleAt(),
            policyState.nextWaiverExpiry(),
            now.plusSeconds(3),
            policyState.version(),
            currentWaiverRevision));
    scans.upsertRepositoryConfig(new SecurityScanDao.RepositoryScanConfig(
        repositoryId,
        true,
        profileId,
        false,
        true,
        EnforcementMode.AUDIT,
        PolicyAction.ALLOW,
        PolicyAction.ALLOW,
        PolicyAction.ALLOW,
        3600L,
        policyId,
        config.configRevision(),
        now,
        now));
    assertEquals(
        assetId,
        scans.listAssetStatesNeedingSnapshot(
                profileId, snapshot.id() + 1000, 0, 10)
            .getFirst()
            .assetId(),
        "group-only applicable policy state must participate in database rematching");
    SecurityScanDao.RepositoryScanConfig restoredSourceConfig =
        scans.upsertRepositoryConfig(new SecurityScanDao.RepositoryScanConfig(
            repositoryId,
            true,
            profileId,
            true,
            true,
            EnforcementMode.AUDIT,
            PolicyAction.ALLOW,
            PolicyAction.ALLOW,
            PolicyAction.ALLOW,
            3600L,
            policyId,
            config.configRevision(),
            now,
            now));

    SecurityScanDao.AssetSecurityState partialState = scans.upsertAssetStateIfCurrent(
        new SecurityScanDao.AssetSecurityState(
            storedState.assetId(),
            storedState.profileId(),
            storedState.contentGeneration(),
            storedState.subjectIdentityHash(),
            storedState.latestScanRunId(),
            ScanState.PARTIAL,
            ScanCompleteness.PARTIAL,
            false,
            storedState.maxSeverity(),
            storedState.findingCounts(),
            storedState.policyId(),
            storedState.policyRevision(),
            storedState.policyDecision(),
            storedState.policyReasonCode(),
            storedState.staleAt(),
            now.plusSeconds(3),
            storedState.version()));
    Instant partialPolicyStaleAt =
        Instant.ofEpochMilli(Instant.now().plusSeconds(3600).toEpochMilli());
    SecurityScanDao.AssetPolicyState partialSourcePolicyState =
        scans.upsertAssetPolicyStateIfCurrent(new SecurityScanDao.AssetPolicyState(
            refreshedPolicyState.assetId(),
            refreshedPolicyState.profileId(),
            refreshedPolicyState.repositoryId(),
            refreshedPolicyState.contentGeneration(),
            refreshedPolicyState.latestScanRunId(),
            refreshedPolicyState.policyId(),
            refreshedPolicyState.policyRevision(),
            restoredSourceConfig.configRevision(),
            PolicyDecision.BLOCK_VULNERABILITY,
            "PARTIAL_SOURCE_BLOCK",
            refreshedPolicyState.waivedFindings(),
            partialPolicyStaleAt,
            refreshedPolicyState.nextWaiverExpiry(),
            now.plusSeconds(3),
            refreshedPolicyState.version(),
            currentWaiverRevision));
    SecurityScanDao.RepositoryScanConfig currentGroupConfig =
        scans.findRepositoryConfig(groupRepositoryId).orElseThrow();
    SecurityScanDao.AssetPolicyState currentGroupPolicyState =
        scans.findAssetPolicyState(assetId, profileId, groupRepositoryId).orElseThrow();
    scans.upsertAssetPolicyStateIfCurrent(new SecurityScanDao.AssetPolicyState(
        currentGroupPolicyState.assetId(),
        currentGroupPolicyState.profileId(),
        currentGroupPolicyState.repositoryId(),
        currentGroupPolicyState.contentGeneration(),
        currentGroupPolicyState.latestScanRunId(),
        currentGroupPolicyState.policyId(),
        currentGroupPolicyState.policyRevision(),
        currentGroupConfig.configRevision(),
        PolicyDecision.ALLOW,
        "PARTIAL_GROUP_ALLOW",
        currentGroupPolicyState.waivedFindings(),
        partialPolicyStaleAt,
        currentGroupPolicyState.nextWaiverExpiry(),
        now.plusSeconds(3),
        currentGroupPolicyState.version(),
        currentWaiverRevision));
    SecurityScanDao.ScanSummary partialSourceSummary = scans.summary(List.of(repositoryId));
    assertEquals(1, partialSourceSummary.partialAssets());
    assertEquals(
        1,
        partialSourceSummary.blockedAssets(),
        "a partial scan must count its authoritative vulnerability block when partial is allowed");
    assertEquals(
        0,
        scans.summary(List.of(groupRepositoryId)).blockedAssets(),
        "a partial scan must count its authoritative allow when the partial fallback blocks");

    assertTrue(scans.listPolicyEvaluationTargets(
        repositoryId, repositoryId, profileId, restoredSourceConfig.configRevision(),
        policyId, 1L, 0, now.plusSeconds(3), 10).isEmpty());
    SecurityScanDao.PolicyEvaluationTarget ageExpired =
        scans.listPolicyEvaluationTargets(
                repositoryId,
                repositoryId,
                profileId,
                restoredSourceConfig.configRevision(),
                policyId,
                1L,
                0,
                partialPolicyStaleAt.plusSeconds(1),
                10)
            .getFirst();
    assertEquals(partialPolicyStaleAt, ageExpired.staleAt());
    assertEquals(1, scans.listPolicyEvaluationTargets(
        repositoryId, repositoryId, profileId, restoredSourceConfig.configRevision() + 1,
        policyId, 1L, 0, now.plusSeconds(3), 10).size());
    assertEquals(ScanState.PARTIAL, partialState.scanState());
    assertTrue(scans.completeTask(taskId, takeover.leaseToken(), now.plusSeconds(3)));
    assertTrue(
        scans.oldestPendingTaskCreatedAt().isEmpty(),
        "completed tasks must leave the pending-age gauge");

    assertTrue(stores().blobReferences().isReferenced(sbomBlobId));
    assertTrue(stores().blobReferences().isReferenced(reportBlobId));
    assertEquals(
        0,
        stores().assets().markBlobDeletedIfUnreferenced(sbomBlobId, "contract"),
        "SBOM document blobs remain live while referenced");
    assertEquals(
        0,
        stores().assets().markBlobDeletedIfUnreferenced(reportBlobId, "contract"),
        "raw report blobs remain live while referenced");

    AssetBlobRecord replacement = blob(blobStoreId, "scan/app-2.0.jar", "scan-replacement");
    long replacementBlobId = stores().assets().insertBlob(new AssetBlobRecord(
        replacement.id(), replacement.blobStoreId(), replacement.blobRef(),
        replacement.blobRefHash(), replacement.objectKey(), replacement.objectKeyHash(),
        replacement.sha1(), "4".repeat(64), replacement.md5(), replacement.size(),
        replacement.contentType(), replacement.createdBy(), replacement.createdByIp(),
        replacement.blobCreatedAt(), replacement.blobUpdatedAt(), replacement.attributes()));
    stores().assets().updateAssetBlobBinding(
        assetId, replacementBlobId, "application/java-archive", 42, now.plusSeconds(4));
    assertEquals(
        1,
        scans.findCandidate(assetId).orElseThrow().contentGeneration(),
        "asset writes must not synchronously mutate scan candidates");
    List<ArtifactChangeDao.ArtifactChange> replacementEvents = stores().artifactChanges()
        .listAfter(originalEvents.getFirst().id(), 1000).stream()
        .filter(event -> event.assetId() == assetId)
        .toList();
    assertEquals(1, replacementEvents.size());
    assertEquals(
        ArtifactChangeDao.ChangeKind.CONTENT_REPLACED,
        replacementEvents.getFirst().changeKind());
    assertEquals(artifactBlobId, replacementEvents.getFirst().previousAssetBlobId());
    assertEquals(replacementBlobId, replacementEvents.getFirst().assetBlobId());
    assertEquals(1, foldArtifactChanges(artifactChangeCursor));
    assertEquals(2, scans.findCandidate(assetId).orElseThrow().contentGeneration());
    SecurityScanDao.ScanSummary obsoleteGenerationSummary =
        scans.summary(List.of(repositoryId));
    assertEquals(0, obsoleteGenerationSummary.completeAssets());
    assertEquals(0, obsoleteGenerationSummary.partialAssets());
    assertEquals(0, obsoleteGenerationSummary.staleAssets());
    assertEquals(
        0,
        obsoleteGenerationSummary.highFindings(),
        "overview finding counters must exclude obsolete content generations");
    assertEquals(
        0,
        scans.metricSummary(100).partialAssets(),
        "operational state counters must exclude obsolete content generations");
    assertEquals(
        0,
        scans.metricSummary(100).highRiskFindings(),
        "operational finding counters must exclude obsolete content generations");
    SecurityScanDao.AssetSecurityState afterStaleFinalize = scans.upsertAssetStateIfCurrent(
        new SecurityScanDao.AssetSecurityState(
            assetId, profileId, 1, PersistenceHashes.sha256("stale"), runId, ScanState.COMPLETE,
            ScanCompleteness.COMPLETE, true, Severity.UNKNOWN, Map.of(), policyId, 1L,
            PolicyDecision.ALLOW, "STALE_WORKER", null, now.plusSeconds(5), 0));
    assertEquals(
        PolicyDecision.BLOCK_VULNERABILITY,
        afterStaleFinalize.policyDecision(),
        "an old generation cannot overwrite the latest materialized state");
    SecurityScanDao.AssetPolicyState afterStalePolicy = scans.upsertAssetPolicyStateIfCurrent(
        new SecurityScanDao.AssetPolicyState(
            assetId, profileId, repositoryId, 1, runId, policyId, 1L,
            config.configRevision(), PolicyDecision.ALLOW, "STALE_POLICY_WORKER", 0,
            null, null, now.plusSeconds(5), partialSourcePolicyState.version(),
            currentWaiverRevision));
    assertEquals(
        PolicyDecision.BLOCK_VULNERABILITY,
        afterStalePolicy.policyDecision(),
        "an old generation cannot overwrite repository-context policy state");

    SecurityScanDao.ScanPolicy replacementPolicy =
        inTransaction(() -> scans.createNextPolicyRevision(
            policyId,
            new SecurityScanDao.ScanPolicy(
                null, "contract-policy", true, Severity.CRITICAL, true, false, true,
                7200L, List.of("linux/amd64"), 1, "contract", now.plusSeconds(6),
                now.plusSeconds(6))).orElseThrow());
    assertEquals(
        1,
        scans.replaceRepositoryPolicy(
            policyId, replacementPolicy.id(), now.plusSeconds(6)));
    SecurityScanDao.RepositoryScanConfig reboundConfig =
        scans.findRepositoryConfig(repositoryId).orElseThrow();
    assertEquals(replacementPolicy.id(), reboundConfig.policyId());
    assertEquals(
        restoredSourceConfig.configRevision() + 1,
        reboundConfig.configRevision(),
        "switching policy revisions must invalidate materialized repository decisions");
    assertTrue(
        inTransaction(() -> scans.createNextPolicyRevision(
            policyId,
            new SecurityScanDao.ScanPolicy(
                null, "contract-policy", true, Severity.CRITICAL, true, false, true,
                7200L, List.of("linux/amd64"), 1, "contract", now.plusSeconds(7),
                now.plusSeconds(7)))).isEmpty(),
        "a stale policy revision must not publish another immutable head");

    SecurityScanDao.ScanWaiver globalWaiver =
        scans.createWaiver(new SecurityScanDao.ScanWaiver(
            null,
            "GLOBAL",
            null,
            null,
            null,
            "GHSA-fixture",
            null,
            Map.of(),
            "Installation-wide advisory exception",
            null,
            null,
            "contract",
            "contract",
            null,
            now.plusSeconds(7),
            now.plusSeconds(7)));
    assertTrue(scans.findAssetPolicyState(assetId, profileId, repositoryId).isPresent());
    assertEquals(
        0,
        scans.invalidatePolicyStatesForWaiver(globalWaiver),
        "global waiver changes must advance a watermark instead of deleting the state table");
    SecurityScanDao.WaiverRevision globalRevision = scans.waiverRevision();
    assertEquals(currentWaiverRevision + 1, globalRevision.currentRevision());
    assertEquals(globalRevision.currentRevision(), globalRevision.globalInvalidationRevision());
    assertTrue(scans.findAssetPolicyState(assetId, profileId, repositoryId).isPresent());
    assertEquals(
        globalRevision.globalInvalidationRevision(),
        scans.findDownloadPolicySnapshots(assetId, repositoryId)
            .getFirst()
            .requiredWaiverRevision());
  }

  @Test
  void securityScanningReconcilesWritesThatMissedAnAlreadyAdvancedEventCursor() {
    SecurityScanDao scans = stores().securityScanning();
    long repositoryId =
        createRepository("scan-rolling-upgrade-reconcile", RepositoryFormat.MAVEN2);
    long blobStoreId =
        stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-24T12:00:00Z");
    long firstBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "scan/reconcile-1.jar", "scan-reconcile-1"));
    String path = "com/acme/reconcile/1.0/reconcile-1.0.jar";
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null,
        repositoryId,
        null,
        firstBlobId,
        RepositoryFormat.MAVEN2,
        path,
        PersistenceHashes.pathHash(path),
        "reconcile-1.0.jar",
        "ARTIFACT",
        "application/java-archive",
        42L,
        null,
        now,
        Map.of()));
    assertEquals(1, scans.recordArtifactContentChange(assetId));
    assertTrue(scans.markCandidateEnqueued(assetId, 1));

    long recentBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "scan/reconcile-2.jar", "scan-reconcile-2"));
    assertEquals(
        1,
        stores().assets().updateAssetBlobBinding(
            assetId,
            recentBlobId,
            "application/java-archive",
            43,
            now.plusSeconds(1)));

    SecurityScanDao.ReconciliationPage recent = inTransaction(
        () -> scans.reconcileArtifactChanges(
            assetId, now, 10, 10));
    assertEquals(1, recent.recentAssets());
    assertEquals(0, recent.scannedAssets());
    assertEquals(1, recent.markedAssets());
    assertEquals(recentBlobId, scans.findCandidate(assetId).orElseThrow().assetBlobId());
    assertEquals(2, scans.findCandidate(assetId).orElseThrow().contentGeneration());
    assertTrue(scans.markCandidateEnqueued(assetId, 2));

    long oldTimestampBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "scan/reconcile-3.jar", "scan-reconcile-3"));
    assertEquals(
        1,
        stores().assets().updateAssetBlobBinding(
            assetId,
            oldTimestampBlobId,
            "application/java-archive",
            44,
            now.minusSeconds(1)));

    SecurityScanDao.ReconciliationPage wrap = inTransaction(
        () -> scans.reconcileArtifactChanges(
            assetId, now, 10, 10));
    assertEquals(0, wrap.markedAssets());
    assertTrue(wrap.wrapped());
    assertEquals(0, wrap.nextAssetId());
    SecurityScanDao.ReconciliationPage cyclic = inTransaction(
        () -> scans.reconcileArtifactChanges(
            wrap.nextAssetId(), now, 10, 10));
    assertEquals(1, cyclic.scannedAssets());
    assertEquals(1, cyclic.markedAssets());
    assertEquals(
        oldTimestampBlobId,
        scans.findCandidate(assetId).orElseThrow().assetBlobId());
    assertEquals(3, scans.findCandidate(assetId).orElseThrow().contentGeneration());
  }

  @Test
  void securityScanningKeepsNewestScannerSnapshotWhenRematchesCompleteOutOfOrder()
      throws Exception {
    SecurityScanDao scans = stores().securityScanning();
    long repositoryId =
        createRepository("scan-snapshot-publication-fence", RepositoryFormat.MAVEN2);
    long blobStoreId =
        stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-24T12:00:00Z");
    long artifactBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "scan/snapshot-fence.jar", "scan-snapshot-fence-artifact"));
    String path = "com/acme/snapshot-fence/1.0/snapshot-fence-1.0.jar";
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null,
        repositoryId,
        null,
        artifactBlobId,
        RepositoryFormat.MAVEN2,
        path,
        PersistenceHashes.pathHash(path),
        "snapshot-fence-1.0.jar",
        "ARTIFACT",
        "application/java-archive",
        42L,
        null,
        now,
        Map.of()));
    assertEquals(1, scans.recordArtifactContentChange(assetId));

    SecurityScanDao.ScanProfile profile = scans.createProfile(new SecurityScanDao.ScanProfile(
        null,
        "snapshot-publication-fence-profile",
        true,
        "syft",
        "grype",
        List.of("vuln"),
        Map.of(),
        1024 * 1024,
        1000,
        10 * 1024 * 1024,
        1024 * 1024,
        2,
        60,
        OciPlatformPolicy.REQUIRED_SET,
        List.of(),
        "1".repeat(64),
        1,
        now,
        now));
    SecurityScanDao.ScannerSnapshot newerSnapshot =
        scans.insertSnapshotOrFindExisting(new SecurityScanDao.ScannerSnapshot(
            null,
            "contract-adapter",
            "v1",
            "grype",
            "0.1.0",
            "newer-db",
            now.minusSeconds(60),
            "2".repeat(64),
            "4".repeat(64),
            now,
            true,
            Map.of()));
    SecurityScanDao.ScannerSnapshot olderSnapshot =
        scans.insertSnapshotOrFindExisting(new SecurityScanDao.ScannerSnapshot(
            null,
            "contract-adapter",
            "v1",
            "grype",
            "0.1.0",
            "older-db",
            now.minusSeconds(120),
            "2".repeat(64),
            "3".repeat(64),
            now.plusSeconds(1),
            true,
            Map.of()));
    assertTrue(
        olderSnapshot.id() > newerSnapshot.id(),
        "snapshot IDs intentionally run opposite to vulnerability database chronology");
    assertEquals(
        newerSnapshot.id(),
        scans.latestReadyScannerSnapshot(now.plusSeconds(5)).orElseThrow().id());
    long olderTaskId = scans.createTask(new SecurityScanDao.TaskDraft(
        repositoryId,
        assetId,
        SubjectKind.ASSET_BLOB,
        "sha256:" + "5".repeat(64),
        1,
        profile.id(),
        profile.revision(),
        olderSnapshot.id(),
        ScanStage.MATCH_ONLY,
        RequestReason.VULNERABILITY_DB_CHANGED,
        25,
        3,
        "contract",
        "older-snapshot-task",
        "older-snapshot-task",
        now));
    long newerTaskId = scans.createTask(new SecurityScanDao.TaskDraft(
        repositoryId,
        assetId,
        SubjectKind.ASSET_BLOB,
        "sha256:" + "5".repeat(64),
        1,
        profile.id(),
        profile.revision(),
        newerSnapshot.id(),
        ScanStage.MATCH_ONLY,
        RequestReason.VULNERABILITY_DB_CHANGED,
        25,
        3,
        "contract",
        "newer-snapshot-task",
        "newer-snapshot-task",
        now.plusSeconds(1)));
    assertTrue(newerTaskId > olderTaskId);

    scans.upsertAssetStateIfCurrent(new SecurityScanDao.AssetSecurityState(
        assetId,
        profile.id(),
        1,
        PersistenceHashes.sha256("sha256:" + "5".repeat(64)),
        null,
        ScanState.PENDING,
        ScanCompleteness.PARTIAL,
        false,
        Severity.UNKNOWN,
        Map.of(),
        null,
        null,
        PolicyDecision.BLOCK_PENDING,
        "NEWER_TASK_PENDING",
        null,
        now.plusSeconds(1),
        0));
    assertTrue(scans.cancelTask(olderTaskId, now.plusSeconds(1)));
    SecurityScanDao.AssetSecurityState afterOlderPendingCancel =
        scans.findAssetState(assetId, profile.id()).orElseThrow();
    assertEquals(ScanState.PENDING, afterOlderPendingCancel.scanState());
    assertEquals(PolicyDecision.BLOCK_PENDING, afterOlderPendingCancel.policyDecision());
    assertTrue(scans.requeueTask(olderTaskId, now.plusSeconds(1), "contract"));
    SecurityScanDao.AssetSecurityState afterOlderPendingRetry =
        scans.findAssetState(assetId, profile.id()).orElseThrow();
    assertEquals(ScanState.PENDING, afterOlderPendingRetry.scanState());
    assertEquals(PolicyDecision.BLOCK_PENDING, afterOlderPendingRetry.policyDecision());

    long sbomBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/snapshot-fence-sbom.json", "snapshot-fence-sbom"));
    SecurityScanDao.Sbom sbom = scans.insertSbomOrFindExisting(new SecurityScanDao.Sbom(
        null,
        SubjectKind.ASSET_BLOB,
        "sha256:" + "5".repeat(64),
        null,
        "syft",
        "1.0.0",
        "6".repeat(64),
        "7".repeat(64),
        sbomBlobId,
        "8".repeat(64),
        "CycloneDX",
        "1.6",
        1,
        0,
        true,
        now));
    long reportBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/snapshot-fence-report.json", "snapshot-fence-report"));
    SecurityScanDao.ScanRun olderRun =
        scans.insertRunOrFindExisting(new SecurityScanDao.ScanRun(
            null,
            olderTaskId,
            sbom.id(),
            olderSnapshot.id(),
            "9".repeat(64),
            "a".repeat(64),
            ScanState.COMPLETE,
            ScanCompleteness.COMPLETE,
            reportBlobId,
            "b".repeat(64),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            Severity.UNKNOWN,
            now,
            now.plusSeconds(2),
            now.plusSeconds(2)));
    SecurityScanDao.ScanRun newerRun =
        scans.insertRunOrFindExisting(new SecurityScanDao.ScanRun(
            null,
            newerTaskId,
            sbom.id(),
            newerSnapshot.id(),
            "9".repeat(64),
            "c".repeat(64),
            ScanState.COMPLETE,
            ScanCompleteness.COMPLETE,
            reportBlobId,
            "b".repeat(64),
            1,
            1,
            1,
            0,
            0,
            0,
            0,
            Severity.CRITICAL,
            now.plusSeconds(1),
            now.plusSeconds(3),
            now.plusSeconds(3)));

    SecurityScanDao.AssetSecurityState olderState =
        new SecurityScanDao.AssetSecurityState(
            assetId,
            profile.id(),
            1,
            PersistenceHashes.sha256("sha256:" + "5".repeat(64)),
            olderRun.id(),
            ScanState.COMPLETE,
            ScanCompleteness.COMPLETE,
            true,
            Severity.UNKNOWN,
            Map.of(),
            null,
            null,
            PolicyDecision.ALLOW,
            "OLDER_SNAPSHOT",
            null,
            now.plusSeconds(2),
            0);
    SecurityScanDao.AssetSecurityState newerState =
        new SecurityScanDao.AssetSecurityState(
            assetId,
            profile.id(),
            1,
            PersistenceHashes.sha256("sha256:" + "5".repeat(64)),
            newerRun.id(),
            ScanState.COMPLETE,
            ScanCompleteness.COMPLETE,
            true,
            Severity.CRITICAL,
            Map.of("critical", 1),
            null,
            null,
            PolicyDecision.BLOCK_VULNERABILITY,
            "NEWER_SNAPSHOT",
            null,
            now.plusSeconds(3),
            0);
    scans.upsertAssetStateIfCurrent(olderState);
    CyclicBarrier publicationStart = new CyclicBarrier(2);
    invokeConcurrently(List.of(
        () -> {
          publicationStart.await();
          return inTransaction(() -> scans.upsertAssetStateIfCurrent(olderState));
        },
        () -> {
          publicationStart.await();
          return inTransaction(() -> scans.upsertAssetStateIfCurrent(newerState));
        }), 2);
    SecurityScanDao.AssetSecurityState published =
        scans.findAssetState(assetId, profile.id()).orElseThrow();
    assertEquals(
        newerRun.id(),
        published.latestScanRunId(),
        "an older vulnerability snapshot must not replace a newer rematch result");
    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, published.policyDecision());

    long waiverRevision = scans.waiverRevision().currentRevision();
    SecurityScanDao.AssetPolicyState newerPolicyState =
        scans.upsertAssetPolicyStateIfCurrent(new SecurityScanDao.AssetPolicyState(
            assetId,
            profile.id(),
            repositoryId,
            1,
            newerRun.id(),
            null,
            null,
            1,
            PolicyDecision.BLOCK_VULNERABILITY,
            "NEWER_SNAPSHOT",
            0,
            null,
            null,
            now.plusSeconds(3),
            0,
            waiverRevision));
    SecurityScanDao.AssetPolicyState afterOlderPolicy =
        scans.upsertAssetPolicyStateIfCurrent(new SecurityScanDao.AssetPolicyState(
            assetId,
            profile.id(),
            repositoryId,
            1,
            olderRun.id(),
            null,
            null,
            1,
            PolicyDecision.ALLOW,
            "OLDER_SNAPSHOT",
            0,
            null,
            null,
            now.plusSeconds(4),
            newerPolicyState.version(),
            waiverRevision));
    assertEquals(newerRun.id(), afterOlderPolicy.latestScanRunId());
    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, afterOlderPolicy.policyDecision());

    assertTrue(scans.cancelTask(olderTaskId, now.plusSeconds(5)));
    SecurityScanDao.AssetSecurityState afterHistoricalCancel =
        scans.findAssetState(assetId, profile.id()).orElseThrow();
    assertEquals(newerRun.id(), afterHistoricalCancel.latestScanRunId());
    assertEquals(ScanState.COMPLETE, afterHistoricalCancel.scanState());
    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, afterHistoricalCancel.policyDecision());
    assertEquals(
        PolicyDecision.BLOCK_VULNERABILITY,
        scans.findAssetPolicyState(assetId, profile.id(), repositoryId)
            .orElseThrow()
            .policyDecision());

    assertTrue(scans.requeueTask(olderTaskId, now.plusSeconds(6), "contract"));
    SecurityScanDao.AssetSecurityState afterHistoricalRetry =
        scans.findAssetState(assetId, profile.id()).orElseThrow();
    assertEquals(newerRun.id(), afterHistoricalRetry.latestScanRunId());
    assertEquals(ScanState.COMPLETE, afterHistoricalRetry.scanState());
    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, afterHistoricalRetry.policyDecision());
    assertEquals(
        PolicyDecision.BLOCK_VULNERABILITY,
        scans.findAssetPolicyState(assetId, profile.id(), repositoryId)
            .orElseThrow()
            .policyDecision());

    Instant equalDatabaseEpoch = now.minusSeconds(30);
    SecurityScanDao.ScannerSnapshot equalEpochLower =
        scans.insertSnapshotOrFindExisting(new SecurityScanDao.ScannerSnapshot(
            null,
            "contract-adapter",
            "v1",
            "grype",
            "0.2.0",
            "equal-epoch-lower",
            equalDatabaseEpoch,
            "2".repeat(64),
            "5".repeat(64),
            now.plusSeconds(7),
            true,
            Map.of()));
    SecurityScanDao.ScannerSnapshot equalEpochHigher =
        scans.insertSnapshotOrFindExisting(new SecurityScanDao.ScannerSnapshot(
            null,
            "contract-adapter",
            "v1",
            "grype",
            "0.3.0",
            "equal-epoch-higher",
            equalDatabaseEpoch,
            "2".repeat(64),
            "6".repeat(64),
            now.plusSeconds(8),
            true,
            Map.of()));
    assertTrue(equalEpochHigher.id() > equalEpochLower.id());
    scans.insertSnapshotOrFindExisting(new SecurityScanDao.ScannerSnapshot(
        null,
        "contract-adapter",
        "v1",
        "grype",
        "0.2.0",
        "equal-epoch-lower",
        equalDatabaseEpoch,
        "2".repeat(64),
        "5".repeat(64),
        now.plusSeconds(9),
        true,
        Map.of()));
    assertEquals(
        equalEpochHigher.id(),
        scans.latestReadyScannerSnapshot(now.plusSeconds(10)).orElseThrow().id(),
        "a refreshed observation must not change equal-epoch authority");

    long equalEpochHigherTaskId = scans.createTask(new SecurityScanDao.TaskDraft(
        repositoryId,
        assetId,
        SubjectKind.ASSET_BLOB,
        "sha256:" + "5".repeat(64),
        1,
        profile.id(),
        profile.revision(),
        equalEpochHigher.id(),
        ScanStage.MATCH_ONLY,
        RequestReason.VULNERABILITY_DB_CHANGED,
        25,
        3,
        "contract",
        "equal-epoch-higher-task",
        "equal-epoch-higher-task",
        now.plusSeconds(10)));
    long equalEpochLowerTaskId = scans.createTask(new SecurityScanDao.TaskDraft(
        repositoryId,
        assetId,
        SubjectKind.ASSET_BLOB,
        "sha256:" + "5".repeat(64),
        1,
        profile.id(),
        profile.revision(),
        equalEpochLower.id(),
        ScanStage.MATCH_ONLY,
        RequestReason.VULNERABILITY_DB_CHANGED,
        25,
        3,
        "contract",
        "equal-epoch-lower-task",
        "equal-epoch-lower-task",
        now.plusSeconds(11)));
    assertTrue(
        equalEpochLowerTaskId > equalEpochHigherTaskId,
        "task chronology intentionally opposes the immutable snapshot tie-breaker");

    SecurityScanDao.ScanRun equalEpochHigherRun =
        scans.insertRunOrFindExisting(new SecurityScanDao.ScanRun(
            null,
            equalEpochHigherTaskId,
            sbom.id(),
            equalEpochHigher.id(),
            "9".repeat(64),
            "d".repeat(64),
            ScanState.COMPLETE,
            ScanCompleteness.COMPLETE,
            reportBlobId,
            "b".repeat(64),
            1,
            1,
            1,
            0,
            0,
            0,
            0,
            Severity.CRITICAL,
            now.plusSeconds(10),
            now.plusSeconds(12),
            now.plusSeconds(12)));
    SecurityScanDao.ScanRun equalEpochLowerRun =
        scans.insertRunOrFindExisting(new SecurityScanDao.ScanRun(
            null,
            equalEpochLowerTaskId,
            sbom.id(),
            equalEpochLower.id(),
            "9".repeat(64),
            "e".repeat(64),
            ScanState.COMPLETE,
            ScanCompleteness.COMPLETE,
            reportBlobId,
            "b".repeat(64),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            Severity.UNKNOWN,
            now.plusSeconds(11),
            now.plusSeconds(13),
            now.plusSeconds(13)));

    SecurityScanDao.AssetSecurityState equalEpochHigherState =
        scans.upsertAssetStateIfCurrent(new SecurityScanDao.AssetSecurityState(
            assetId,
            profile.id(),
            1,
            PersistenceHashes.sha256("sha256:" + "5".repeat(64)),
            equalEpochHigherRun.id(),
            ScanState.COMPLETE,
            ScanCompleteness.COMPLETE,
            true,
            Severity.CRITICAL,
            Map.of("critical", 1),
            null,
            null,
            PolicyDecision.BLOCK_VULNERABILITY,
            "EQUAL_EPOCH_HIGHER",
            null,
            now.plusSeconds(12),
            afterHistoricalRetry.version()));
    assertTrue(
        inTransaction(() -> scans.taskProjectionIsSuperseded(equalEpochLowerTaskId)),
        "the immutable snapshot order must supersede a later task for the lower snapshot");
    SecurityScanDao.AssetSecurityState afterEqualEpochLower =
        scans.upsertAssetStateIfCurrent(new SecurityScanDao.AssetSecurityState(
            assetId,
            profile.id(),
            1,
            PersistenceHashes.sha256("sha256:" + "5".repeat(64)),
            equalEpochLowerRun.id(),
            ScanState.COMPLETE,
            ScanCompleteness.COMPLETE,
            true,
            Severity.UNKNOWN,
            Map.of(),
            null,
            null,
            PolicyDecision.ALLOW,
            "EQUAL_EPOCH_LOWER",
            null,
            now.plusSeconds(13),
            equalEpochHigherState.version()));
    assertEquals(equalEpochHigherRun.id(), afterEqualEpochLower.latestScanRunId());
    assertEquals(
        PolicyDecision.BLOCK_VULNERABILITY,
        afterEqualEpochLower.policyDecision());

    SecurityScanDao.AssetPolicyState equalEpochHigherPolicy =
        scans.upsertAssetPolicyStateIfCurrent(new SecurityScanDao.AssetPolicyState(
            assetId,
            profile.id(),
            repositoryId,
            1,
            equalEpochHigherRun.id(),
            null,
            null,
            1,
            PolicyDecision.BLOCK_VULNERABILITY,
            "EQUAL_EPOCH_HIGHER",
            0,
            null,
            null,
            now.plusSeconds(12),
            afterOlderPolicy.version(),
            waiverRevision));
    SecurityScanDao.AssetPolicyState afterEqualEpochLowerPolicy =
        scans.upsertAssetPolicyStateIfCurrent(new SecurityScanDao.AssetPolicyState(
            assetId,
            profile.id(),
            repositoryId,
            1,
            equalEpochLowerRun.id(),
            null,
            null,
            1,
            PolicyDecision.ALLOW,
            "EQUAL_EPOCH_LOWER",
            0,
            null,
            null,
            now.plusSeconds(13),
            equalEpochHigherPolicy.version(),
            waiverRevision));
    assertEquals(
        equalEpochHigherRun.id(),
        afterEqualEpochLowerPolicy.latestScanRunId());
    assertEquals(
        PolicyDecision.BLOCK_VULNERABILITY,
        afterEqualEpochLowerPolicy.policyDecision());
  }

  @Test
  void securityScanningRequeuesAFirstObservationFailureExactlyOnce() {
    SecurityScanDao scans = stores().securityScanning();
    long repositoryId =
        createRepository("scan-observation-recovery", RepositoryFormat.MAVEN2);
    long blobStoreId =
        stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-24T12:00:00Z");
    long artifactBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "scan/observation-recovery.jar", "scan-observation-recovery"));
    String path = "com/acme/recovery/1.0/recovery-1.0.jar";
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null,
        repositoryId,
        null,
        artifactBlobId,
        RepositoryFormat.MAVEN2,
        path,
        PersistenceHashes.pathHash(path),
        "recovery-1.0.jar",
        "ARTIFACT",
        "application/java-archive",
        42L,
        null,
        now,
        Map.of()));
    assertEquals(1, scans.recordArtifactContentChange(assetId));
    assertTrue(scans.markCandidateEnqueued(assetId, 1));

    SecurityScanDao.ScanProfile profile = scans.createProfile(new SecurityScanDao.ScanProfile(
        null,
        "observation-recovery-profile",
        true,
        "syft",
        "grype",
        List.of("vuln"),
        Map.of(),
        1024 * 1024,
        1000,
        10 * 1024 * 1024,
        1024 * 1024,
        2,
        60,
        OciPlatformPolicy.REQUIRED_SET,
        List.of(),
        "8".repeat(64),
        1,
        now,
        now));
    SecurityScanDao.RepositoryScanConfig config =
        scans.upsertRepositoryConfig(new SecurityScanDao.RepositoryScanConfig(
            repositoryId,
            true,
            profile.id(),
            true,
            true,
            EnforcementMode.AUDIT,
            PolicyAction.ALLOW,
            PolicyAction.ALLOW,
            PolicyAction.ALLOW,
            null,
            null,
            1,
            now,
            now));
    scans.upsertAssetStateIfCurrent(new SecurityScanDao.AssetSecurityState(
        assetId,
        profile.id(),
        1,
        PersistenceHashes.sha256("sha256:" + "a".repeat(64)),
        null,
        ScanState.FAILED,
        ScanCompleteness.UNKNOWN,
        false,
        Severity.UNKNOWN,
        Map.of(),
        null,
        null,
        PolicyDecision.ALLOW,
        SCANNER_OBSERVATION_UNAVAILABLE,
        null,
        now,
        0));
    scans.upsertAssetPolicyStateIfCurrent(new SecurityScanDao.AssetPolicyState(
        assetId,
        profile.id(),
        repositoryId,
        1,
        null,
        null,
        null,
        config.configRevision(),
        PolicyDecision.ALLOW,
        SCANNER_OBSERVATION_UNAVAILABLE,
        0,
        null,
        null,
        now,
        0,
        scans.waiverRevision().currentRevision()));

    assertEquals(
        assetId,
        scans.listAssetStatesNeedingSnapshot(profile.id(), 99L, 0, 10)
            .getFirst()
            .assetId());
    assertTrue(scans.requeueCandidateAfterObservationFailure(
        assetId, profile.id(), 1, now.plusSeconds(1)));
    assertEquals(
        assetId,
        inTransaction(() -> scans.claimCandidates(10)).getFirst().assetId());
    assertFalse(scans.requeueCandidateAfterObservationFailure(
        assetId, profile.id(), 1, now.plusSeconds(2)));
  }

  @Test
  void securityScanningRecoversFinalAttemptsAndCleansUnreferencedHistory() {
    SecurityScanDao scans = stores().securityScanning();
    long repositoryId =
        createRepository("scan-lifecycle", RepositoryFormat.MAVEN2);
    long blobStoreId =
        stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    Instant fixtureTime = Instant.parse("2026-07-24T12:00:00Z");
    long artifactBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "scan/lifecycle.jar", "scan-lifecycle-artifact"));
    String path = "com/acme/lifecycle/1.0/lifecycle-1.0.jar";
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null,
        repositoryId,
        null,
        artifactBlobId,
        RepositoryFormat.MAVEN2,
        path,
        PersistenceHashes.pathHash(path),
        "lifecycle-1.0.jar",
        "ARTIFACT",
        "application/java-archive",
        42L,
        null,
        fixtureTime,
        Map.of()));

    ArtifactChangeDao.ArtifactChange change =
        stores().artifactChanges().listAfter(0, 10).getFirst();
    advanceCursor("artifact_change:security_scan", change.id());
    advanceCursor("artifact_change:contract", change.id());
    assertEquals(
        change.id(),
        stores()
            .maintenanceCursors()
            .minimumLastSeenId("artifact_change:")
            .orElseThrow());
    ArtifactChangeDao.EventRange retainedRange =
        stores().artifactChanges().retainedRange().orElseThrow();
    assertEquals(change.id(), retainedRange.oldestId());
    assertEquals(change.id(), retainedRange.newestId());
    assertEquals(1, retainedRange.estimatedCount());
    assertEquals(1, stores().artifactChanges().deleteThrough(change.id(), 1));
    assertTrue(stores().artifactChanges().retainedRange().isEmpty());

    SecurityScanDao.ScanProfile profile = scans.createProfile(
        new SecurityScanDao.ScanProfile(
            null,
            "lifecycle-profile",
            true,
            "syft",
            "grype",
            List.of("vuln"),
            Map.of(),
            1024 * 1024,
            1000,
            10 * 1024 * 1024,
            1024 * 1024,
            2,
            60,
            OciPlatformPolicy.REQUIRED_SET,
            List.of("linux/amd64"),
            "7".repeat(64),
            1,
            fixtureTime,
            fixtureTime));
    SecurityScanDao.ScannerSnapshot snapshot =
        scans.insertSnapshotOrFindExisting(new SecurityScanDao.ScannerSnapshot(
            null,
            "contract-adapter",
            "v1",
            "grype",
            "0.1.0",
            "lifecycle-db",
            fixtureTime.minusSeconds(60),
            "8".repeat(64),
            "9".repeat(64),
            fixtureTime,
            true,
            Map.of()));
    long taskId = scans.createTask(new SecurityScanDao.TaskDraft(
        repositoryId,
        assetId,
        SubjectKind.ASSET_BLOB,
        "sha256:" + "6".repeat(64),
        1,
        profile.id(),
        profile.revision(),
        snapshot.id(),
        ScanStage.CATALOG_AND_MATCH,
        RequestReason.MANUAL,
        0,
        1,
        "contract",
        null,
        null,
        fixtureTime));
    SecurityScanDao.ScanTask finalAttempt = inTransaction(
        () -> scans.claimTasks(
                "replica-a",
                fixtureTime,
                fixtureTime.plusSeconds(30),
                1)
            .getFirst());
    assertEquals(1, finalAttempt.attempts());
    assertTrue(inTransaction(
            () -> scans.claimExpiredExhaustedTasks(
                "replica-b",
                fixtureTime.plusSeconds(29),
                fixtureTime.plusSeconds(59),
                1))
        .isEmpty());
    SecurityScanDao.ScanTask recovered = inTransaction(
        () -> scans.claimExpiredExhaustedTasks(
                "replica-b",
                fixtureTime.plusSeconds(31),
                fixtureTime.plusSeconds(61),
                1)
            .getFirst());
    assertEquals(1, recovered.attempts());
    assertNotEquals(finalAttempt.leaseToken(), recovered.leaseToken());
    assertTrue(scans.failTask(
        taskId,
        recovered.leaseToken(),
        "SCAN_ATTEMPTS_EXHAUSTED",
        "final lease expired",
        fixtureTime.plusSeconds(32)));

    SecurityScanDao.BackfillJob backfill =
        scans.createBackfillJob(repositoryId, "contract", fixtureTime);
    SecurityScanDao.BackfillJob claimedBackfill = inTransaction(
        () -> scans.claimBackfillJobs(
                "replica-a",
                fixtureTime,
                fixtureTime.plusSeconds(30),
                1)
            .getFirst());
    assertEquals(backfill.id(), claimedBackfill.id());
    assertEquals(1, claimedBackfill.attempts());
    assertTrue(scans.requeueBackfill(
        claimedBackfill.id(),
        claimedBackfill.leaseToken(),
        assetId,
        1,
        1,
        "deadlock",
        fixtureTime.plusSeconds(10),
        fixtureTime.plusSeconds(1)));
    assertTrue(inTransaction(
            () -> scans.claimBackfillJobs(
                "replica-b",
                fixtureTime.plusSeconds(9),
                fixtureTime.plusSeconds(39),
                1))
        .isEmpty());
    SecurityScanDao.BackfillJob retriedBackfill = inTransaction(
        () -> scans.claimBackfillJobs(
                "replica-b",
                fixtureTime.plusSeconds(10),
                fixtureTime.plusSeconds(40),
                1)
            .getFirst());
    assertEquals(2, retriedBackfill.attempts());
    assertEquals(assetId, retriedBackfill.cursorAssetId());
    assertTrue(scans.updateBackfillProgress(
        retriedBackfill.id(),
        retriedBackfill.leaseToken(),
        assetId,
        1,
        1,
        com.github.klboke.kkrepo.security.scan.ScanEnums.BackfillStatus.SUCCEEDED,
        null,
        null,
        fixtureTime.plusSeconds(11)));

    long sbomBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/lifecycle-sbom.json", "lifecycle-sbom"));
    SecurityScanDao.Sbom sbom = scans.insertSbomOrFindExisting(
        new SecurityScanDao.Sbom(
            null,
            SubjectKind.ASSET_BLOB,
            "sha256:" + "6".repeat(64),
            null,
            "syft",
            "1.0.0",
            "a".repeat(64),
            "b".repeat(64),
            sbomBlobId,
            "c".repeat(64),
            "CycloneDX",
            "1.6",
            1,
            0,
            true,
            fixtureTime));
    long reportBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "security/lifecycle-report.json", "lifecycle-report"));
    SecurityScanDao.ScanRun run = scans.insertRunOrFindExisting(
        new SecurityScanDao.ScanRun(
            null,
            taskId,
            sbom.id(),
            snapshot.id(),
            "d".repeat(64),
            "e".repeat(64),
            ScanState.COMPLETE,
            ScanCompleteness.COMPLETE,
            reportBlobId,
            "f".repeat(64),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            Severity.UNKNOWN,
            fixtureTime,
            fixtureTime.plusSeconds(1),
            fixtureTime.plusSeconds(1)));
    assertTrue(stores().blobReferences().isReferenced(sbomBlobId));
    assertTrue(stores().blobReferences().isReferenced(reportBlobId));

    Instant cutoff = Instant.now().plusSeconds(60);
    SecurityScanDao.RetentionResult retained =
        scans.cleanupRetainedData(cutoff, cutoff, 50);

    assertEquals(1, retained.taskCount());
    assertEquals(1, retained.backfillJobCount());
    assertEquals(0, retained.runSubjectCount());
    assertEquals(1, retained.runCount());
    assertEquals(1, retained.sbomCount());
    assertEquals(1, retained.scannerSnapshotCount());
    assertEquals(5, retained.total());
    assertTrue(scans.findRun(run.id()).isEmpty());
    assertTrue(scans.findSbom(sbom.id()).isEmpty());
    assertTrue(scans.findScannerSnapshot(snapshot.id()).isEmpty());
    assertFalse(stores().blobReferences().isReferenced(sbomBlobId));
    assertFalse(stores().blobReferences().isReferenced(reportBlobId));
  }

  @Test
  void securityBackfillWaitsForLockedAssetsInsteadOfSkippingTheirCursor() throws Exception {
    long repositoryId = createRepository("scan-backfill-lock", RepositoryFormat.MAVEN2);
    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-24T14:00:00Z");
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "scan/locked-app.jar", "scan-locked-artifact"));
    String path = "locked/app.jar";
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null,
        repositoryId,
        null,
        blobId,
        RepositoryFormat.MAVEN2,
        path,
        PersistenceHashes.pathHash(path),
        "app.jar",
        "ARTIFACT",
        "application/java-archive",
        42L,
        null,
        now,
        Map.of()));
    CountDownLatch rowLocked = new CountDownLatch(1);
    CountDownLatch releaseWriter = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var writer = executor.submit(() -> inTransaction(() -> {
        assertEquals(
            assetId,
            stores().assets().claimStaleAssetsByPrefix(
                repositoryId, "locked/", now.plusSeconds(1), 1).getFirst().id());
        rowLocked.countDown();
        await(releaseWriter);
        return null;
      }));
      assertTrue(rowLocked.await(30, java.util.concurrent.TimeUnit.SECONDS));

      var backfill = executor.submit(() -> inTransaction(() ->
          stores().securityScanning().markRepositoryAssetsForBackfill(repositoryId, 0, 100)));

      assertThrows(
          java.util.concurrent.TimeoutException.class,
          () -> backfill.get(250, java.util.concurrent.TimeUnit.MILLISECONDS),
          "a cursor page must wait for a locked asset rather than skip and complete past it");
      releaseWriter.countDown();
      writer.get(30, java.util.concurrent.TimeUnit.SECONDS);
      SecurityScanDao.BackfillPage page =
          backfill.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(1, page.scannedAssets());
      assertEquals(assetId, page.nextAssetId());
      assertTrue(page.complete());
      assertEquals(
          assetId,
          stores().securityScanning().findCandidate(assetId).orElseThrow().assetId());
    } finally {
      releaseWriter.countDown();
    }
  }

  @Test
  void securityArtifactChangeFoldDefersInsteadOfBlockingForegroundAssetLocks()
      throws Exception {
    long repositoryId = createRepository("scan-event-lock", RepositoryFormat.MAVEN2);
    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-24T14:30:00Z");
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "scan/event-lock.jar", "scan-event-lock-artifact"));
    String path = "locked/event-lock.jar";
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null,
        repositoryId,
        null,
        blobId,
        RepositoryFormat.MAVEN2,
        path,
        PersistenceHashes.pathHash(path),
        "event-lock.jar",
        "ARTIFACT",
        "application/java-archive",
        42L,
        null,
        now,
        Map.of()));
    CountDownLatch rowLocked = new CountDownLatch(1);
    CountDownLatch releaseWriter = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var writer = executor.submit(() -> inTransaction(() -> {
        assertEquals(
            assetId,
            stores().assets().claimStaleAssetsByPrefix(
                repositoryId, "locked/", now.plusSeconds(1), 1).getFirst().id());
        rowLocked.countDown();
        await(releaseWriter);
        return null;
      }));
      assertTrue(rowLocked.await(30, java.util.concurrent.TimeUnit.SECONDS));

      var fold = executor.submit(() ->
          inTransaction(() -> stores().securityScanning().recordArtifactContentChange(assetId)));
      ExecutionException deferred = assertThrows(
          ExecutionException.class,
          () -> fold.get(5, java.util.concurrent.TimeUnit.SECONDS),
          "artifact-change folding must defer rather than wait on a foreground asset lock");
      assertTrue(
          deferred.getCause() instanceof CannotAcquireLockException,
          () -> "unexpected contention failure: " + deferred.getCause());

      releaseWriter.countDown();
      writer.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          1,
          inTransaction(
              () -> stores().securityScanning().recordArtifactContentChange(assetId)));
    } finally {
      releaseWriter.countDown();
    }
  }

  @Test
  void ansibleGalaxyRegistryStateIsImmutableFencedAndSharedAcrossReplicas() {
    long repositoryId = createRepository("ansible-hosted", RepositoryFormat.ANSIBLEGALAXY);
    long proxyRepositoryId = createRepository(
        "ansible-proxy", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.PROXY);
    long groupRepositoryId = createRepository(
        "ansible-group", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.GROUP);
    stores().repositories().addMember(groupRepositoryId, repositoryId, 0);
    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-21T08:00:00Z");

    long componentId = stores().components().upsertReturningId(component(
        repositoryId, RepositoryFormat.ANSIBLEGALAXY, "acme", "tools", "1.2.3",
        Map.of("kind", "ansible-collection"), now));
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "ansible/acme-tools-1.2.3.tar.gz", "ansible-artifact-ref"));
    String path = "api/v3/plugin/ansible/content/published/collections/artifacts/"
        + "acme-tools-1.2.3.tar.gz";
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, componentId, blobId, RepositoryFormat.ANSIBLEGALAXY, path,
        PersistenceHashes.pathHash(path), "acme-tools-1.2.3.tar.gz", "collection-artifact",
        "application/octet-stream", 42L, null, now, Map.of()));

    AnsibleGalaxyRegistryDao registry = stores().ansibleGalaxyRegistry();
    AnsibleGalaxyRegistryDao.CollectionVersion fixture =
        new AnsibleGalaxyRegistryDao.CollectionVersion(
            null, repositoryId, componentId, assetId, "acme", "acme", "tools", "tools",
            "1.2.3", "1.2.3", "acme-tools-1.2.3.tar.gz", "a".repeat(64), 42L,
            Map.of("authors", List.of("kkrepo")),
            Map.of("community.general", ">=8.0.0,<9.0.0"), ">=2.16", "HOSTED",
            "11111111-1111-1111-1111-111111111111", 0L,
            AnsibleGalaxyRegistryDao.VERSION_READY, now, now, now);
    AnsibleGalaxyRegistryDao.CollectionVersion stored =
        inTransaction(() -> registry.insertVersion(fixture));
    long revision = stored.revision();

    assertNotNull(stored.id());
    assertTrue(revision > 0L);
    assertEquals("11111111-1111-1111-1111-111111111111", stored.importTaskId());
    assertEquals("1.2.3", registry.findVersion(
        repositoryId, "acme", "tools", "1.2.3").orElseThrow().versionOriginal());
    assertEquals(List.of("1.2.3"), registry.listVersions(repositoryId, "acme", "tools")
        .stream().map(AnsibleGalaxyRegistryDao.CollectionVersion::versionNormalized).toList());
    assertEquals(
        List.of("1.2.3"), registry.listVersionNames(repositoryId, "acme", "tools"));
    assertEquals(stored.artifactAssetId(),
        registry.findArtifactByVersionId(stored.id()).orElseThrow().artifactAssetId());
    assertEquals(stored.id(), registry.findArtifactByFilename(
        repositoryId, stored.artifactFilename()).orElseThrow().versionId());
    assertEquals(revision,
        registry.currentRepositoryRevisions(List.of(repositoryId, proxyRepositoryId))
            .get(repositoryId));
    long revisionBeforeDuplicate = registry.currentRepositoryRevision(repositoryId);
    assertThrows(RuntimeException.class,
        () -> inTransaction(() -> registry.insertVersion(fixture)));
    assertEquals(revisionBeforeDuplicate, registry.currentRepositoryRevision(repositoryId),
        "a rejected immutable insert must roll back its speculative revision");

    AnsibleGalaxyRegistryDao.Signature signature = registry.insertSignature(
        new AnsibleGalaxyRegistryDao.Signature(
            null, stored.id(), null, "b".repeat(64), "fingerprint", "HOSTED", now));
    assertNotNull(signature.id());
    assertEquals("fingerprint", registry.listSignatures(stored.id()).getFirst().keyFingerprint());

    String taskId = "11111111-2222-3333-4444-555555555555";
    registry.createTask(new AnsibleGalaxyRegistryDao.ImportTask(
        taskId, repositoryId, "alice", AnsibleGalaxyRegistryDao.TASK_WAITING, List.of(),
        null, null, "acme", "tools", "1.2.4", "acme-tools-1.2.4.tar.gz",
        "c".repeat(64), null, assetId, 0, null, null, 0L, now, null, null, now));
    Instant leaseNow = Instant.now();
    AnsibleGalaxyRegistryDao.ImportTask claimed = registry.claimTasks(
        "replica-a", leaseNow.plusSeconds(30), leaseNow, 10).getFirst();
    assertEquals(1, claimed.attemptCount());
    assertTrue(registry.claimTask(
        taskId, "replica-b", leaseNow.plusSeconds(30), leaseNow).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> registry.renewTaskLease(
        taskId, "replica-a", claimed.fencingToken(), Instant.EPOCH));
    Instant renewedUntil = Instant.now().plusSeconds(60);
    assertFalse(registry.renewTaskLease(
        taskId, "replica-a", claimed.fencingToken() + 1, renewedUntil));
    assertTrue(registry.renewTaskLease(
        taskId, "replica-a", claimed.fencingToken(), renewedUntil));
    assertFalse(registry.finishTask(
        taskId, "replica-a", claimed.fencingToken() + 1,
        AnsibleGalaxyRegistryDao.TASK_COMPLETED, List.of(), null, null,
        "acme", "tools", "1.2.4", "acme-tools-1.2.4.tar.gz", "c".repeat(64),
        now.plusSeconds(2)));
    assertTrue(registry.finishTask(
        taskId, "replica-a", claimed.fencingToken(),
        AnsibleGalaxyRegistryDao.TASK_COMPLETED,
        List.of(Map.of("level", "INFO", "message", "imported")), null, null,
        "acme", "tools", "1.2.4", "acme-tools-1.2.4.tar.gz", "c".repeat(64),
        now.plusSeconds(2)));
    assertEquals(AnsibleGalaxyRegistryDao.TASK_COMPLETED,
        registry.findTask(taskId).orElseThrow().state());

    String requestTaskId = "22222222-3333-4444-5555-666666666666";
    Instant requestClaimedAt = now.plusSeconds(10);
    AnsibleGalaxyRegistryDao.ImportTask requestClaimed = registry.createClaimedTask(
        new AnsibleGalaxyRegistryDao.ImportTask(
            requestTaskId, repositoryId, "bob", AnsibleGalaxyRegistryDao.TASK_WAITING,
            List.of(), null, null, "acme", "tools", "1.2.5",
            "acme-tools-1.2.5.tar.gz", "d".repeat(64), null, assetId, 0,
            null, null, 0L, requestClaimedAt, null, null, requestClaimedAt),
        "request-replica", requestClaimedAt.plusSeconds(30), requestClaimedAt);
    assertEquals(AnsibleGalaxyRegistryDao.TASK_RUNNING, requestClaimed.state());
    assertEquals("request-replica", requestClaimed.leaseOwner());
    assertEquals(1, requestClaimed.attemptCount());
    assertEquals(1L, requestClaimed.fencingToken());
    assertEquals(requestClaimedAt, requestClaimed.startedAt());
    assertFalse(registry.listClaimableTasks(requestClaimedAt.plusSeconds(1), 10).stream()
        .anyMatch(task -> requestTaskId.equals(task.taskId())));
    assertTrue(registry.claimTask(
        requestTaskId, "recovery-replica", requestClaimedAt.plusSeconds(40),
        requestClaimedAt.plusSeconds(1)).isEmpty());
    AnsibleGalaxyRegistryDao.ImportTask recovered = registry.claimTask(
        requestTaskId, "recovery-replica", requestClaimedAt.plusSeconds(70),
        requestClaimedAt.plusSeconds(31)).orElseThrow();
    assertEquals(2, recovered.attemptCount());
    assertEquals(2L, recovered.fencingToken());

    long firstProxyStateRevision = registry.upsertProxyState(
        new AnsibleGalaxyRegistryDao.ProxyVersionState(
            proxyRepositoryId, "acme", "tools", "1.2.3", "acme-tools-1.2.3.tar.gz",
            "https://galaxy.example/v3/collections/acme/tools/versions/1.2.3/",
            "https://cdn.example/acme-tools-1.2.3.tar.gz", "a".repeat(64), "etag-one",
            now.toString(), now.plusSeconds(60), now, null, null,
            Map.of("version", "1.2.3"), 0L, now));
    long secondProxyStateRevision = registry.upsertProxyState(
        new AnsibleGalaxyRegistryDao.ProxyVersionState(
            proxyRepositoryId, "acme", "tools", "1.2.3", "acme-tools-1.2.3.tar.gz",
            "https://galaxy.example/v3/collections/acme/tools/versions/1.2.3/",
            "https://cdn.example/acme-tools-1.2.3.tar.gz", "a".repeat(64), "etag-two",
            now.toString(), now.plusSeconds(120), now.plusSeconds(1), null, null,
            Map.of("version", "1.2.3", "signatures", List.of()), 0L, now.plusSeconds(1)));
    assertTrue(secondProxyStateRevision > firstProxyStateRevision);
    assertEquals("etag-two", registry.findProxyState(
        proxyRepositoryId, "acme", "tools", "1.2.3").orElseThrow().metadataEtag());
    assertEquals(secondProxyStateRevision, registry.findProxyState(
        proxyRepositoryId, "acme", "tools", "1.2.3").orElseThrow().revision());
    Instant revalidatedAt = now.plusSeconds(2);
    assertTrue(registry.touchProxyState(
        proxyRepositoryId, "acme", "tools", "1.2.3", "etag-three", null,
        now.plusSeconds(180), revalidatedAt));
    assertEquals("etag-three", registry.findProxyState(
        proxyRepositoryId, "acme", "tools", "1.2.3").orElseThrow().metadataEtag());
    assertEquals("1.2.3", registry.findProxyStateByArtifactFilename(
        proxyRepositoryId, "acme-tools-1.2.3.tar.gz").orElseThrow().versionNormalized());

    long inventoryRevision = registry.replaceProxyInventory(
        new AnsibleGalaxyRegistryDao.ProxyInventory(
            proxyRepositoryId, "acme", "tools", now.plusSeconds(60), now, 0L, 2, now),
        List.of("1.2.3", "2.0.0", "2.0.0"));
    assertEquals(2, registry.findProxyInventory(
        proxyRepositoryId, "acme", "tools").orElseThrow().versionCount());
    assertEquals(inventoryRevision, registry.findProxyInventory(
        proxyRepositoryId, "acme", "tools").orElseThrow().revision());
    assertEquals(List.of("1.2.3", "2.0.0"), registry.listProxyInventoryVersionNames(
        proxyRepositoryId, "acme", "tools"));
    assertTrue(registry.touchProxyInventory(
        proxyRepositoryId, "acme", "tools", now.plusSeconds(120), now.plusSeconds(1)));
    assertEquals(
        Map.of(proxyRepositoryId, now.plusSeconds(120)),
        registry.currentProxyInventoryCacheUntil(
            List.of(repositoryId, proxyRepositoryId), "acme", "tools"));
    assertTrue(registry.currentProxyInventoryCacheUntil(
        List.of(), "acme", "tools").isEmpty());

    long groupRevision = registry.nextGroupConfigRevision(groupRepositoryId);
    assertTrue(groupRevision > 0L);
    assertEquals(groupRevision, registry.currentGroupConfigRevision(groupRepositoryId));
    assertEquals(groupRevision,
        registry.currentGroupConfigRevisions(List.of(groupRepositoryId)).get(groupRepositoryId));
    assertTrue(registry.bindGroupSourceIfCurrent(new AnsibleGalaxyRegistryDao.GroupBinding(
        groupRepositoryId, "acme", "tools", "1.2.3", repositoryId, stored.id(),
        stored.artifactFilename(), revision, groupRevision, stored.artifactSha256(), now, now)));
    assertEquals(repositoryId, registry.findGroupBinding(
        groupRepositoryId, "acme", "tools", "1.2.3").orElseThrow().memberRepositoryId());
    assertEquals(stored.id(), registry.findGroupBindingByArtifactFilename(
        groupRepositoryId, stored.artifactFilename()).orElseThrow().memberVersionId());
    assertEquals(stored.artifactSha256(), registry.currentCoordinateSha256(
        groupRepositoryId, "acme", "tools", "1.2.3").orElseThrow());

    AnsibleGalaxyRegistryDao.Lease firstLease = registry.tryAcquireLease(
        "ansible:" + repositoryId + ":artifact:acme:tools:1.2.3",
        "replica-a", Instant.now().plusSeconds(30)).orElseThrow();
    assertTrue(registry.tryAcquireLease(
        firstLease.leaseKey(), "replica-b", Instant.now().plusSeconds(30)).isEmpty());
    assertTrue(registry.renewLease(
        firstLease.leaseKey(), firstLease.owner(), firstLease.fencingToken(),
        Instant.now().plusSeconds(60)));
    registry.releaseLease(
        firstLease.leaseKey(), firstLease.owner(), firstLease.fencingToken());
    AnsibleGalaxyRegistryDao.Lease secondLease = registry.tryAcquireLease(
        firstLease.leaseKey(), "replica-b", Instant.now().plusSeconds(30)).orElseThrow();
    assertTrue(secondLease.fencingToken() > firstLease.fencingToken());

    long repositoryRevisionBeforeDelete = registry.currentRepositoryRevision(repositoryId);
    long groupRevisionBeforeDelete = registry.currentRepositoryRevision(groupRepositoryId);
    assertFalse(inTransaction(() -> registry.deleteVersion(
        repositoryId, stored.id(), assetId + 1)));
    assertTrue(registry.findVersionById(stored.id()).isPresent());
    assertTrue(inTransaction(() -> registry.deleteVersion(
        repositoryId, stored.id(), assetId)));
    assertTrue(registry.findVersionById(stored.id()).isEmpty());
    assertTrue(registry.listSignatures(stored.id()).isEmpty());
    assertTrue(registry.findGroupBinding(
        groupRepositoryId, "acme", "tools", "1.2.3").isEmpty());
    assertTrue(registry.currentRepositoryRevision(repositoryId) > repositoryRevisionBeforeDelete);
    assertTrue(registry.currentRepositoryRevision(groupRepositoryId) > groupRevisionBeforeDelete);
    assertEquals(1, stores().assets().deleteAssetById(assetId));

    long proxyBlobStoreId = stores().repositories()
        .findById(proxyRepositoryId).orElseThrow().blobStoreId();
    long metadataGroupRevision = registry.currentGroupConfigRevision(groupRepositoryId);
    long proxyMetadataRevision = registry.currentRepositoryRevision(proxyRepositoryId);
    assertTrue(registry.bindGroupSourceIfCurrent(new AnsibleGalaxyRegistryDao.GroupBinding(
        groupRepositoryId, "acme", "tools", "1.2.3", proxyRepositoryId, null,
        "acme-tools-1.2.3.tar.gz", proxyMetadataRevision, metadataGroupRevision,
        "a".repeat(64), now, now)));
    AnsibleGalaxyRegistryDao.GroupBinding metadataBinding = registry.findGroupBindingByArtifactFilename(
        groupRepositoryId, "acme-tools-1.2.3.tar.gz").orElseThrow();
    assertNull(metadataBinding.memberVersionId());
    assertEquals(proxyRepositoryId, metadataBinding.memberRepositoryId());

    long proxyComponentId = stores().components().upsertReturningId(component(
        proxyRepositoryId, RepositoryFormat.ANSIBLEGALAXY, "acme", "tools", "1.2.3",
        Map.of("kind", "ansible-collection"), now));
    long proxyBlobId = stores().assets().insertBlob(
        blob(proxyBlobStoreId, "ansible/proxy-acme-tools-1.2.3.tar.gz", "ansible-proxy-ref"));
    long proxyAssetId = stores().assets().insertAsset(new AssetRecord(
        null, proxyRepositoryId, proxyComponentId, proxyBlobId,
        RepositoryFormat.ANSIBLEGALAXY, path, PersistenceHashes.pathHash(path),
        "acme-tools-1.2.3.tar.gz", "collection-artifact", "application/octet-stream",
        42L, null, now, Map.of()));
    AnsibleGalaxyRegistryDao.CollectionVersion proxyVersion = inTransaction(() ->
        registry.insertVersion(new AnsibleGalaxyRegistryDao.CollectionVersion(
            null, proxyRepositoryId, proxyComponentId, proxyAssetId,
            "acme", "acme", "tools", "tools", "1.2.3", "1.2.3",
            "acme-tools-1.2.3.tar.gz", "a".repeat(64), 42L, Map.of(), Map.of(),
            ">=2.16", "PROXY", 0L, AnsibleGalaxyRegistryDao.VERSION_READY,
            now, now, now)));
    long proxyRevision = proxyVersion.revision();
    assertTrue(registry.bindGroupSourceIfCurrent(new AnsibleGalaxyRegistryDao.GroupBinding(
        groupRepositoryId, "acme", "tools", "1.2.3", proxyRepositoryId, proxyVersion.id(),
        proxyVersion.artifactFilename(), proxyRevision, metadataGroupRevision,
        proxyVersion.artifactSha256(), now, now)));
    assertEquals(proxyVersion.id(), registry.findGroupBindingByArtifactFilename(
        groupRepositoryId, proxyVersion.artifactFilename()).orElseThrow().memberVersionId());
    assertTrue(inTransaction(() -> registry.deleteVersion(
        proxyRepositoryId, proxyVersion.id(), proxyAssetId)));
    assertTrue(registry.findProxyState(
        proxyRepositoryId, "acme", "tools", "1.2.3").isEmpty());
    assertEquals(1, stores().assets().deleteAssetById(proxyAssetId));

    registry.upsertProxyState(new AnsibleGalaxyRegistryDao.ProxyVersionState(
        proxyRepositoryId, "acme", "missing", "9.9.9", null, null, null, null,
        null, null, null, now, 404, now.plusSeconds(1), Map.of(), 1L, now));
    registry.replaceProxyInventory(new AnsibleGalaxyRegistryDao.ProxyInventory(
        proxyRepositoryId, "acme", "stale", now.minusSeconds(2), now.minusSeconds(2),
        0L, 1, now.minusSeconds(2)), List.of("1.0.0"));
    assertEquals(2, registry.deleteExpiredProxyCache(
        now.plusSeconds(2), now.minusSeconds(1), 100));
    assertTrue(registry.findProxyInventory(proxyRepositoryId, "acme", "stale").isEmpty());
    assertEquals(1, registry.deleteTerminalTasksBefore(now.plusSeconds(3), 100));
    registry.releaseLease(
        secondLease.leaseKey(), secondLease.owner(), secondLease.fencingToken());
    assertEquals(1, registry.deleteExpiredLeasesBefore(Instant.now().plusSeconds(1), 100));
  }

  @Test
  void ansibleImportCoordinatesAreReservedAcrossConcurrentReplicas() throws Exception {
    long repositoryId = createRepository(
        "ansible-reservation-hosted", RepositoryFormat.ANSIBLEGALAXY);
    AnsibleGalaxyRegistryDao registry = stores().ansibleGalaxyRegistry();
    Instant now = Instant.parse("2026-07-22T03:00:00Z");
    CyclicBarrier transactionsReady = new CyclicBarrier(2);
    List<Callable<Boolean>> attempts = List.of(
        () -> createAnsibleTaskReservation(
            registry, ansibleImportTask(
                "33333333-4444-5555-6666-777777777777", repositoryId, now),
            transactionsReady),
        () -> createAnsibleTaskReservation(
            registry, ansibleImportTask(
                "44444444-5555-6666-7777-888888888888", repositoryId, now),
            transactionsReady));

    List<Boolean> outcomes = invokeConcurrently(attempts, 2);

    assertEquals(1L, outcomes.stream().filter(Boolean::booleanValue).count());
    AnsibleGalaxyRegistryDao.ImportTask winner = registry.listClaimableTasks(
            now.plusSeconds(1), 100).stream()
        .filter(task -> task.repositoryId() == repositoryId)
        .findFirst()
        .orElseThrow();
    assertEquals(winner.taskId(), registry.findActiveTaskId(
        repositoryId, "acme", "tools", "2.0.0").orElseThrow());
    AnsibleGalaxyRegistryDao.ImportTask claimed = registry.claimTask(
        winner.taskId(), "reservation-worker", now.plusSeconds(31), now.plusSeconds(1))
        .orElseThrow();
    assertTrue(registry.finishTask(
        claimed.taskId(), "reservation-worker", claimed.fencingToken(),
        AnsibleGalaxyRegistryDao.TASK_FAILED, List.of(), "fixture.failure", "fixture failure",
        claimed.namespaceLc(), claimed.nameLc(), claimed.versionNormalized(),
        claimed.artifactFilename(), claimed.actualSha256(), now.plusSeconds(2)));
    assertTrue(registry.findActiveTaskId(
        repositoryId, "acme", "tools", "2.0.0").isEmpty());
    String retryTaskId = "55555555-6666-7777-8888-999999999999";
    assertNotNull(registry.createTask(ansibleImportTask(
        retryTaskId, repositoryId, now.plusSeconds(3))));
    assertEquals(retryTaskId, registry.findActiveTaskId(
        repositoryId, "acme", "tools", "2.0.0").orElseThrow());
  }

  @Test
  void swiftRegistryStateIsImmutableFencedAndSharedAcrossReplicas() {
    long repositoryId = createRepository("swift-hosted", RepositoryFormat.SWIFT);
    long groupRepositoryId = createRepository("swift-group", RepositoryFormat.SWIFT);
    long otherMemberId = createRepository("swift-other-member", RepositoryFormat.SWIFT);
    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-16T08:00:00Z");

    long componentId = stores().components().upsertReturningId(component(
        repositoryId, RepositoryFormat.SWIFT, "acme", "fixture", "1.2.3",
        Map.of("kind", "swift-package-release"), now));
    long archiveBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/acme/fixture/1.2.3.zip", "swift-archive-ref"));
    String archivePath = "acme/fixture/1.2.3.zip";
    long archiveAssetId = stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, componentId, archiveBlobId, RepositoryFormat.SWIFT, archivePath,
        PersistenceHashes.pathHash(archivePath), "1.2.3.zip", "source-archive",
        "application/zip", 42L, null, now, Map.of()));
    long manifestBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/acme/fixture/Package.swift", "swift-manifest-ref"));
    String manifestPath = "acme/fixture/1.2.3/Package.swift";
    long manifestAssetId = stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, componentId, manifestBlobId, RepositoryFormat.SWIFT, manifestPath,
        PersistenceHashes.pathHash(manifestPath), "Package.swift", "manifest", "text/x-swift",
        42L, null, now, Map.of("toolsVersion", "5.9")));

    SwiftRegistryDao registry = stores().swiftRegistry();
    assertEquals(0, registry.currentRepositoryRevision(repositoryId));
    long revision = registry.nextRepositoryRevision(repositoryId);
    assertEquals(1, revision);
    SwiftRegistryDao.Release release = new SwiftRegistryDao.Release(
        null, repositoryId, componentId, "acme", "Acme", "fixture", "Fixture", "1.2.3",
        now, "{\"repositoryURLs\":[\"https://github.com/acme/fixture\"]}", "a".repeat(64),
        archiveAssetId, null, null, null, "HOSTED", revision,
        SwiftRegistryDao.RELEASE_READY, now, now);
    SwiftRegistryDao.Release stored = inTransaction(() -> registry.insertRelease(
        release,
        List.of(new SwiftRegistryDao.Manifest(
            null, "Package.swift", "", manifestAssetId, "b".repeat(64), "5.9")),
        List.of(new SwiftRegistryDao.RepositoryUrl(
            null, null, repositoryId, "acme", "fixture",
            "https://github.com/acme/fixture", "https://github.com/Acme/Fixture"))));

    assertNotNull(stored.id());
    assertEquals(1, registry.listReleases(repositoryId, "acme", "fixture").size());
    assertEquals(manifestAssetId,
        registry.findManifest(stored.id(), null).orElseThrow().assetId());
    assertEquals("5.9",
        registry.findManifest(stored.id(), null).orElseThrow().declaredToolsVersion());
    assertEquals(
        Map.of(repositoryId, revision, groupRepositoryId, 0L),
        registry.currentRepositoryRevisions(List.of(repositoryId, groupRepositoryId)));
    assertEquals("Acme", registry.findIdentities(
        repositoryId, "https://github.com/acme/fixture").getFirst().scopeDisplay());
    assertEquals(
        List.of("https://github.com/acme/fixture"),
        registry.listRepositoryUrls(repositoryId, "acme", "fixture").stream()
            .map(SwiftRegistryDao.RepositoryUrl::normalizedUrl)
            .toList());
    assertThrows(RuntimeException.class, () -> inTransaction(() -> registry.insertRelease(
        release, List.of(), List.of())));
    assertEquals(1, registry.listReleases(repositoryId, "acme", "fixture").size());

    for (String distinctVersion : List.of(
        "1.0.0-alpha", "1.0.0-ALPHA", "1.0.0+build.one", "1.0.0+build.two")) {
      insertSwiftReleaseFixture(
          registry, repositoryId, blobStoreId, distinctVersion, now.plusSeconds(10));
    }
    assertEquals(
        Set.of("1.2.3", "1.0.0-alpha", "1.0.0-ALPHA",
            "1.0.0+build.one", "1.0.0+build.two"),
        registry.listReleases(repositoryId, "acme", "fixture").stream()
            .map(SwiftRegistryDao.Release::version)
            .collect(java.util.stream.Collectors.toSet()),
        "SemVer prerelease and build identifiers must remain case-sensitive on every database");

    SwiftRegistryDao.ProxySource firstSource = registry.bindProxySource(
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "fixture", "1.2.3", "https://github.com/acme/fixture",
            "v1.2.3", "c".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            now, revision, 0, now));
    SwiftRegistryDao.ProxySource movedTag = registry.bindProxySource(
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "fixture", "1.2.3", "https://github.com/acme/fixture",
            "v1.2.3", "d".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            now.plusSeconds(1), revision, 0, now.plusSeconds(1)));
    assertEquals(firstSource.commitSha(), movedTag.commitSha());
    assertEquals(2, movedTag.observedCount());
    assertEquals(List.of("1.2.3"), registry.listProxySources(
        repositoryId, "acme", "fixture").stream()
        .map(SwiftRegistryDao.ProxySource::version)
        .toList());
    assertEquals(now.plusSeconds(1), registry.listProxySources(
        repositoryId, "acme", "fixture").getFirst().lastCheckedAt());

    List<SwiftRegistryDao.ProxySource> bulkSources = registry.bindProxySources(List.of(
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "fixture", "1.2.3", "https://github.com/acme/fixture",
            "v1.2.3", "e".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            now.plusSeconds(2), revision, 0, now.plusSeconds(2)),
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "fixture", "2.0.0", "https://github.com/acme/fixture",
            "v2.0.0", "f".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            now.plusSeconds(2), revision, 0, now.plusSeconds(2))));
    assertEquals(List.of("1.2.3", "2.0.0"), bulkSources.stream()
        .map(SwiftRegistryDao.ProxySource::version)
        .toList());
    assertEquals(firstSource.commitSha(), bulkSources.getFirst().commitSha());
    assertEquals(3, bulkSources.getFirst().observedCount());

    SwiftRegistryDao.ProxyInventory inventory = new SwiftRegistryDao.ProxyInventory(
        repositoryId,
        "acme",
        "fixture",
        revision,
        now.plusSeconds(3),
        Map.of("1", List.of(new SwiftRegistryDao.ProxyTag(
            "1.2.3", "v1.2.3", firstSource.commitSha()))),
        Map.of("1", "github-page-one"));
    registry.upsertProxyInventory(inventory);
    assertEquals(inventory,
        registry.findProxyInventory(repositoryId, "acme", "fixture").orElseThrow());

    registry.bindProxySources(List.of(
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "pruned", "1.0.0", "https://github.com/acme/pruned",
            "v1.0.0", "1".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            null, revision, 0, now),
        new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "pruned", "2.0.0", "https://github.com/acme/pruned",
            "v2.0.0", "2".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            null, revision, 0, now)));
    List<SwiftRegistryDao.ProxySource> replacedSources = registry.replaceProxySources(
        repositoryId,
        "acme",
        "pruned",
        List.of(new SwiftRegistryDao.ProxySource(
            repositoryId, "acme", "pruned", "2.0.0", "https://github.com/acme/pruned",
            "v2.0.0", "2".repeat(40), "github-archive-v1", null, "DISCOVERED", null,
            null, revision, 0, now.plusSeconds(1))));
    assertEquals(List.of("2.0.0"), replacedSources.stream()
        .map(SwiftRegistryDao.ProxySource::version)
        .toList());
    assertEquals(List.of("2.0.0"), registry.listProxySources(
        repositoryId, "acme", "pruned").stream()
        .map(SwiftRegistryDao.ProxySource::version)
        .toList());
    assertTrue(registry.replaceProxySources(
        repositoryId, "acme", "pruned", List.of()).isEmpty());
    assertTrue(registry.listProxySources(repositoryId, "acme", "pruned").isEmpty());

    SwiftRegistryDao.Lease lowerCasePrereleaseLease = registry.tryAcquireLease(
        "swift:acme:fixture:1.0.0-alpha", "replica-a", Instant.now().plusSeconds(30))
        .orElseThrow();
    SwiftRegistryDao.Lease upperCasePrereleaseLease = registry.tryAcquireLease(
        "swift:acme:fixture:1.0.0-ALPHA", "replica-b", Instant.now().plusSeconds(30))
        .orElseThrow();
    assertNotEquals(
        lowerCasePrereleaseLease.leaseKey(), upperCasePrereleaseLease.leaseKey(),
        "coordinate leases must preserve SemVer identifier case");
    registry.releaseLease(
        lowerCasePrereleaseLease.leaseKey(), lowerCasePrereleaseLease.owner(),
        lowerCasePrereleaseLease.fencingToken());
    registry.releaseLease(
        upperCasePrereleaseLease.leaseKey(), upperCasePrereleaseLease.owner(),
        upperCasePrereleaseLease.fencingToken());

    SwiftRegistryDao.Lease firstLease = registry.tryAcquireLease(
        "swift:acme:fixture:1.2.3", "replica-a", Instant.now().plusSeconds(30)).orElseThrow();
    assertTrue(registry.tryAcquireLease(
        "swift:acme:fixture:1.2.3", "replica-b", Instant.now().plusSeconds(30)).isEmpty());
    assertFalse(registry.completeProxySource(
        repositoryId, "acme", "fixture", "1.2.3", firstSource.commitSha(), "e".repeat(64),
        "READY", stored.id(), now, revision, firstLease.leaseKey(), firstLease.owner(),
        firstLease.fencingToken() + 1));
    assertTrue(registry.completeProxySource(
        repositoryId, "acme", "fixture", "1.2.3", firstSource.commitSha(), "e".repeat(64),
        "READY", stored.id(), now, revision, firstLease.leaseKey(), firstLease.owner(),
        firstLease.fencingToken()));
    registry.releaseLease(firstLease.leaseKey(), firstLease.owner(), firstLease.fencingToken());
    SwiftRegistryDao.Lease secondLease = registry.tryAcquireLease(
        firstLease.leaseKey(), "replica-b", Instant.now().plusSeconds(30)).orElseThrow();
    assertTrue(secondLease.fencingToken() > firstLease.fencingToken());
    assertFalse(registry.renewLease(
        firstLease.leaseKey(), firstLease.owner(), firstLease.fencingToken(),
        Instant.now().plusSeconds(60)));
    assertTrue(registry.renewLease(
        secondLease.leaseKey(), secondLease.owner(), secondLease.fencingToken(),
        Instant.now().plusSeconds(60)));

    long groupConfigRevision = registry.nextRepositoryRevision(groupRepositoryId);
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", "1.2.3", repositoryId, stored.id(),
            revision, groupConfigRevision, now))));
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", "1.2.3", otherMemberId, stored.id(),
            revision, groupConfigRevision, now.plusMillis(500)))));
    assertEquals(repositoryId, registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", "1.2.3").orElseThrow().memberRepositoryId(),
        "the first binding for one configuration revision remains canonical across replicas");
    long updatedGroupConfigRevision = registry.nextRepositoryRevision(groupRepositoryId);
    assertFalse(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", "1.2.3", otherMemberId, stored.id(),
            revision, groupConfigRevision, now.plusSeconds(1)))));
    assertEquals(repositoryId, registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", "1.2.3").orElseThrow().memberRepositoryId());
    registry.deleteGroupSourceBindings(groupRepositoryId);
    assertFalse(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", "1.2.3", repositoryId, stored.id(),
            revision, groupConfigRevision, now.plusSeconds(2)))));
    assertTrue(registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", "1.2.3").isEmpty());
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", "1.2.3", otherMemberId, stored.id(),
            revision, updatedGroupConfigRevision, now.plusSeconds(3)))));
    assertEquals(otherMemberId, registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", "1.2.3").orElseThrow().memberRepositoryId());

    registry.putNegativeCache(new SwiftRegistryDao.NegativeCache(
        repositoryId, "github:missing", 404, null, Instant.now().plusSeconds(30), now));
    assertEquals(404, registry.findNegativeCache(
        repositoryId, "github:missing").orElseThrow().statusCode());
    assertEquals(1, registry.deleteExpiredNegativeCache(Instant.now().plusSeconds(60)));

    long tombstoneRevision = registry.nextRepositoryRevision(repositoryId);
    registry.tombstoneRelease(new SwiftRegistryDao.Tombstone(
        repositoryId, "acme", "fixture", "1.2.3", "deleted", tombstoneRevision, now));
    assertTrue(registry.findRelease(repositoryId, "acme", "fixture", "1.2.3").isEmpty());
    assertEquals(tombstoneRevision, registry.findTombstone(
        repositoryId, "acme", "fixture", "1.2.3").orElseThrow().revision());
    assertEquals(List.of("1.2.3"), registry.listTombstones(
        repositoryId, "acme", "fixture").stream()
        .map(SwiftRegistryDao.Tombstone::version)
        .toList());

    long migrationJobId = stores().migrationJobs().create(
        "3.94.0", "/nexus-data", Map.of("scope", "swift", "dryRun", false));
    MigrationCheckpointRecord checkpoint = new MigrationCheckpointRecord(
        migrationJobId, "component", "swift-package-release", "#12:0", "swift_release",
        stored.id().toString(), "f".repeat(64), now);
    stores().migrationCheckpoints().upsert(checkpoint);
    stores().migrationCheckpoints().upsert(checkpoint);
    assertEquals(stored.id().toString(), stores().migrationCheckpoints().find(
        migrationJobId, "component", "swift-package-release", "#12:0")
        .orElseThrow().targetId());
  }

  @Test
  void swiftMemberMutationsRecursivelyInvalidateGroupBindingsInTheSameTransaction() {
    long memberRepositoryId = createRepository("swift-member", RepositoryFormat.SWIFT);
    long groupRepositoryId = createRepository(
        "swift-inner-group", RepositoryFormat.SWIFT, RepositoryType.GROUP);
    long outerGroupRepositoryId = createRepository(
        "swift-outer-group", RepositoryFormat.SWIFT, RepositoryType.GROUP);
    stores().repositories().addMember(groupRepositoryId, memberRepositoryId, 0);
    stores().repositories().addMember(outerGroupRepositoryId, groupRepositoryId, 0);
    SwiftRegistryDao registry = stores().swiftRegistry();
    long blobStoreId = stores().repositories().findById(memberRepositoryId)
        .orElseThrow().blobStoreId();
    Instant now = Instant.parse("2026-07-16T09:00:00Z");

    SwiftRegistryDao.Release first = insertSwiftReleaseFixture(
        registry, memberRepositoryId, blobStoreId, "1.0.0", now);
    long innerRevision = registry.currentRepositoryRevision(groupRepositoryId);
    long outerRevision = registry.currentRepositoryRevision(outerGroupRepositoryId);
    assertTrue(innerRevision > 0);
    assertTrue(outerRevision > 0);
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", first.version(), memberRepositoryId,
            first.id(), first.revision(), innerRevision, now))));
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            outerGroupRepositoryId, "acme", "fixture", first.version(), groupRepositoryId,
            first.id(), first.revision(), outerRevision, now))));

    SwiftRegistryDao.Release second = insertSwiftReleaseFixture(
        registry, memberRepositoryId, blobStoreId, "2.0.0", now.plusSeconds(1));

    long innerAfterPublish = registry.currentRepositoryRevision(groupRepositoryId);
    long outerAfterPublish = registry.currentRepositoryRevision(outerGroupRepositoryId);
    assertTrue(innerAfterPublish > innerRevision);
    assertTrue(outerAfterPublish > outerRevision);
    assertTrue(registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", first.version()).isEmpty());
    assertTrue(registry.findGroupSourceBinding(
        outerGroupRepositoryId, "acme", "fixture", first.version()).isEmpty());

    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            groupRepositoryId, "acme", "fixture", second.version(), memberRepositoryId,
            second.id(), second.revision(), innerAfterPublish, now.plusSeconds(1)))));
    assertTrue(inTransaction(() -> registry.upsertGroupSourceBindingIfCurrent(
        new SwiftRegistryDao.GroupSourceBinding(
            outerGroupRepositoryId, "acme", "fixture", second.version(), groupRepositoryId,
            second.id(), second.revision(), outerAfterPublish, now.plusSeconds(1)))));

    assertThrows(IllegalStateException.class, () -> inTransaction(() -> {
      registry.tombstoneAndDeleteReleaseState(
          memberRepositoryId, "acme", "fixture", second.version(), "rollback", now.plusSeconds(2))
          .orElseThrow();
      throw new IllegalStateException("force rollback");
    }));
    assertEquals(innerAfterPublish, registry.currentRepositoryRevision(groupRepositoryId));
    assertEquals(outerAfterPublish, registry.currentRepositoryRevision(outerGroupRepositoryId));
    assertTrue(registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", second.version()).isPresent());
    assertTrue(registry.findGroupSourceBinding(
        outerGroupRepositoryId, "acme", "fixture", second.version()).isPresent());
    assertTrue(registry.findRelease(
        memberRepositoryId, "acme", "fixture", second.version()).isPresent());
    assertTrue(registry.findTombstone(
        memberRepositoryId, "acme", "fixture", second.version()).isEmpty());

    SwiftRegistryDao.DeletedRelease deleted = inTransaction(() ->
        registry.tombstoneAndDeleteReleaseState(
            memberRepositoryId, "acme", "fixture", second.version(), "deleted",
            now.plusSeconds(3)).orElseThrow());

    assertEquals(second.componentId(), deleted.componentId());
    assertTrue(registry.currentRepositoryRevision(groupRepositoryId) > innerAfterPublish);
    assertTrue(registry.currentRepositoryRevision(outerGroupRepositoryId) > outerAfterPublish);
    assertTrue(registry.findGroupSourceBinding(
        groupRepositoryId, "acme", "fixture", second.version()).isEmpty());
    assertTrue(registry.findGroupSourceBinding(
        outerGroupRepositoryId, "acme", "fixture", second.version()).isEmpty());
    assertTrue(registry.findRelease(
        memberRepositoryId, "acme", "fixture", second.version()).isEmpty());
    assertTrue(registry.findTombstone(
        memberRepositoryId, "acme", "fixture", second.version()).isPresent());
  }

  @Test
  void swiftAdministrativeDeleteSerializesWithTheCoordinatePublishFence() throws Exception {
    long repositoryId = createRepository("swift-delete-fence", RepositoryFormat.SWIFT);
    long blobStoreId = stores().repositories().findById(repositoryId)
        .orElseThrow().blobStoreId();
    SwiftRegistryDao registry = stores().swiftRegistry();
    String version = "3.0.0";
    insertSwiftReleaseFixture(
        registry, repositoryId, blobStoreId, version, Instant.parse("2026-07-16T10:00:00Z"));
    String leaseKey = "swift:" + repositoryId + ":acme:fixture:" + version;
    SwiftRegistryDao.Lease lease = registry.tryAcquireLease(
        leaseKey, "publishing-replica", Instant.now().plusSeconds(30)).orElseThrow();
    CountDownLatch publishFenceLocked = new CountDownLatch(1);
    CountDownLatch allowPublishCommit = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var publishing = executor.submit(() -> inTransaction(() -> {
        assertTrue(registry.renewLease(
            lease.leaseKey(), lease.owner(), lease.fencingToken(), Instant.now().plusSeconds(30)));
        publishFenceLocked.countDown();
        await(allowPublishCommit);
        assertTrue(registry.findTombstone(
            repositoryId, "acme", "fixture", version).isEmpty());
        return null;
      }));
      assertTrue(publishFenceLocked.await(30, java.util.concurrent.TimeUnit.SECONDS));

      var deleting = executor.submit(() -> inTransaction(() ->
          registry.tombstoneAndDeleteReleaseState(
              repositoryId,
              "acme",
              "fixture",
              version,
              "administrative delete",
              Instant.now()).orElseThrow()));

      assertThrows(
          java.util.concurrent.TimeoutException.class,
          () -> deleting.get(250, java.util.concurrent.TimeUnit.MILLISECONDS),
          "delete must wait for the in-transaction publication fence");
      allowPublishCommit.countDown();
      publishing.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          1L,
          deleting.get(30, java.util.concurrent.TimeUnit.SECONDS).assetIds().size());
    } finally {
      allowPublishCommit.countDown();
    }

    assertTrue(registry.findRelease(
        repositoryId, "acme", "fixture", version).isEmpty());
    assertTrue(registry.findTombstone(
        repositoryId, "acme", "fixture", version).isPresent());
  }

  @Test
  void terraformRegistryStateIsSharedTransactionalAndReplicaSafe() {
    long repositoryId = createRepository("terraform-hosted", RepositoryFormat.TERRAFORM);
    long memberRepositoryId = createRepository("terraform-member", RepositoryFormat.TERRAFORM);
    TerraformRegistryDao registry = stores().terraformRegistry();
    Instant now = Instant.now();
    registry.insertSigningKey(new TerraformRegistryDao.SigningKey(
        repositoryId, 1, "0123456789ABCDEF", "encrypted-private", "public-key", now));

    assertEquals("0123456789ABCDEF", registry.findActiveSigningKey(repositoryId).orElseThrow().keyId());
    assertEquals(1, registry.findSigningKey(repositoryId, 1).orElseThrow().revision());

    registry.publishProvider(
        new TerraformRegistryDao.ProviderPlatform(
            repositoryId, "kkrepo", "fixture", "1.2.3", "linux", "amd64",
            "terraform-provider-fixture_1.2.3_linux_amd64.zip",
            "v1/providers/kkrepo/fixture/1.2.3/package/linux/terraform-provider-fixture-fixture.zip",
            "a".repeat(64), "5.0", 1, now),
        new TerraformRegistryDao.ProviderState(
            repositoryId, "kkrepo", "fixture", "1.2.3", 1,
            "v1/providers/kkrepo/fixture/1.2.3/terraform-provider-fixture_1.2.3_SHA256SUMS",
            "v1/providers/kkrepo/fixture/1.2.3/terraform-provider-fixture_1.2.3_SHA256SUMS.sig",
            1, now));
    assertEquals(1, registry.listProviderPlatforms(
        repositoryId, "kkrepo", "fixture", "1.2.3").size());
    assertEquals(1, registry.listProviderPlatformsForProvider(
        repositoryId, "kkrepo", "fixture").size());
    assertEquals(1, registry.findProviderState(
        repositoryId, "kkrepo", "fixture", "1.2.3").orElseThrow().revision());

    assertTrue(registry.tryAcquirePublishLease("provider:fixture", "replica-a", now.plusSeconds(30)));
    assertFalse(registry.tryAcquirePublishLease("provider:fixture", "replica-b", now.plusSeconds(30)));
    assertFalse(registry.renewPublishLease("provider:fixture", "replica-b", now.plusSeconds(60)));
    assertTrue(registry.renewPublishLease("provider:fixture", "replica-a", now.plusSeconds(60)));
    registry.releasePublishLease("provider:fixture", "replica-a");
    assertTrue(registry.tryAcquirePublishLease("provider:fixture", "replica-b", now.plusSeconds(30)));
    assertTrue(registry.tryAcquirePublishLease(
        "provider:expired", "replica-a", now.minusSeconds(1)));
    assertFalse(registry.renewPublishLease(
        "provider:expired", "replica-a", now.plusSeconds(60)));

    registry.upsertSourceBinding(new TerraformRegistryDao.SourceBinding(
        repositoryId, "asset:v1/providers/kkrepo/fixture", memberRepositoryId, 7,
        now.plusSeconds(60), now));
    assertEquals(memberRepositoryId, registry.findSourceBinding(
        repositoryId, "asset:v1/providers/kkrepo/fixture").orElseThrow().memberRepositoryId());
    registry.deleteSourceBindings(repositoryId);
    assertTrue(registry.findSourceBinding(
        repositoryId, "asset:v1/providers/kkrepo/fixture").isEmpty());
  }

  @Test
  void componentUpsertIsAtomicAndJsonValuesRoundTrip() throws Exception {
    long repositoryId = createRepository("maven-hosted", RepositoryFormat.MAVEN2);
    ComponentRecord component = component(
        repositoryId,
        RepositoryFormat.MAVEN2,
        "com.acme.platform",
        "observability-library",
        "1.2.3",
        Map.of("description", "distributed tracing", "verified", true),
        Instant.parse("2026-07-13T08:00:00Z"));

    List<Callable<Long>> writes = new ArrayList<>();
    for (int index = 0; index < 12; index++) {
      writes.add(() -> stores().components().upsertReturningId(component));
    }
    List<Long> ids;
    try (var executor = Executors.newFixedThreadPool(6)) {
      ids = executor.invokeAll(writes).stream().map(future -> {
        try {
          return future.get();
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }).toList();
    }

    assertEquals(1, new HashSet<>(ids).size());
    assertEquals(1L, stores().components().countByRepositoryId(repositoryId));
    long componentId = ids.getFirst();
    assertEquals(component.attributes(), stores().components().findById(componentId).orElseThrow().attributes());

    Map<String, Object> updated = Map.of("description", "telemetry platform", "verified", false);
    stores().components().updateAttributes(componentId, updated, Instant.parse("2026-07-13T09:00:00Z"));
    assertEquals(updated, stores().components().findById(componentId).orElseThrow().attributes());
  }

  @Test
  void componentSearchPreservesAndPrefixFormatRepositoryAndStableOrderingSemantics() {
    long mavenRepository = createRepository("maven-search", RepositoryFormat.MAVEN2);
    long otherMavenRepository = createRepository("maven-other", RepositoryFormat.MAVEN2);
    long npmRepository = createRepository("npm-search", RepositoryFormat.NPM);

    long checksumComponentId = stores().components().upsertReturningId(component(
        mavenRepository, RepositoryFormat.MAVEN2, "com.acme.platform", "observability-library",
        "2.0.0", Map.of("keywords", "telemetry tracing"), Instant.parse("2026-07-13T10:00:00Z")));
    stores().components().upsertReturningId(component(
        mavenRepository, RepositoryFormat.MAVEN2, "com.acme.platform", "observability-agent",
        "1.0.0", Map.of("keywords", "telemetry collector"), Instant.parse("2026-07-13T09:00:00Z")));
    stores().components().upsertReturningId(component(
        otherMavenRepository, RepositoryFormat.MAVEN2, "org.example", "observability-library",
        "3.0.0", Map.of("keywords", "telemetry tracing"), Instant.parse("2026-07-13T08:00:00Z")));
    stores().components().upsertReturningId(component(
        npmRepository, RepositoryFormat.NPM, "@acme", "observability-library",
        "4.0.0", Map.of("keywords", "telemetry tracing"), Instant.parse("2026-07-13T11:00:00Z")));

    var andSearch = stores().components().search("acme library", RepositoryFormat.MAVEN2, 20);
    assertEquals(List.of("2.0.0"), andSearch.stream().map(row -> row.version()).toList());
    assertEquals("observability-library",
        stores().components().search("observ libr", RepositoryFormat.MAVEN2, 20).getFirst().name());
    assertEquals(2,
        stores().components().search("telemetry tracing", RepositoryFormat.MAVEN2, 20).size());
    assertEquals(List.of("2.0.0"), stores().components().searchByRepositoryIds(
            List.of(mavenRepository), RepositoryFormat.MAVEN2, "telemetry tracing", 20)
        .stream().map(row -> row.version()).toList());
    assertEquals(List.of("2.0.0", "3.0.0"),
        stores().components().search("telemetry tracing", RepositoryFormat.MAVEN2, 20)
            .stream().map(row -> row.version()).toList());
    assertFalse(stores().components().search("telemetry", RepositoryFormat.NPM, 20).isEmpty());

    long blobStoreId = stores().repositories().findById(mavenRepository)
        .orElseThrow().blobStoreId();
    long checksumBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "maven-search/observability-library.jar", "component-search-sha1"));
    String checksumPath = "com/acme/platform/observability-library/2.0.0/"
        + "observability-library-2.0.0.jar";
    stores().assets().insertAsset(new AssetRecord(
        null, mavenRepository, checksumComponentId, checksumBlobId, RepositoryFormat.MAVEN2,
        checksumPath, PersistenceHashes.pathHash(checksumPath),
        "observability-library-2.0.0.jar", "ARTIFACT", "application/java-archive", 42L,
        null, Instant.parse("2026-07-13T10:00:00Z"), Map.of()));

    ComponentSearchCriteria exact = new ComponentSearchCriteria(
        "telemetry tracing", RepositoryFormat.MAVEN2, "maven-search",
        "com.acme.platform", "observability-library", "2.0.0");
    var exactPage = stores().components().searchPage(exact, 0, 2);
    assertEquals(List.of("2.0.0"), exactPage.stream().map(row -> row.version()).toList());

    ComponentSearchCriteria checksum = new ComponentSearchCriteria(
        null, null, null, null, null, null, "1".repeat(40));
    assertEquals(List.of("2.0.0"), stores().components().searchPage(checksum, 0, 2)
        .stream().map(row -> row.version()).toList());
    ComponentSearchCriteria missingChecksum = new ComponentSearchCriteria(
        null, null, null, null, null, null, "f".repeat(40));
    assertTrue(stores().components().searchPage(missingChecksum, 0, 2).isEmpty());

    ComponentSearchCriteria paged = new ComponentSearchCriteria(
        "telemetry", RepositoryFormat.MAVEN2, null, null, null, null);
    var firstPage = stores().components().searchPage(paged, 0, 1);
    var secondPage = stores().components().searchPage(paged, firstPage.getFirst().id(), 1);
    assertEquals(1, firstPage.size());
    assertEquals(1, secondPage.size());
    assertTrue(secondPage.getFirst().id() > firstPage.getFirst().id());
  }

  @Test
  void cacheVersionsAreMonotonicAcrossConcurrentConnections() throws Exception {
    List<Callable<Long>> bumps = new ArrayList<>();
    for (int index = 0; index < 16; index++) {
      bumps.add(() -> stores().cacheVersions().bump("security"));
    }
    List<Long> versions;
    try (var executor = Executors.newFixedThreadPool(8)) {
      versions = executor.invokeAll(bumps).stream().map(future -> {
        try {
          return future.get();
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }).sorted().toList();
    }

    assertEquals(java.util.stream.LongStream.rangeClosed(1, 16).boxed().toList(), versions);
    assertEquals(16L, stores().cacheVersions().current("security"));
  }

  @Test
  void insertIfAbsentRelationshipsAndBacklogAgeRemainIdempotent() {
    var security = stores().security();
    long userId = security.insertUser(new SecurityUserRecord(
        null, "Local", "alice", "Alice", "Example", "alice@example.com", "hash", "active",
        null, Map.of("team", "infra")));
    security.upsertRole(new SecurityRoleRecord(
        "developers", "Local", "Developers", "Developers", false, Map.of()));
    security.upsertRole(new SecurityRoleRecord(
        "readers", "Local", "Readers", "Readers", false, Map.of()));
    var privilege = new SecurityPrivilegeRecord(
        "repository-read", "Repository read", "Read repositories", "repository-view", false,
        Map.of("actions", List.of("read")));
    security.insertPrivilegeIfAbsent(privilege);
    security.insertPrivilegeIfAbsent(privilege);
    security.assignRole(userId, "developers");
    security.assignRole(userId, "developers");
    security.grantPrivilege("developers", privilege.privilegeId());
    security.grantPrivilege("developers", privilege.privilegeId());
    security.inheritRole("developers", "readers");
    security.inheritRole("developers", "readers");

    assertEquals(List.of("developers"), security.listUserRoleIds(userId));
    assertEquals(List.of("repository-read"), security.listRolePrivilegeIds("developers"));
    assertEquals(List.of("readers"), security.listRoleChildIds("developers"));

    long repositoryId = createRepository("marker-hosted", RepositoryFormat.MAVEN2);
    stores().metadataRebuild().enqueue(repositoryId, "ga:com.acme/library");
    stores().metadataRebuild().enqueue(repositoryId, "ga:com.acme/library");
    assertEquals(1L, stores().metadataRebuild().countBacklog());
    assertTrue(stores().metadataRebuild().oldestBacklogAgeSeconds() >= 0);
    stores().repositoryIndexRebuild().enqueue(
        repositoryId, RepositoryIndexRebuildDao.HELM_INDEX, RepositoryIndexRebuildDao.ROOT_SCOPE);
    assertEquals(1L, stores().repositoryIndexRebuild().countBacklog());
    assertTrue(stores().repositoryIndexRebuild().oldestBacklogAgeSeconds() >= 0);
  }

  @Test
  void migrationJsonBindingExtractionAndBooleanUpdateUsePublicContracts() {
    long jobId = stores().migrationJobs().create(
        "3.70.1",
        "/nexus-data",
        Map.of("scope", "repository-data", "packageMigrationEnabled", false));

    assertEquals(1, stores().repositoryDataMigrations().listRepositoryDataJobs(10).size());
    stores().repositoryDataMigrations().setPackageMigrationEnabled(jobId, true);
    assertEquals(
        true,
        stores().migrationJobs().findById(jobId).orElseThrow().options().get("packageMigrationEnabled"));
    stores().migrationJobs().markFinished(jobId, "finished", Map.of("migrated", 12));
    assertEquals(
        12,
        stores().migrationJobs().findById(jobId).orElseThrow().summary().get("migrated"));
  }

  @Test
  void generatedKeysAndConcurrentBlobAndAssetNaturalKeysArePortable() throws Exception {
    long firstStoreId = stores().blobStores().insert(blobStore("keys-one"));
    long secondStoreId = stores().blobStores().insert(blobStore("keys-two"));
    assertNotEquals(firstStoreId, secondStoreId);

    AssetBlobRecord blob = blob(firstStoreId, "blobs/acme.jar", "blob-ref-acme");
    List<Callable<Long>> blobWrites = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      blobWrites.add(() -> stores().assets().insertBlobOrFindExisting(blob).id());
    }
    List<Long> blobIds = invokeConcurrently(blobWrites, 5);
    assertEquals(1, new HashSet<>(blobIds).size());
    long blobId = blobIds.getFirst();
    assertEquals(Map.of("origin", "contract"),
        stores().assets().findBlobById(blobId).orElseThrow().attributes());

    long repositoryId = insertRepository("asset-keys", RepositoryFormat.MAVEN2, firstStoreId);
    String path = "com/acme/app/1.0/app-1.0.jar";
    AssetRecord asset = new AssetRecord(
        null, repositoryId, null, blobId, RepositoryFormat.MAVEN2, path, sha256(path),
        "app-1.0.jar", "ARTIFACT", "application/java-archive", 42L, null,
        Instant.parse("2026-07-13T10:00:00Z"), Map.of("classifier", ""));
    List<Callable<Long>> assetWrites = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      assetWrites.add(() -> stores().assets().insertAsset(asset));
    }
    List<Long> assetIds = invokeConcurrently(assetWrites, 5);
    assertEquals(1, new HashSet<>(assetIds).size());
    assertEquals(path, stores().assets().findAssetById(assetIds.getFirst()).orElseThrow().path());
  }

  @Test
  void batchedAssetPathLookupUsesExactProtocolPaths() {
    long blobStoreId = stores().blobStores().insert(blobStore("asset-batch-store"));
    long repositoryId = insertRepository("asset-batch", RepositoryFormat.TERRAFORM, blobStoreId);
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "terraform/provider.zip", "terraform-provider-ref"));
    String existingPath = "v1/providers/acme/cloud/1.2.3/package/linux/provider.zip";
    stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, null, blobId, RepositoryFormat.TERRAFORM, existingPath,
        PersistenceHashes.pathHash(existingPath), "provider.zip", "provider-archive",
        "application/zip", 42L, null, Instant.parse("2026-07-13T10:00:00Z"), Map.of()));

    assertEquals(
        Set.of(existingPath),
        stores().assets().findExistingAssetPaths(
            repositoryId,
            List.of(existingPath, "v1/providers/acme/cloud/1.2.3/package/darwin/missing.zip",
                existingPath)));
  }

  @Test
  void duplicateAssetInsertKeepsProtocolWriterTransactionUsable() throws Exception {
    long blobStoreId = stores().blobStores().insert(blobStore("asset-conflict-store"));
    long repositoryId = insertRepository(
        "asset-conflict", RepositoryFormat.HELM, blobStoreId);
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "helm/index.yaml", "helm-index-ref"));
    String path = "index.yaml";
    AssetRecord asset = new AssetRecord(
        null, repositoryId, null, blobId, RepositoryFormat.HELM, path,
        PersistenceHashes.pathHash(path),
        path, "INDEX", "text/x-yaml", 42L, null,
        Instant.parse("2026-07-13T10:00:00Z"), Map.of("generated", true));
    CyclicBarrier transactionsReady = new CyclicBarrier(2);

    List<Callable<AssetInsertResult>> writes = List.of(
        () -> insertOrFindAssetInWriterTransaction(asset, transactionsReady),
        () -> insertOrFindAssetInWriterTransaction(asset, transactionsReady));
    List<AssetInsertResult> results = invokeConcurrently(writes, 2);

    assertEquals(1, results.stream().filter(AssetInsertResult::inserted).count());
    assertEquals(1, results.stream().map(AssetInsertResult::assetId).distinct().count());
    assertEquals(path, stores().assets().findAssetByPath(repositoryId, path).orElseThrow().path());
  }

  @Test
  void pypiPrefixLookupsArePortableAcrossDatabaseCollations() {
    long pypiRepositoryId = createRepository("pypi-prefix", RepositoryFormat.PYPI);
    long pypiBlobStoreId = stores().repositories().findById(pypiRepositoryId)
        .orElseThrow().blobStoreId();
    long pypiBlobId = stores().assets().insertBlob(
        blob(pypiBlobStoreId, "pypi/portable-pkg.whl", "pypi-portable-ref"));
    String pypiPath = "packages/portable-pkg/1.0.0/portable_pkg-1.0.0-py3-none-any.whl";
    stores().assets().insertAsset(new AssetRecord(
        null, pypiRepositoryId, null, pypiBlobId, RepositoryFormat.PYPI,
        pypiPath, sha256(pypiPath), "portable_pkg-1.0.0-py3-none-any.whl", "PACKAGE",
        "application/octet-stream", 42L, null, Instant.parse("2026-07-13T10:00:00Z"),
        Map.of("normalizedName", "portable-pkg", "requires_python", ">=3.8")));

    var pypiRows = stores().assets().listPypiProjectIndexRows(pypiRepositoryId, "portable-pkg");
    assertEquals(1, pypiRows.size());
    assertEquals(pypiPath, pypiRows.getFirst().path());
    assertEquals(">=3.8", pypiRows.getFirst().attributes().get("requires_python"));
    assertEquals(List.of("portable-pkg"), stores().assets().listPypiProjectNames(pypiRepositoryId));
    assertEquals(List.of(pypiPath), stores().assets()
        .listAssetsByPrefix(pypiRepositoryId, "packages/portable-pkg/")
        .stream().map(AssetRecord::path).toList());
  }

  @Test
  void staleAssetPrefixClaimsAreBoundedAndPortable() {
    long repositoryId = createRepository("swift-staging-cleanup", RepositoryFormat.SWIFT);
    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    long oldBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/staging/old.zip", "swift-staging-old-ref"));
    long freshBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/staging/fresh.zip", "swift-staging-fresh-ref"));
    long publicBlobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/releases/public.zip", "swift-release-public-ref"));
    String oldPath = ".swift/staging/old/source.zip";
    String freshPath = ".swift/staging/fresh/source.zip";
    String publicPath = "acme/demo/1.0.0.zip";
    stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, null, oldBlobId, RepositoryFormat.SWIFT,
        oldPath, sha256(oldPath), "source.zip", "swift", "application/zip", 42L,
        null, Instant.parse("2026-07-13T08:00:00Z"), Map.of()));
    stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, null, freshBlobId, RepositoryFormat.SWIFT,
        freshPath, sha256(freshPath), "source.zip", "swift", "application/zip", 42L,
        null, Instant.parse("2026-07-13T10:00:00Z"), Map.of()));
    stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, null, publicBlobId, RepositoryFormat.SWIFT,
        publicPath, sha256(publicPath), "1.0.0.zip", "swift", "application/zip", 42L,
        null, Instant.parse("2026-07-13T08:00:00Z"), Map.of()));

    List<AssetRecord> claimed = inTransaction(() -> stores().assets()
        .claimStaleAssetsByPrefix(
            repositoryId,
            ".swift/staging/",
            Instant.parse("2026-07-13T09:00:00Z"),
            1));

    assertEquals(List.of(oldPath), claimed.stream().map(AssetRecord::path).toList());
  }

  @Test
  void dockerAuthTokenKindsRoundTripWithoutTrustingTheSubjectSource() {
    DockerAuthTokenDao tokens = stores().dockerAuthTokens();
    Instant expiresAt = Instant.parse("2036-07-13T11:00:00Z");
    List<Map<String, Object>> scopes = List.of(Map.of(
        "repository", "docker-hosted",
        "imageName", "acme/app",
        "actions", List.of("pull")));
    String userHash = "a".repeat(64);
    String scannerHash = "b".repeat(64);

    inTransaction(() -> {
      tokens.insert(
          userHash,
          "security-scanner",
          "alice",
          "realm-1",
          null,
          TokenKind.USER,
          scopes,
          expiresAt);
      tokens.insert(
          scannerHash,
          "security-scanner",
          "scanner",
          null,
          null,
          TokenKind.SECURITY_SCANNER,
          scopes,
          expiresAt);
      tokens.insertScannerResources(scannerHash, List.of(
          new ScannerTokenResource(
              ScannerResourceKind.MANIFEST, "sha256:" + "c".repeat(64)),
          new ScannerTokenResource(
              ScannerResourceKind.BLOB, "sha256:" + "d".repeat(64))));
      return null;
    });

    DockerAuthTokenDao.TokenRecord user =
        inTransaction(() -> tokens.findValid(userHash, Instant.now()).orElseThrow());
    DockerAuthTokenDao.TokenRecord scanner =
        inTransaction(() -> tokens.findValid(scannerHash, Instant.now()).orElseThrow());
    assertEquals(TokenKind.USER, user.tokenKind());
    assertEquals(TokenKind.SECURITY_SCANNER, scanner.tokenKind());
    assertEquals(scopes, user.scopes().get("scopes"));
    assertEquals(scopes, scanner.scopes().get("scopes"));
    assertTrue(inTransaction(() -> tokens.scannerResourceAllowed(
        scannerHash, ScannerResourceKind.MANIFEST, "sha256:" + "c".repeat(64))));
    assertTrue(inTransaction(() -> tokens.scannerResourceAllowed(
        scannerHash, ScannerResourceKind.BLOB, "sha256:" + "d".repeat(64))));
    assertFalse(inTransaction(() -> tokens.scannerResourceAllowed(
        scannerHash, ScannerResourceKind.BLOB, "sha256:" + "e".repeat(64))));
  }

  @Test
  void dockerAuthTokenCleanupIsBoundedAndPreservesUnexpiredTokens() {
    DockerAuthTokenDao tokens = stores().dockerAuthTokens();
    Instant cleanupAt = Instant.parse("2026-07-13T11:00:00Z");
    inTransaction(() -> {
      for (String tokenHash : List.of("c".repeat(64), "d".repeat(64), "e".repeat(64))) {
        tokens.insert(
            tokenHash,
            "security-scanner",
            "scanner",
            null,
            null,
            TokenKind.SECURITY_SCANNER,
            List.of(),
            cleanupAt.minusSeconds(1));
      }
      tokens.insert(
          "f".repeat(64),
          "security-scanner",
          "scanner",
          null,
          null,
          TokenKind.SECURITY_SCANNER,
          List.of(),
          cleanupAt.plusSeconds(60));
      return null;
    });

    assertEquals(0, inTransaction(() -> tokens.deleteExpired(cleanupAt, 0)));
    assertEquals(2, inTransaction(() -> tokens.deleteExpired(cleanupAt, 2)));
    assertEquals(1, inTransaction(() -> tokens.deleteExpired(cleanupAt, 2)));
    assertEquals(0, inTransaction(() -> tokens.deleteExpired(cleanupAt, 2)));
    assertTrue(inTransaction(() -> tokens.findValid("f".repeat(64), cleanupAt)).isPresent());
  }

  @Test
  void dockerUnreferencedBlobCleanupSqlIsPortable() {
    long dockerRepositoryId = createRepository("docker-cleanup", RepositoryFormat.DOCKER);
    long dockerBlobStoreId = stores().repositories().findById(dockerRepositoryId)
        .orElseThrow().blobStoreId();
    AssetBlobRecord dockerBlob = blob(
        dockerBlobStoreId, "docker/blobs/sha256/layer", "docker-layer-ref");
    long dockerBlobId = stores().assets().insertBlob(dockerBlob);
    String digest = "sha256:" + dockerBlob.sha256();
    String dockerPath = "v2/acme/app/blobs/" + digest;
    long dockerAssetId = stores().assets().insertAsset(new AssetRecord(
        null, dockerRepositoryId, null, dockerBlobId, RepositoryFormat.DOCKER,
        dockerPath, sha256(dockerPath), digest, "BLOB", "application/octet-stream",
        dockerBlob.size(), null, Instant.parse("2026-07-13T10:00:00Z"),
        Map.of("docker", Map.of("digest", digest))));

    assertEquals(dockerAssetId, stores().dockerRegistry()
        .findUnreferencedBlobAssetIdForCleanup(
            dockerRepositoryId, 0, 10, Instant.parse("2026-07-13T11:00:00Z"))
        .orElseThrow());
  }

  @Test
  void browseSubtreeFlagsSupportBooleanReactivationAndPruning() {
    long repositoryId = createRepository("browse-boolean", RepositoryFormat.MAVEN2);
    String path = "com/acme/app/1.0/app-1.0.jar";

    stores().browseNodes().upsertPathAncestors(repositoryId, path, null, null);
    var emptyRoot = stores().browseNodes().listChildren(repositoryId, "").getFirst();
    assertEquals("com", emptyRoot.path());
    assertFalse(emptyRoot.hasAssetSubtree());

    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "browse/app-1.0.jar", "browse-app-ref"));
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null, repositoryId, null, blobId, RepositoryFormat.MAVEN2,
        path, sha256(path), "app-1.0.jar", "ARTIFACT", "application/java-archive",
        42L, null, Instant.parse("2026-07-13T10:00:00Z"), Map.of()));

    stores().browseNodes().upsertPathAncestors(repositoryId, path, assetId, null);
    assertTrue(stores().browseNodes().listChildren(repositoryId, "").getFirst().hasAssetSubtree());

    assertEquals(1, stores().browseNodes().deleteByAssetId(assetId));
    assertTrue(stores().browseNodes().listChildren(repositoryId, "").isEmpty());
  }

  @Test
  void groupMemberOrderAndReplacementAreStable() {
    long first = createRepository("member-first", RepositoryFormat.MAVEN2);
    long second = createRepository("member-second", RepositoryFormat.MAVEN2);
    long third = createRepository("member-third", RepositoryFormat.MAVEN2);
    long group = stores().repositories().insert(new RepositoryRecord(
        null, "maven-public", RepositoryFormat.MAVEN2, RepositoryType.GROUP,
        "maven2-group", true, null, null, null, null, null, null, true, Map.of()));

    stores().repositories().addMember(group, first, 20);
    stores().repositories().addMember(group, second, 10);
    assertEquals(List.of("member-second", "member-first"),
        stores().repositories().listMembers(group).stream().map(RepositoryRecord::name).toList());

    stores().repositories().replaceMembers(group, List.of(third, first, second));
    assertEquals(List.of("member-third", "member-first", "member-second"),
        stores().repositories().listMembers(group).stream().map(RepositoryRecord::name).toList());
    assertEquals(List.of("maven-public"),
        stores().repositories().listGroupsContaining(first).stream().map(RepositoryRecord::name).toList());
  }

  @Test
  void auditFilteringFreeTextAndPaginationHaveIdenticalSemantics() {
    var audit = stores().securityAudit();
    audit.insert(auditRecord(LocalDateTime.of(2026, 7, 13, 8, 0), "alice", "GET",
        "/repository/releases/a.jar", 200, "SUCCESS", Map.of("traceId", "trace-one")));
    audit.insert(auditRecord(LocalDateTime.of(2026, 7, 13, 9, 0), "bob", "PUT",
        "/repository/releases/b.jar", 201, "SUCCESS", Map.of("traceId", "trace-two")));
    audit.insert(auditRecord(LocalDateTime.of(2026, 7, 13, 10, 0), "alice", "DELETE",
        "/repository/releases/c.jar", 403, "DENIED", Map.of("reason", "policy")));

    var filtered = audit.search(new SecurityAuditDao.AuditLogQuery(
        null, null, " ALICE ", null, null, "/repository/releases", null,
        null, null, null, null, 0, 10));
    assertEquals(2, filtered.total());
    assertEquals(List.of("DELETE", "GET"), filtered.items().stream().map(item -> item.method()).toList());

    var freeText = audit.search(new SecurityAuditDao.AuditLogQuery(
        "trace-two", null, null, null, null, null, null, null, null,
        null, null, 0, 10));
    assertEquals(1, freeText.total());
    assertEquals("bob", freeText.items().getFirst().actorUserId());

    var firstPage = audit.search(new SecurityAuditDao.AuditLogQuery(
        null, null, null, null, null, null, null, null, null,
        null, null, 0, 1));
    var secondPage = audit.search(new SecurityAuditDao.AuditLogQuery(
        null, null, null, null, null, null, null, null, null,
        null, null, 1, 1));
    assertEquals(3, firstPage.total());
    assertNotEquals(firstPage.items().getFirst().id(), secondPage.items().getFirst().id());
  }

  @Test
  void markerClaimsPartitionConcurrentWorkersAndFailuresReenqueue() throws Exception {
    long repositoryId = createRepository("claim-markers", RepositoryFormat.MAVEN2);
    for (int index = 0; index < 8; index++) {
      stores().metadataRebuild().enqueue(repositoryId, "ga:com.acme/app-" + index);
      stores().repositoryIndexRebuild().enqueue(
          repositoryId, RepositoryIndexRebuildDao.PYPI_PROJECT, "app-" + index);
    }

    List<Callable<String>> metadataClaims = new ArrayList<>();
    List<Callable<String>> indexClaims = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      metadataClaims.add(() -> inTransaction(() ->
          stores().metadataRebuild().claim(1).getFirst().scopeKey()));
      indexClaims.add(() -> inTransaction(() ->
          stores().repositoryIndexRebuild().claim(1).getFirst().scopeKey()));
    }
    Set<String> claimedMetadata = new HashSet<>(invokeConcurrently(metadataClaims, 4));
    Set<String> claimedIndexes = new HashSet<>(invokeConcurrently(indexClaims, 4));
    assertEquals(8, claimedMetadata.size());
    assertEquals(8, claimedIndexes.size());
    assertEquals(0L, stores().metadataRebuild().countBacklog());
    assertEquals(0L, stores().repositoryIndexRebuild().countBacklog());

    var metadataFailure = new com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao.Claim(
        repositoryId, "ga:com.acme/failure", Instant.now(), 0, null);
    stores().metadataRebuild().reenqueueFailure(metadataFailure, new IllegalStateException("boom"));
    var indexFailure = new RepositoryIndexRebuildDao.Claim(
        repositoryId, RepositoryIndexRebuildDao.HELM_INDEX, "", Instant.now(), 0, null);
    stores().repositoryIndexRebuild().reenqueueFailure(indexFailure, new IllegalStateException("boom"));
    assertEquals(1L, stores().metadataRebuild().countFailures());
    assertEquals(1L, stores().repositoryIndexRebuild().countFailures());
  }

  @Test
  void blobGcDockerAndPubUploadCoordinationRoundTrip() throws Exception {
    long repositoryId = createRepository("upload-coordination", RepositoryFormat.DOCKER);
    long blobStoreId = stores().repositories().findById(repositoryId).orElseThrow().blobStoreId();
    long deletedBlobId = stores().assets().insertBlob(blob(
        blobStoreId, "gc/deleted-layer", "gc-ref"));
    stores().assets().markBlobDeletedById(deletedBlobId, "contract cleanup");
    var gcClaims = inTransaction(() -> stores().assets().claimDeletedBlobsForGc(
        10, Instant.now().plusSeconds(1), Instant.EPOCH));
    assertEquals(List.of(deletedBlobId), gcClaims.stream().map(AssetBlobRecord::id).toList());
    assertEquals(1, stores().assets().releaseBlobGcClaim(deletedBlobId));

    Instant expiresAt = Instant.now().plusSeconds(600);
    stores().dockerUploads().insertSession(new DockerUploadSessionRecord(
        "docker-session", repositoryId, "acme/app", sha256("acme/app"), "STARTED", 0,
        null, null, "alice", "127.0.0.1", expiresAt, null, null,
        Map.of("node", "one"), null, null));
    List<Callable<Long>> chunkAppends = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      chunkAppends.add(() -> inTransaction(() -> {
        var session = stores().dockerUploads().lockSession("docker-session").orElseThrow();
        int chunkIndex = stores().dockerUploads().nextChunkIndex("docker-session");
        long startOffset = session.nextOffset();
        long nextOffset = startOffset + 4;
        stores().dockerUploads().appendChunk(
            "docker-session", chunkIndex, startOffset, nextOffset - 1,
            "chunk-ref-" + chunkIndex, "chunks/" + chunkIndex, "abcd", 4, nextOffset);
        return startOffset;
      }));
    }
    assertEquals(List.of(0L, 4L, 8L, 12L),
        invokeConcurrently(chunkAppends, 4).stream().sorted().toList());
    inTransaction(() -> {
      stores().dockerUploads().lockSession("docker-session").orElseThrow();
      stores().dockerUploads().completeSession("docker-session", "sha256:abcd", "sha256");
      return null;
    });
    assertEquals(16L,
        stores().dockerUploads().findSession("docker-session").orElseThrow().nextOffset());
    assertEquals(List.of(0, 1, 2, 3), stores().dockerUploads().listChunks("docker-session")
        .stream().map(chunk -> chunk.chunkIndex()).toList());
    assertEquals(1, inTransaction(() -> stores().dockerUploads().claimTerminalSessions(
        Instant.now(), "worker-one", Instant.now().plusSeconds(30), 10)).size());

    long pubId = stores().pubUploadSessions().insert(new PubUploadSessionRecord(
        null, repositoryId, "pub-session", "field-token", "alice", null,
        PubUploadSessionDao.STATUS_NEW, expiresAt, null, null, null, null, null, null, null,
        null, null, null, Map.of(), null, null, null, null));
    inTransaction(() -> {
      assertEquals(pubId, stores().pubUploadSessions().lockById(pubId).orElseThrow().id());
      stores().pubUploadSessions().markUploaded(
          pubId, blobStoreId, "pub-ref", "pub/pkg.tar.gz", "md5", "sha1", "sha256",
          "sha512", 99, "acme_pkg", "1.0.0", Map.of("name", "acme_pkg"));
      stores().pubUploadSessions().markFinalized(pubId, Instant.parse("2026-07-13T11:00:00Z"));
      return null;
    });
    var finalized = stores().pubUploadSessions().find(repositoryId, "pub-session").orElseThrow();
    assertEquals(PubUploadSessionDao.STATUS_FINALIZED, finalized.status());
    assertEquals(Map.of("name", "acme_pkg"), finalized.pubspec());
    assertNotNull(finalized.finalizedAt());
  }

  @Test
  void repositoryMigrationAssetsCanBeClaimedFailedRetriedAndCompleted() throws Exception {
    long repositoryId = createRepository("migration-target", RepositoryFormat.MAVEN2);
    long migrationJobId = stores().migrationJobs().create(
        "3.70.1", "/nexus-data", Map.of(
            "scope", "repository-data", "packageMigrationEnabled", true));
    long repositoryJobId = stores().repositoryDataMigrations().createRepositoryJob(
        migrationJobId, "maven-releases", "migration-target", repositoryId,
        RepositoryFormat.MAVEN2, 100, Map.of("sourceType", "hosted"));
    List<RepositoryDataMigrationAssetRecord> discovered = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      String path = "com/acme/app/1.0/app-1.0-" + index + ".jar";
      discovered.add(new RepositoryDataMigrationAssetRecord(
          null, repositoryJobId, "source-asset-" + index, "source-component", path, sha256(path),
          RepositoryFormat.MAVEN2, "com.acme", "app", "1.0", "ARTIFACT",
          "application/java-archive", 1024L, "default@abc-" + index,
          Instant.parse("2026-07-13T08:00:00Z"), null,
          Instant.parse("2026-07-13T08:00:00Z"), Instant.parse("2026-07-13T08:00:00Z"),
          "admin", "127.0.0.1", "pending", 0, null, null, null, null, null, null,
          Map.of("sourceRepositoryType", "hosted"), null));
    }
    stores().repositoryDataMigrations().upsertDiscoveredAssets(
        repositoryJobId, discovered, Map.of());
    stores().repositoryDataMigrations().finishDiscoveryPage(repositoryJobId, null, true);

    Set<Long> concurrentlyClaimedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    List<Callable<List<RepositoryDataMigrationDao.AssetClaim>>> workerClaims = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      workerClaims.add(() -> {
        List<RepositoryDataMigrationDao.AssetClaim> workerClaimsResult = new ArrayList<>();
        // MySQL also locks the joined repository row, so competing replicas may briefly receive
        // an empty batch. A real worker polls again after the winning transaction commits.
        for (int attempt = 0; attempt < 100; attempt++) {
          if (concurrentlyClaimedIds.size() == discovered.size()) {
            return workerClaimsResult;
          }
          List<RepositoryDataMigrationDao.AssetClaim> claims = inTransaction(() ->
              stores().repositoryDataMigrations().claimAssetsForMigration(
                  migrationJobId, 2, 3, Instant.now().minusSeconds(60)));
          for (RepositoryDataMigrationDao.AssetClaim claim : claims) {
            assertTrue(concurrentlyClaimedIds.add(claim.asset().id()),
                () -> "Asset was claimed by more than one worker: " + claim.asset().id());
            workerClaimsResult.add(claim);
          }
          if (claims.isEmpty()) {
            Thread.sleep(10);
          }
        }
        return workerClaimsResult;
      });
    }
    List<RepositoryDataMigrationDao.AssetClaim> claimed = invokeConcurrently(workerClaims, 4)
        .stream().flatMap(List::stream).toList();
    assertEquals(8, claimed.size());
    assertEquals(8, concurrentlyClaimedIds.size());

    var firstClaim = claimed.getFirst();
    stores().repositoryDataMigrations().markAssetFailed(
        firstClaim.asset().id(), repositoryJobId, 1, "upstream failed");
    for (var successfulClaim : claimed.subList(1, claimed.size())) {
      stores().repositoryDataMigrations().markAssetMigrated(
          successfulClaim.asset().id(), repositoryJobId, null, null, null);
    }
    stores().repositoryDataMigrations().refreshRepositoryProgress(repositoryJobId);
    assertEquals(1, stores().repositoryDataMigrations().retryFailedAssets(migrationJobId));

    var retried = inTransaction(() -> stores().repositoryDataMigrations()
        .claimAssetsForMigration(migrationJobId, 10, 3, Instant.now().plusSeconds(1)))
        .getFirst();
    stores().repositoryDataMigrations().markAssetMigrated(
        retried.asset().id(), repositoryJobId, null, null, null);
    stores().repositoryDataMigrations().refreshRepositoryProgress(repositoryJobId);
    var progress = stores().repositoryDataMigrations().jobProgress(migrationJobId);
    assertEquals(8, progress.migratedAssets());
    assertEquals(0, progress.failedAssets());
    assertFalse(progress.active());
  }

  @Test
  void absoluteTimestampsRoundTripAcrossUtcShanghaiAndDstBoundaries() {
    long blobStoreId = stores().blobStores().insert(blobStore("time-roundtrip"));
    List<Instant> instants = List.of(
        Instant.parse("2026-07-13T08:00:00.123Z"),
        ZonedDateTime.of(2026, 7, 13, 16, 0, 0, 456_000_000,
            ZoneId.of("Asia/Shanghai")).toInstant(),
        ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 789_000_000,
            ZoneId.of("America/New_York")).withLaterOffsetAtOverlap().toInstant());

    for (int index = 0; index < instants.size(); index++) {
      Instant instant = instants.get(index);
      AssetBlobRecord fixture = blob(blobStoreId, "time/" + index, "time-ref-" + index);
      long id = stores().assets().insertBlob(new AssetBlobRecord(
          fixture.id(), fixture.blobStoreId(), fixture.blobRef(), fixture.blobRefHash(),
          fixture.objectKey(), fixture.objectKeyHash(), fixture.sha1(), fixture.sha256(), fixture.md5(),
          fixture.size(), fixture.contentType(), fixture.createdBy(), fixture.createdByIp(),
          instant, instant, fixture.attributes()));
      AssetBlobRecord stored = stores().assets().findBlobById(id).orElseThrow();
      assertEquals(instant, stored.blobCreatedAt());
      assertEquals(instant, stored.blobUpdatedAt());
    }
  }

  private long createRepository(String name, RepositoryFormat format) {
    return createRepository(name, format, RepositoryType.HOSTED);
  }

  private long createRepository(
      String name, RepositoryFormat format, RepositoryType type) {
    long blobStoreId = stores().blobStores().insert(blobStore(name + "-store"));
    return insertRepository(name, format, type, blobStoreId);
  }

  private SwiftRegistryDao.Release insertSwiftReleaseFixture(
      SwiftRegistryDao registry,
      long repositoryId,
      long blobStoreId,
      String version,
      Instant now) {
    long componentId = stores().components().upsertReturningId(component(
        repositoryId,
        RepositoryFormat.SWIFT,
        "acme",
        "fixture",
        version,
        Map.of("kind", "swift-package-release"),
        now));
    String token = version.replaceAll("[^A-Za-z0-9]", "-");
    long blobId = stores().assets().insertBlob(
        blob(blobStoreId, "swift/acme/fixture/" + token + ".zip", "swift-" + token));
    String path = "acme/fixture/" + version + ".zip";
    long assetId = stores().assets().insertAsset(new AssetRecord(
        null,
        repositoryId,
        componentId,
        blobId,
        RepositoryFormat.SWIFT,
        path,
        PersistenceHashes.pathHash(path),
        version + ".zip",
        "source-archive",
        "application/zip",
        42L,
        null,
        now,
        Map.of()));
    long revision = registry.nextRepositoryRevision(repositoryId);
    SwiftRegistryDao.Release fixture = new SwiftRegistryDao.Release(
        null,
        repositoryId,
        componentId,
        "acme",
        "Acme",
        "fixture",
        "Fixture",
        version,
        now,
        "{}",
        "a".repeat(64),
        assetId,
        null,
        null,
        null,
        "HOSTED",
        revision,
        SwiftRegistryDao.RELEASE_READY,
        now,
        now);
    return inTransaction(() -> registry.insertRelease(fixture, List.of(), List.of()));
  }

  private long insertRepository(String name, RepositoryFormat format, long blobStoreId) {
    return insertRepository(name, format, RepositoryType.HOSTED, blobStoreId);
  }

  private long insertRepository(
      String name, RepositoryFormat format, RepositoryType type, long blobStoreId) {
    return stores().repositories().insert(new RepositoryRecord(
        null,
        name,
        format,
        type,
        format.id() + "-" + type.name().toLowerCase(java.util.Locale.ROOT),
        true,
        blobStoreId,
        null,
        null,
        "RELEASE",
        "STRICT",
        "ALLOW_ONCE",
        true,
        Map.of("storage", Map.of("blobStoreName", name + "-store"))));
  }

  private static BlobStoreRecord blobStore(String name) {
    return new BlobStoreRecord(
        null, name, "S3", "https://s3.example", "cn-test-1", "artifacts", name,
        Map.of("pathStyleAccess", true));
  }

  private static AssetBlobRecord blob(long blobStoreId, String objectKey, String blobRef) {
    Instant now = Instant.parse("2026-07-13T08:00:00Z");
    return new AssetBlobRecord(
        null, blobStoreId, blobRef, sha256(blobRef), objectKey, sha256(objectKey),
        "1".repeat(40), "2".repeat(64), "3".repeat(32), 42,
        "application/octet-stream", "contract",
        "127.0.0.1", now, now, Map.of("origin", "contract"));
  }

  private static SecurityAuditDao.AuditLogRecord auditRecord(
      LocalDateTime occurredAt,
      String actor,
      String method,
      String path,
      int status,
      String outcome,
      Map<String, Object> details) {
    return new SecurityAuditDao.AuditLogRecord(
        occurredAt, "Local", actor, "LocalAuthenticatingRealm", null, "127.0.0.1",
        method, path, "repository-view", status, outcome, details);
  }

  private static <T> List<T> invokeConcurrently(List<Callable<T>> calls, int threads) throws Exception {
    try (var executor = Executors.newFixedThreadPool(threads)) {
      List<T> results = new ArrayList<>(calls.size());
      for (var future : executor.invokeAll(calls)) {
        results.add(future.get());
      }
      return results;
    }
  }

  private int foldArtifactChanges(String cursorName) {
    return inTransaction(() -> {
      stores().maintenanceCursors().ensureCursor(cursorName);
      var cursor = stores().maintenanceCursors().tryLockLastSeenId(cursorName);
      if (cursor.isEmpty()) {
        return 0;
      }
      List<ArtifactChangeDao.ArtifactChange> events =
          stores().artifactChanges().listAfter(cursor.getAsLong(), 1000);
      if (events.isEmpty()) {
        return 0;
      }
      events.stream()
          .map(ArtifactChangeDao.ArtifactChange::assetId)
          .distinct()
          .forEach(stores().securityScanning()::recordArtifactContentChange);
      assertEquals(
          1,
          stores().maintenanceCursors().updateLastSeenId(
              cursorName, events.getLast().id()));
      return events.size();
    });
  }

  private void advanceCursor(String cursorName, long eventId) {
    inTransaction(() -> {
      stores().maintenanceCursors().ensureCursor(cursorName);
      assertTrue(
          stores().maintenanceCursors().tryLockLastSeenId(cursorName).isPresent());
      assertEquals(
          1,
          stores().maintenanceCursors().updateLastSeenId(cursorName, eventId));
      return null;
    });
  }

  private boolean createAnsibleTaskReservation(
      AnsibleGalaxyRegistryDao registry,
      AnsibleGalaxyRegistryDao.ImportTask task,
      CyclicBarrier transactionsReady) {
    await(transactionsReady);
    try {
      inTransaction(() -> registry.createTask(task));
      return true;
    } catch (DuplicateKeyException duplicateCoordinate) {
      return false;
    }
  }

  private static AnsibleGalaxyRegistryDao.ImportTask ansibleImportTask(
      String taskId, long repositoryId, Instant now) {
    return new AnsibleGalaxyRegistryDao.ImportTask(
        taskId, repositoryId, "alice", AnsibleGalaxyRegistryDao.TASK_WAITING, List.of(),
        null, null, "acme", "tools", "2.0.0", "acme-tools-2.0.0.tar.gz",
        "e".repeat(64), "e".repeat(64), null, 0, null, null, 0L,
        now, null, null, now);
  }

  private AssetInsertResult insertOrFindAssetInWriterTransaction(
      AssetRecord asset, CyclicBarrier transactionsReady) {
    return inTransaction(() -> {
      await(transactionsReady);
      var inserted = stores().assets().tryInsertAsset(asset);
      long assetId = inserted.isPresent()
          ? inserted.getAsLong()
          : stores().assets().findAssetByPath(asset.repositoryId(), asset.path()).orElseThrow().id();
      return new AssetInsertResult(assetId, inserted.isPresent());
    });
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (Exception e) {
      throw new AssertionError("Failed to coordinate duplicate asset insert race", e);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent transaction");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for concurrent transaction", e);
    }
  }

  private record AssetInsertResult(long assetId, boolean inserted) {
  }

  private static ComponentRecord component(
      long repositoryId,
      RepositoryFormat format,
      String namespace,
      String name,
      String version,
      Map<String, Object> attributes,
      Instant updatedAt) {
    return new ComponentRecord(
        null,
        repositoryId,
        format,
        namespace,
        name,
        version,
        "release",
        sha256(namespace + '\u0000' + name + '\u0000' + version),
        attributes,
        updatedAt);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
