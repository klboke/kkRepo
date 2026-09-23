package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.Sbom;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanSummary;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanTask;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.TaskDraft;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.OciPlatformPolicy;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TaskStatus;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.ConfigCommand;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.PolicyCommand;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SecurityScanManagementServiceCoreTest {
  private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");
  private SecurityScanDao scans;
  private RepositoryDao repositories;
  private AssetDao assets;
  private BrowseNodeDao browseNodes;
  private DockerRegistryDao docker;
  private SecurityManagementService security;
  private SecurityScanDocumentStore documents;
  private SecurityScanDocumentPersistence documentPersistence;
  private SecurityScanningProperties properties;
  private SecurityScanRepositoryScope scope;
  private SecurityScanManagementService service;
  private AuthenticatedSubject actor;
  private RepositoryRecord repository;
  private ScanProfile profile;

  @BeforeEach
  void setUp() {
    scans = mock(SecurityScanDao.class);
    repositories = mock(RepositoryDao.class);
    assets = mock(AssetDao.class);
    browseNodes = mock(BrowseNodeDao.class);
    docker = mock(DockerRegistryDao.class);
    security = mock(SecurityManagementService.class);
    documents = mock(SecurityScanDocumentStore.class);
    documentPersistence = mock(SecurityScanDocumentPersistence.class);
    properties = new SecurityScanningProperties();
    properties.setEnabled(true);
    scope = mock(SecurityScanRepositoryScope.class);
    PermissionSubject subject =
        new PermissionSubject("test", "admin", Set.of("nx-admin"), null);
    actor = new AuthenticatedSubject("test", "admin", "local", null, subject);
    repository = repository(1L, "maven-hosted", RepositoryType.HOSTED);
    profile = profile();
    when(security.decide(eq(subject), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.allow());
    when(security.decide(eq(subject), anyString())).thenReturn(AccessDecision.allow());
    when(repositories.findById(1L)).thenReturn(Optional.of(repository));
    when(repositories.list()).thenReturn(List.of(repository));
    when(scans.listProfiles()).thenReturn(List.of(profile));
    when(scans.findRepositoryConfigs(any())).thenReturn(List.of());
    service = new SecurityScanManagementService(
        scans,
        repositories,
        assets,
        browseNodes,
        docker,
        security,
        documents,
        documentPersistence,
        properties,
        scope);
  }

  @ParameterizedTest
  @CsvSource(value = {
      "null, null, true, null, NO_EXPIRY",
      "null, 86400, true, 86400, POLICY",
      "604800, null, true, 604800, REPOSITORY",
      "604800, 86400, true, 86400, BOTH",
      "86400, 604800, true, 86400, BOTH",
      "86400, 86400, true, 86400, BOTH",
      "null, 86400, false, null, NO_EXPIRY",
      "604800, 86400, false, 604800, REPOSITORY"
  }, nullValues = "null")
  void repositoryPageResolvesRuntimeValidityWithoutChangingEditableConfig(
      Long repositoryAge, Long policyAge, boolean enabled, Long effectiveAge, String source) {
    RepositoryScanConfig config = new RepositoryScanConfig(
        1L, true, 1L, true, true, EnforcementMode.ENFORCE,
        PolicyAction.BLOCK, PolicyAction.ALLOW, PolicyAction.BLOCK,
        repositoryAge, 10L, 1, NOW, NOW);
    ScanPolicy policy = new ScanPolicy(
        10L, "critical", enabled, Severity.CRITICAL, false, false, false,
        policyAge, List.of(), 1, "admin", NOW, NOW);
    when(scans.findRepositoryConfigs(any())).thenReturn(List.of(config));
    when(scans.listPolicies()).thenReturn(List.of(policy));

    var view = service.repositoryPage(actor, null, 0, 10).items().getFirst();

    assertEquals(effectiveAge, view.resultValidity().maxResultAgeSeconds());
    assertEquals(source, view.resultValidity().source());
    assertEquals(enabled, view.policyEnabled());
    assertSame(config, view.config());
    assertEquals(repositoryAge, view.config().maxResultAgeSeconds());
    assertEquals(PolicyAction.BLOCK, view.config().partialAction());
    verify(scans, never()).findPolicy(anyLong());
  }

  @Test
  void repositoryViewWithoutAssignedPolicyUsesBaselineWithoutAnAgeLimit() {
    var view = service.repositoryViews(actor).getFirst();
    assertEquals(new SecurityScanResultValidity(null, "NO_EXPIRY"), view.resultValidity());
    assertTrue(view.policyEnabled());
  }

  @Test
  void aggregatesOverviewAndBuildsCursorPagesForRepositoriesTasksAndRuns() {
    RepositoryRecord proxy = repository(2L, "maven-proxy", RepositoryType.PROXY);
    when(repositories.list()).thenReturn(List.of(proxy, repository));
    RepositoryScanConfig config = config(1L, true, 1L, 10L);
    when(scans.findRepositoryConfig(1L)).thenReturn(Optional.of(config));
    when(scans.findRepositoryConfigs(any())).thenReturn(List.of(config));
    ScanPolicy policy = policy(10L, "critical", 1L);
    when(scans.listPolicies()).thenReturn(List.of(policy));
    when(scans.summary(List.of(1L, 2L)))
        .thenReturn(new ScanSummary(11, 11, 11, 11, 11, 11, 11, 11, 11, 11));

    var overview = service.overview(actor);
    var repositoriesPage = service.repositoryPage(actor, "maven", 0, 1);

    assertEquals(2, overview.visibleRepositories());
    assertEquals(11, overview.summary().candidateBacklog());
    assertFalse(overview.scannerStatus().ready());
    assertEquals("SNAPSHOT_UNAVAILABLE", overview.scannerStatus().reasonCode());
    assertEquals(1, repositoriesPage.items().size());
    assertNotNull(repositoriesPage.nextAfter());
    assertEquals("critical", service.repositoryViews(actor).stream()
        .filter(view -> view.id() == 1L).findFirst().orElseThrow().policyName());

    ScanTask task = mock(ScanTask.class);
    when(task.id()).thenReturn(7L);
    when(task.repositoryId()).thenReturn(1L);
    when(task.assetId()).thenReturn(20L);
    when(task.subjectKind()).thenReturn(SubjectKind.ASSET_BLOB);
    when(task.stage()).thenReturn(ScanStage.CATALOG_AND_MATCH);
    when(task.requestReason()).thenReturn(RequestReason.MANUAL);
    when(task.status()).thenReturn(TaskStatus.PENDING);
    when(task.maxAttempts()).thenReturn(5);
    when(scans.listTasks(1L, TaskStatus.PENDING, "demo", 0, 2))
        .thenReturn(List.of(task));
    var taskPage = service.taskPage(actor, 1L, TaskStatus.PENDING, "demo", 0, 1);
    assertEquals("maven-hosted", taskPage.items().getFirst().repository());

    ScanRun run = run(9L);
    when(scans.listRuns(1L, "demo", 0, 2)).thenReturn(List.of(run));
    var runPage = service.runPage(actor, 1L, "demo", 0, 1);
    assertEquals("COMPLETE", runPage.items().getFirst().status());
  }

  @Test
  void overviewDegradesAReadyScannerWhenItsSharedObservationIsStale() {
    SecurityScanDao.ScannerSnapshot snapshot = mock(SecurityScanDao.ScannerSnapshot.class);
    when(snapshot.ready()).thenReturn(true);
    when(snapshot.observedAt()).thenReturn(Instant.now().minusSeconds(600));
    when(snapshot.vulnerabilityDatabaseUpdatedAt()).thenReturn(Instant.now());
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(snapshot));

    var overview = service.overview(actor);

    assertFalse(overview.scannerStatus().ready());
    assertEquals("SCANNER_OBSERVATION_STALE", overview.scannerStatus().reasonCode());
  }

  @Test
  void overviewSeparatesLatestHealthObservationFromAuthoritativeMatchingDatabase() {
    Instant now = Instant.now();
    SecurityScanDao.ScannerSnapshot laggingObservation =
        mock(SecurityScanDao.ScannerSnapshot.class);
    when(laggingObservation.ready()).thenReturn(true);
    when(laggingObservation.observedAt()).thenReturn(now);
    when(laggingObservation.vulnerabilityDatabaseUpdatedAt())
        .thenReturn(now.minusSeconds(120));
    SecurityScanDao.ScannerSnapshot authoritative = mock(SecurityScanDao.ScannerSnapshot.class);
    when(authoritative.vulnerabilityDatabaseUpdatedAt()).thenReturn(now.minusSeconds(60));
    when(scans.latestScannerSnapshot()).thenReturn(Optional.of(laggingObservation));
    when(scans.latestReadyScannerSnapshot(any())).thenReturn(Optional.of(authoritative));

    var overview = service.overview(actor);

    assertSame(laggingObservation, overview.scanner());
    assertSame(authoritative, overview.matchingScanner());
    assertTrue(overview.scannerStatus().ready());
  }

  @Test
  void repositoryAndGlobalPagesUseBoundedVisibleRepositoryRelations() {
    List<RepositoryRecord> manyRepositories = java.util.stream.LongStream.rangeClosed(1, 300)
        .mapToObj(id -> repository(id, "repository-" + id, RepositoryType.HOSTED))
        .toList();
    when(repositories.list()).thenReturn(manyRepositories);

    var page = service.repositoryPage(actor, null, 0, 10);

    assertEquals(10, page.items().size());
    ArgumentCaptor<List<Long>> configScope = ArgumentCaptor.forClass(List.class);
    verify(scans).findRepositoryConfigs(configScope.capture());
    assertEquals(11, configScope.getValue().size());
    verify(scans, never()).findRepositoryConfig(anyLong());

    service.taskPage(actor, null, null, null, 0, 10);
    ArgumentCaptor<List<Long>> taskScope = ArgumentCaptor.forClass(List.class);
    verify(scans).listTasksByRepositories(
        taskScope.capture(),
        eq(null),
        eq(null),
        eq(0L),
        eq(11));
    assertEquals(300, taskScope.getValue().size());
    assertEquals(
        manyRepositories.stream()
            .map(RepositoryRecord::id)
            .collect(java.util.stream.Collectors.toSet()),
        Set.copyOf(taskScope.getValue()));
  }

  @Test
  void groupContextListsAndAdministersTasksStoredAgainstItsMemberSource() {
    RepositoryRecord group = repository(2L, "maven-group", RepositoryType.GROUP);
    when(repositories.list()).thenReturn(List.of(repository, group));
    when(repositories.findById(2L)).thenReturn(Optional.of(group));
    when(security.decide(
            eq(actor.permissionSubject()),
            argThat((RepositoryPermission permission) ->
                permission.repository().equals(repository.name()))))
        .thenReturn(AccessDecision.deny("source is reachable only through the group"));

    ScanTask task = mock(ScanTask.class);
    when(task.id()).thenReturn(77L);
    when(task.repositoryId()).thenReturn(1L);
    when(task.profileId()).thenReturn(1L);
    when(task.subjectKind()).thenReturn(SubjectKind.ASSET_BLOB);
    when(task.stage()).thenReturn(ScanStage.CATALOG_AND_MATCH);
    when(task.requestReason()).thenReturn(RequestReason.CONTENT_CHANGED);
    when(task.status()).thenReturn(TaskStatus.FAILED);
    when(task.maxAttempts()).thenReturn(5);
    when(scans.listTasksByRepositories(List.of(2L), null, null, 0, 11))
        .thenReturn(List.of(task));
    when(scans.findTask(77L)).thenReturn(Optional.of(task));
    when(scope.effectiveConfigsForSource(1L))
        .thenReturn(List.of(config(2L, true, 1L, null)));
    when(scans.requeueTask(eq(77L), any(), eq("admin"))).thenReturn(true);

    var page = service.taskPage(actor, null, null, null, 0, 10);
    assertEquals(1, page.items().size());
    assertEquals("maven-hosted", page.items().getFirst().repository());
    service.retry(actor, 77L);

    verify(scans).listTasksByRepositories(List.of(2L), null, null, 0, 11);
    verify(scans).requeueTask(eq(77L), any(), eq("admin"));
  }

  @Test
  void taskPageResolvesAssetsInBatchesAndPreservesPaginationAndMissingAssets() {
    ScanTask first = scanTask(7L, 20L);
    ScanTask deleted = scanTask(8L, 21L);
    ScanTask withoutAsset = scanTask(9L, null);
    ScanTask next = scanTask(10L, 20L);
    String path = "com/acme/demo/1.0/demo-1.0.jar";
    when(scans.listTasks(1L, null, null, 0, 4))
        .thenReturn(List.of(first, deleted, withoutAsset, next));
    when(assets.findAssetsByIds(List.of(20L, 21L)))
        .thenReturn(Map.of(20L, asset(20L, RepositoryFormat.MAVEN2, path, "artifact")));
    when(browseNodes.findPathsByAssetIds(List.of(20L, 21L)))
        .thenReturn(Map.of(20L, "projected/" + path));

    var page = service.taskPage(actor, 1L, null, null, 0, 3);

    assertEquals(3, page.items().size());
    assertEquals(9L, page.nextAfter());
    var resolved = page.items().getFirst();
    assertEquals(path, resolved.assetPath());
    assertEquals("projected/" + path, resolved.browsePath());
    assertEquals("maven-hosted", resolved.browseRepository());
    assertEquals(21L, page.items().get(1).assetId());
    assertNull(page.items().get(1).assetPath());
    assertNull(page.items().get(1).browseRepository());
    assertNull(page.items().get(2).assetId());
    verify(assets).findAssetsByIds(List.of(20L, 21L));
    verify(assets, never()).findAssetById(anyLong());
  }

  @ParameterizedTest
  @CsvSource({"true,true", "false,true", "true,false", "false,false"})
  void taskLinksUseVisibleGroupContextWhenSelectedOrSourceIsHidden(
      boolean selectGroup, boolean sourceVisible) {
    RepositoryRecord group = repository(2L, "maven-group", RepositoryType.GROUP);
    when(repositories.list()).thenReturn(List.of(repository, group));
    when(repositories.findById(2L)).thenReturn(Optional.of(group));
    when(scope.sourceRepositoryIds(2L)).thenReturn(List.of(1L));
    if (!sourceVisible) {
      when(security.decide(eq(actor.permissionSubject()),
          argThat((RepositoryPermission permission) ->
              permission.repository().equals(repository.name()))))
          .thenReturn(AccessDecision.deny("group access only"));
    }
    List<Long> visibleIds = selectGroup || !sourceVisible ? List.of(2L) : List.of(2L, 1L);
    ScanTask scanTask = scanTask(7L, 20L);
    when(scans.listTasksByRepositories(visibleIds, null, null, 0, 11))
        .thenReturn(List.of(scanTask));
    when(assets.findAssetsByIds(List.of(20L)))
        .thenReturn(Map.of(20L, asset(20L, RepositoryFormat.MAVEN2, "demo.jar", "artifact")));

    var task = service.taskPage(actor, selectGroup ? 2L : null, null, null, 0, 10)
        .items().getFirst();

    assertEquals("maven-hosted", task.repository());
    assertEquals(selectGroup || !sourceVisible ? "maven-group" : "maven-hosted",
        task.browseRepository());
    assertEquals("demo.jar", task.browsePath());
  }

  @Test
  void taskPageDoesNotResolveAnAssetFromAnUnrelatedRepository() {
    ScanTask scanTask = scanTask(7L, 20L);
    when(scans.listTasks(1L, null, null, 0, 11))
        .thenReturn(List.of(scanTask));
    AssetRecord unrelated = mock(AssetRecord.class);
    when(unrelated.repositoryId()).thenReturn(99L);
    when(assets.findAssetsByIds(List.of(20L))).thenReturn(Map.of(20L, unrelated));

    var task = service.taskPage(actor, 1L, null, null, 0, 10).items().getFirst();

    assertNull(task.assetPath());
    assertNull(task.browsePath());
    assertNull(task.browseRepository());
  }

  @Test
  void taskPageUsesLiveDockerManifestBrowsePath() {
    String digest = "sha256:" + "a".repeat(64);
    String storagePath = "docker/manifests/team/demo/sha256/" + "a".repeat(64);
    ScanTask scanTask = scanTask(7L, 20L);
    when(scans.listTasks(1L, null, null, 0, 11))
        .thenReturn(List.of(scanTask));
    when(assets.findAssetsByIds(List.of(20L)))
        .thenReturn(Map.of(20L, asset(20L, RepositoryFormat.DOCKER, storagePath, "manifest")));
    DockerManifestRecord manifest = mock(DockerManifestRecord.class);
    when(manifest.hasDigestReference()).thenReturn(true);
    when(manifest.imageName()).thenReturn("team/demo");
    when(manifest.digest()).thenReturn(digest);
    when(docker.findManifestsByAssetIds(List.of(20L))).thenReturn(Map.of(20L, manifest));

    var task = service.taskPage(actor, 1L, null, null, 0, 10).items().getFirst();

    assertEquals(storagePath, task.assetPath());
    assertEquals("team/demo/manifests/" + digest, task.browsePath());
  }

  private static ScanTask scanTask(long id, Long assetId) {
    ScanTask task = mock(ScanTask.class);
    when(task.id()).thenReturn(id);
    when(task.repositoryId()).thenReturn(1L);
    when(task.assetId()).thenReturn(assetId);
    when(task.subjectKind()).thenReturn(SubjectKind.ASSET_BLOB);
    when(task.stage()).thenReturn(ScanStage.CATALOG_AND_MATCH);
    when(task.requestReason()).thenReturn(RequestReason.MANUAL);
    when(task.status()).thenReturn(TaskStatus.PENDING);
    return task;
  }

  @Test
  void exposesAssetCreatesManualTaskAndControlsRetryAndCancel() {
    AssetRecord asset = asset(20L, RepositoryFormat.MAVEN2, "demo.jar", "artifact");
    AssetBlobRecord blob = blob();
    ScanCandidate candidate = new ScanCandidate(20L, 21L, 3, 3, NOW, NOW);
    when(assets.findAssetWithBlobById(20L))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(asset, blob)));
    when(scans.findCandidate(20L)).thenReturn(Optional.of(candidate));
    when(scans.listAssetStates(20L)).thenReturn(List.of(mock(AssetSecurityState.class)));
    when(scope.effectiveConfigsForSource(1L)).thenReturn(List.of(config(1L, true, 1L, null)));
    when(scans.findProfile(1L)).thenReturn(Optional.of(profile));
    when(scans.createTask(any())).thenReturn(77L);

    assertEquals("demo.jar", service.asset(actor, 20L).path());
    assertEquals(77L, service.rescan(actor, 20L));
    ArgumentCaptor<TaskDraft> draft = ArgumentCaptor.forClass(TaskDraft.class);
    verify(scans).createTask(draft.capture());
    assertEquals(RequestReason.MANUAL, draft.getValue().requestReason());
    assertEquals("sha256:" + "a".repeat(64), draft.getValue().subjectKey());

    ScanTask task = mock(ScanTask.class);
    when(task.repositoryId()).thenReturn(1L);
    ScanTask lockedTask = mock(ScanTask.class);
    when(lockedTask.id()).thenReturn(77L);
    when(lockedTask.repositoryId()).thenReturn(1L);
    when(lockedTask.status()).thenReturn(TaskStatus.RUNNING);
    when(lockedTask.attempts()).thenReturn(3);
    when(lockedTask.leaseToken()).thenReturn("current-lease");
    when(scans.findTask(77L)).thenReturn(Optional.of(task));
    when(scans.findTaskForUpdate(77L)).thenReturn(Optional.of(lockedTask));
    when(scans.requeueTask(eq(77L), any(), eq("admin"))).thenReturn(true);
    when(scans.cancelTask(eq(77L), any())).thenReturn(true);
    service.retry(actor, 77L);
    assertEquals("77:current-lease", service.cancel(actor, 77L));
    verify(scans).requeueTask(eq(77L), any(), eq("admin"));
    verify(scans).findTaskForUpdate(77L);
    verify(scans).cancelTask(eq(77L), any());
    verify(documentPersistence).releaseOwner(77L);
  }

  @Test
  void materializesMissingCandidateAndRejectsInvalidManualScans() {
    AssetRecord asset = asset(20L, RepositoryFormat.DOCKER, "v2/demo/manifests/latest", "manifest");
    AssetBlobRecord blob = blob();
    ScanCandidate candidate = new ScanCandidate(20L, 21L, 4, 4, NOW, NOW);
    when(assets.findAssetWithBlobById(20L))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(asset, blob)));
    when(scope.effectiveConfigsForSource(1L)).thenReturn(List.of(config(1L, true, 1L, null)));
    when(scans.findProfile(1L)).thenReturn(Optional.of(profile));
    when(scans.findCandidate(20L))
        .thenReturn(Optional.empty(), Optional.of(candidate));
    when(scans.createTask(any())).thenReturn(88L);

    assertEquals(88L, service.rescan(actor, 20L));
    verify(scans).markRepositoryAssetsForBackfill(1L, 19L, 1);
    ArgumentCaptor<TaskDraft> draft = ArgumentCaptor.forClass(TaskDraft.class);
    verify(scans).createTask(draft.capture());
    assertEquals(SubjectKind.OCI_MANIFEST, draft.getValue().subjectKind());

    when(assets.findAssetWithBlobById(21L)).thenReturn(Optional.empty());
    assertStatus(HttpStatus.NOT_FOUND, () -> service.rescan(actor, 21L));

    AssetRecord noBlobAsset = asset(22L, RepositoryFormat.MAVEN2, "empty.jar", "artifact");
    when(assets.findAssetWithBlobById(22L))
        .thenReturn(Optional.of(new AssetDao.AssetWithBlob(noBlobAsset, null)));
    assertStatus(HttpStatus.CONFLICT, () -> service.rescan(actor, 22L));
  }

  @ParameterizedTest
  @CsvSource({"false, 1", "true, 2"})
  void groupScanConfigChangesLeaveMemberContentForPolicyReconciliation(
      boolean previouslyEnabled, long previousProfileId) {
    RepositoryRecord group = repository(3L, "maven-group", RepositoryType.GROUP);
    when(repositories.findById(3L)).thenReturn(Optional.of(group));
    when(scans.findProfile(1L)).thenReturn(Optional.of(profile));
    when(scans.findRepositoryConfig(3L))
        .thenReturn(Optional.of(config(3L, previouslyEnabled, previousProfileId, null)));
    when(scans.upsertRepositoryConfig(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scope.sourceRepositoryIds(3L)).thenReturn(List.of(1L, 2L));
    when(scope.appliesToSource(any(), anyLong())).thenReturn(true);

    RepositoryScanConfig updated = service.updateRepositoryConfig(actor, 3L, new ConfigCommand(
        true, 1L, true, true, null, null, null, null, null, null));

    assertTrue(updated.enabled());
    assertEquals(2L, updated.configRevision());
    verify(scans, never()).createBackfillJob(anyLong(), anyString(), any());
  }

  @ParameterizedTest
  @EnumSource(value = RepositoryType.class, names = {"HOSTED", "PROXY"})
  void enablingSourceRepositoryScanningStillCreatesContentBackfill(RepositoryType type) {
    when(repositories.findById(1L)).thenReturn(Optional.of(repository(1L, "source", type)));
    when(scans.findProfile(1L)).thenReturn(Optional.of(profile));
    when(scans.findRepositoryConfig(1L)).thenReturn(Optional.of(config(1L, false, 1L, null)));
    when(scans.upsertRepositoryConfig(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scope.sourceRepositoryIds(1L)).thenReturn(List.of(1L));
    when(scope.appliesToSource(any(), eq(1L))).thenReturn(true);

    service.updateRepositoryConfig(actor, 1L, new ConfigCommand(
        true, 1L, true, true, null, null, null, null, null, null));

    verify(scans).createBackfillJob(eq(1L), eq("admin"), any());
  }

  @Test
  void updatesRepositoryConfigurationAndCreatesPolicyRevisions() {
    RepositoryScanConfig previous = config(1L, false, 1L, null);
    when(scans.findProfile(1L)).thenReturn(Optional.of(profile));
    when(scans.findPolicy(10L)).thenReturn(Optional.of(policy(10L, "critical", 1L)));
    when(scans.findRepositoryConfig(1L)).thenReturn(Optional.of(previous));
    when(scans.upsertRepositoryConfig(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scope.sourceRepositoryIds(1L)).thenReturn(List.of(1L, 2L));
    when(scope.appliesToSource(any(), anyLong())).thenReturn(true);
    ConfigCommand command = new ConfigCommand(
        true, 1L, true, true, null, null, PolicyAction.BLOCK, null, 0L, 10L);

    RepositoryScanConfig updated = service.updateRepositoryConfig(actor, 1L, command);

    assertEquals(EnforcementMode.AUDIT, updated.enforcementMode());
    assertEquals(1L, updated.maxResultAgeSeconds());
    verify(scans).createBackfillJob(eq(1L), eq("admin"), any());
    verify(scans).createBackfillJob(eq(2L), eq("admin"), any());

    ScanPolicy current = policy(10L, "critical", 1L);
    when(scans.listPolicies()).thenReturn(List.of(current));
    when(scans.createPolicyIfAbsent(any())).thenAnswer(invocation -> {
      ScanPolicy value = invocation.getArgument(0);
      return Optional.of(new ScanPolicy(
          value.revision() + 10, value.name(), value.enabled(), value.blockSeverity(),
          value.onlyFixable(), value.blockUnknownSeverity(), value.requireCompleteInventory(),
          value.maxResultAgeSeconds(), value.requiredPlatforms(), value.revision(),
          value.createdBy(), value.createdAt(), value.updatedAt()));
    });
    when(scans.createNextPolicyRevision(eq(10L), any())).thenAnswer(invocation -> {
      ScanPolicy value = invocation.getArgument(1);
      return Optional.of(new ScanPolicy(
          12L, value.name(), value.enabled(), value.blockSeverity(),
          value.onlyFixable(), value.blockUnknownSeverity(), value.requireCompleteInventory(),
          value.maxResultAgeSeconds(), value.requiredPlatforms(), 2,
          value.createdBy(), value.createdAt(), value.updatedAt()));
    });
    PolicyCommand create = new PolicyCommand(
        "audit", true, null, false, false, false, 0L, null);
    assertEquals(1L, service.createPolicy(actor, create).revision());

    when(scans.findPolicy(10L)).thenReturn(Optional.of(current));
    PolicyCommand revise = new PolicyCommand(
        "critical", true, Severity.HIGH, true, false, true, 3600L,
        List.of("linux/amd64"));
    ScanPolicy replacement = service.revisePolicy(actor, 10L, revise);
    assertEquals(2L, replacement.revision());
    verify(scans).replaceRepositoryPolicy(eq(10L), eq(replacement.id()), any());
  }

  @Test
  void validatesConfigurationPolicyMutationsAndSbomVisibility() throws Exception {
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> service.updateRepositoryConfig(actor, 1L, null));
    when(scans.findProfile(99L)).thenReturn(Optional.empty());
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> service.updateRepositoryConfig(
            actor, 1L, new ConfigCommand(
                true, 99L, true, true, null, null, null, null, null, null)));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.createPolicy(
        actor, new PolicyCommand(" ", true, null, false, false, false, null, null)));

    when(scans.createPolicyIfAbsent(any())).thenReturn(Optional.empty());
    assertStatus(HttpStatus.CONFLICT, () -> service.createPolicy(
        actor,
        new PolicyCommand("Duplicate", true, null, false, false, false, null, null)));

    Sbom sbom = new Sbom(
        30L, SubjectKind.ASSET_BLOB, "sha256:" + "a".repeat(64), new byte[32],
        "syft", "1", "config", "fingerprint", 31L, "b".repeat(64),
        "CycloneDX", "1.5", 1, 0, true, NOW);
    when(scans.findSbom(30L)).thenReturn(Optional.of(sbom));
    when(scans.listRepositoryIdsForSbom(30L)).thenReturn(List.of(1L));
    when(documents.open(31L)).thenReturn(new ByteArrayInputStream("{}".getBytes()));
    assertEquals(2, service.sbom(actor, 30L).input().readAllBytes().length);

    when(documents.open(31L)).thenThrow(new IOException("missing"));
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> service.sbom(actor, 30L));
  }

  @Test
  void coversConveniencePagesDefaultsAndMutationConflicts() {
    when(scans.listTasks(1L, null, 0, 2)).thenReturn(List.of());
    when(scans.listRuns(1L, 0, 2)).thenReturn(List.of());
    when(scans.listFindings(1L, null, null, 0, 2)).thenReturn(List.of());
    when(scans.listPolicies(null, 0, 2)).thenReturn(List.of());
    when(scans.listWaivers(1L, null, 0, 2)).thenReturn(List.of());

    assertEquals(0, service.tasks(actor, 1L, null, 0, 1).size());
    assertEquals(0, service.runs(actor, 1L, 0, 1).size());
    assertEquals(0, service.findings(actor, 1L, null, null, 0, 1).size());
    assertEquals(0, service.policies(actor).size());
    assertEquals(0, service.policyPage(actor, null, 0, 1).items().size());
    assertEquals(0, service.waivers(actor, 1L, 0, 1).size());

    when(scans.findRepositoryConfig(1L)).thenReturn(Optional.empty());
    assertEquals(1L, service.repositoryConfig(actor, 1L).profileId());

    ScanTask task = mock(ScanTask.class);
    when(task.repositoryId()).thenReturn(1L);
    when(scans.findTask(77L)).thenReturn(Optional.of(task));
    when(scans.findTaskForUpdate(77L)).thenReturn(Optional.of(task));
    assertStatus(HttpStatus.CONFLICT, () -> service.retry(actor, 77L));
    assertStatus(HttpStatus.CONFLICT, () -> service.cancel(actor, 77L));
  }

  @Test
  void rejectsDisabledProfilesUnknownPoliciesRenamesAndOversizedQueries() {
    ScanProfile disabled = new ScanProfile(
        2L, "disabled", false, "syft", "grype", List.of(), Map.of(),
        1024, 100, 4096, 1024, 2, 60, OciPlatformPolicy.REQUIRED_SET,
        List.of(), "d".repeat(64), 1, NOW, NOW);
    when(scans.findProfile(2L)).thenReturn(Optional.of(disabled));
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> service.updateRepositoryConfig(
            actor, 1L, new ConfigCommand(
                true, 2L, true, true, null, null, null, null, null, null)));

    when(scans.findProfile(1L)).thenReturn(Optional.of(profile));
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> service.updateRepositoryConfig(
            actor, 1L, new ConfigCommand(
                true, 1L, true, true, null, null, null, null, null, 404L)));

    ScanPolicy current = policy(10L, "critical", 1L);
    when(scans.findPolicy(10L)).thenReturn(Optional.of(current));
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> service.revisePolicy(
            actor,
            10L,
            new PolicyCommand(
                "renamed", true, Severity.HIGH, false, false, false, null, null)));

    when(scans.createNextPolicyRevision(eq(10L), any())).thenReturn(Optional.empty());
    assertStatus(
        HttpStatus.CONFLICT,
        () -> service.revisePolicy(
            actor,
            10L,
            new PolicyCommand(
                "critical", true, Severity.HIGH, false, false, false, null, null)));
    verify(scans, never()).replaceRepositoryPolicy(anyLong(), anyLong(), any());

    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> service.createPolicy(
            actor,
            new PolicyCommand(
                "p".repeat(129), true, null, false, false, false, null, null)));

    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> service.repositoryPage(actor, "x".repeat(201), 0, 10));
    assertEquals(0, service.repositoryPage(actor, "does-not-match", 0, 10).items().size());
  }

  @Test
  void enforcesGlobalWaiverAndRepositoryAdministrationPermissions() {
    PermissionSubject subject = actor.permissionSubject();
    when(security.decide(subject, "nexus:security-scanning:read"))
        .thenReturn(AccessDecision.deny("denied"));
    assertStatus(HttpStatus.FORBIDDEN, () -> service.policies(actor));

    when(security.decide(subject, "nexus:security-scanning:update"))
        .thenReturn(AccessDecision.deny("denied"));
    assertStatus(
        HttpStatus.FORBIDDEN,
        () -> service.createPolicy(
            actor,
            new PolicyCommand(
                "blocked", true, null, false, false, false, null, null)));

    when(security.decide(subject, "nexus:security-scanning-waivers:create"))
        .thenReturn(AccessDecision.deny("denied"));
    assertStatus(
        HttpStatus.FORBIDDEN,
        () -> service.findingWaiverContext(actor, 1L));

    ScanTask task = mock(ScanTask.class);
    when(task.repositoryId()).thenReturn(1L);
    when(scans.findTask(77L)).thenReturn(Optional.of(task));
    when(security.decide(eq(subject), any(RepositoryPermission.class)))
        .thenReturn(AccessDecision.deny("denied"));
    assertStatus(HttpStatus.FORBIDDEN, () -> service.retry(actor, 77L));
  }

  private static void assertStatus(HttpStatus status, Runnable invocation) {
    ResponseStatusException exception =
        assertThrows(ResponseStatusException.class, invocation::run);
    assertEquals(status, exception.getStatusCode());
  }

  private static RepositoryRecord repository(long id, String name, RepositoryType type) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.MAVEN2, type, "maven2-" + type.name().toLowerCase(),
        true, 1L, null, null, null, null, null, true, Map.of());
  }

  private static ScanProfile profile() {
    return new ScanProfile(
        1L, "default", true, "syft", "grype", List.of("vuln"), Map.of(),
        1024, 100, 4096, 1024, 2, 60, OciPlatformPolicy.REQUIRED_SET,
        List.of("linux/amd64"), "c".repeat(64), 1, NOW, NOW);
  }

  private static RepositoryScanConfig config(
      long repositoryId, boolean enabled, long profileId, Long policyId) {
    return new RepositoryScanConfig(
        repositoryId, enabled, profileId, true, true, EnforcementMode.AUDIT,
        PolicyAction.ALLOW, PolicyAction.ALLOW, PolicyAction.ALLOW,
        null, policyId, 1, NOW, NOW);
  }

  private static ScanPolicy policy(long id, String name, long revision) {
    return new ScanPolicy(
        id, name, true, Severity.CRITICAL, false, false, false,
        null, List.of(), revision, "admin", NOW, NOW);
  }

  private static AssetRecord asset(
      long id, RepositoryFormat format, String path, String kind) {
    return new AssetRecord(
        id, 1L, null, 21L, format, path, PersistenceHashes.pathHash(path),
        path.substring(path.lastIndexOf('/') + 1), kind, "application/octet-stream",
        8L, null, NOW, Map.of());
  }

  private static AssetBlobRecord blob() {
    return new AssetBlobRecord(
        21L, 1L, "blob://test/object", PersistenceHashes.blobRefHash("blob://test/object"),
        "object", PersistenceHashes.objectKeyHash("object"), "1".repeat(40), "a".repeat(64),
        "2".repeat(32), 8L, "application/octet-stream", "test", "127.0.0.1",
        NOW, NOW, Map.of());
  }

  private static ScanRun run(long id) {
    return new ScanRun(
        id, 7L, 30L, 40L, "config", "fingerprint",
        ScanState.COMPLETE, ScanCompleteness.COMPLETE, 31L, "b".repeat(64),
        1, 1, 1, 0, 0, 0, 0, Severity.CRITICAL, NOW, NOW, NOW);
  }
}
