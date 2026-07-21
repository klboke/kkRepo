package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class AnsibleGalaxyServiceLifecycleTest {
  private static final String BASE = "https://repo.example/repository/ansible/";
  private static final String SHA256 = "a".repeat(64);
  private static final String FILENAME = "acme-tools-1.2.3.tar.gz";

  @TempDir
  Path tempDir;

  private ObjectMapper mapper;
  private AnsibleGalaxyRegistryDao registry;
  private AnsibleGalaxyAssetSupport assets;
  private AnsibleCollectionArchiveInspector inspector;
  private HttpRemoteFetcher fetcher;
  private RepositoryRuntimeRegistry runtimes;
  private AnsibleGalaxyService service;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    registry = mock(AnsibleGalaxyRegistryDao.class);
    assets = mock(AnsibleGalaxyAssetSupport.class);
    inspector = mock(AnsibleCollectionArchiveInspector.class);
    fetcher = mock(HttpRemoteFetcher.class);
    runtimes = mock(RepositoryRuntimeRegistry.class);
    service = new AnsibleGalaxyService(mapper, registry, assets, inspector, fetcher, runtimes);
  }

  @Test
  void publishesAHostedCollectionThroughTheDurableTaskLifecycle() throws Exception {
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, null, List.of());
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspected("body");
    AnsibleGalaxyRegistryDao.CollectionVersion stored = version(hosted.id(), 31L, 21L);
    stubPersistence(hosted, inspected, stored);
    AssetRecord staged = asset(41L, hosted.id(), null, 51L, ".ansible/staging/task/" + FILENAME);
    when(assets.stageCollection(
        eq(hosted), anyString(), eq(FILENAME), eq(inspected.file()), eq("alice"), eq("127.0.0.1")))
        .thenReturn(staged);
    AtomicReference<AnsibleGalaxyRegistryDao.ImportTask> waiting = new AtomicReference<>();
    when(registry.createTask(any())).thenAnswer(invocation -> {
      AnsibleGalaxyRegistryDao.ImportTask task = invocation.getArgument(0);
      waiting.set(task);
      return task;
    });
    when(registry.claimTask(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
      AnsibleGalaxyRegistryDao.ImportTask task = waiting.get();
      return Optional.of(claimed(task));
    });
    when(registry.finishTask(
        anyString(), anyString(), anyLong(), anyString(), any(), any(), any(),
        any(), any(), any(), any(), any(), any())).thenReturn(true);

    MavenResponse response = service.publish(
        hosted, "api/v3/artifacts/collections/", null,
        new ByteArrayInputStream(new byte[] {1}), FILENAME, SHA256.toUpperCase(),
        "alice", "127.0.0.1", false);

    assertEquals(202, response.status());
    Map<String, Object> body = json(response);
    String taskPath = body.get("task").toString();
    assertTrue(taskPath.matches(
        "api/v3/imports/collections/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{4}-[0-9a-f]{12}/"));
    assertEquals(BASE + taskPath, URI.create(BASE).resolve(taskPath).toString());
    assertTrue(taskPath.contains(waiting.get().taskId()));
    String location = response.headers().get("Location").toString();
    assertEquals("../../imports/collections/" + waiting.get().taskId() + "/", location);
    assertEquals(
        BASE + taskPath,
        URI.create(BASE + "api/v3/artifacts/collections/").resolve(location).toString());
    verify(registry).finishTask(
        anyString(), anyString(), anyLong(), eq(AnsibleGalaxyRegistryDao.TASK_COMPLETED),
        any(), eq(null), eq(null), eq("acme"), eq("tools"), eq("1.2.3"),
        eq(FILENAME), eq(SHA256), any());
    verify(assets).delete(hosted, ".ansible/staging/" + waiting.get().taskId() + "/" + FILENAME);
    assertFalse(Files.exists(inspected.file()));

    ArgumentCaptor<AnsibleGalaxyRegistryDao.CollectionVersion> inserted =
        ArgumentCaptor.forClass(AnsibleGalaxyRegistryDao.CollectionVersion.class);
    verify(registry).insertVersion(inserted.capture());
    assertEquals("HOSTED", inserted.getValue().sourceKind());
    assertEquals(Map.of("acme.base", ">=1.0.0"), inserted.getValue().dependencies());
  }

  @Test
  void taskCreationFailureDeletesTheUnreferencedStagingAsset() throws Exception {
    RepositoryRuntime hosted = runtime(2L, RepositoryType.HOSTED, null, List.of());
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspected("task-failure");
    AssetRecord staged = asset(
        42L, hosted.id(), null, 52L, ".ansible/staging/task/" + FILENAME);
    when(inspector.inspect(any())).thenReturn(inspected);
    when(assets.stageCollection(
        eq(hosted), anyString(), eq(FILENAME), eq(inspected.file()), eq("alice"), eq(null)))
        .thenReturn(staged);
    when(registry.createTask(any())).thenThrow(new IllegalStateException("database unavailable"));

    assertThrows(IllegalStateException.class, () -> service.publish(
        hosted, "api/v3/artifacts/collections/", null,
        new ByteArrayInputStream(new byte[] {1}), FILENAME, SHA256,
        "alice", null, false));

    ArgumentCaptor<AnsibleGalaxyRegistryDao.ImportTask> task =
        ArgumentCaptor.forClass(AnsibleGalaxyRegistryDao.ImportTask.class);
    verify(registry).createTask(task.capture());
    verify(assets).delete(
        hosted, ".ansible/staging/" + task.getValue().taskId() + "/" + FILENAME);
    verify(registry, never()).claimTask(anyString(), anyString(), any(), any());
    assertFalse(Files.exists(inspected.file()));
  }

  @Test
  void concurrentDuplicatePublishMarksTheLosingImportTaskFailed() throws Exception {
    RepositoryRuntime hosted = runtime(2L, RepositoryType.HOSTED, null, List.of());
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspected("loser");
    AnsibleGalaxyRegistryDao.CollectionVersion winner = version(hosted.id(), 32L, 22L);
    AssetRecord staged = asset(
        41L, hosted.id(), null, 51L, ".ansible/staging/task/" + FILENAME);
    when(inspector.inspect(any())).thenReturn(inspected);
    when(assets.stageCollection(
        eq(hosted), anyString(), eq(FILENAME), eq(inspected.file()), eq("alice"), eq(null)))
        .thenReturn(staged);
    AtomicReference<AnsibleGalaxyRegistryDao.ImportTask> waiting = new AtomicReference<>();
    when(registry.createTask(any())).thenAnswer(invocation -> {
      AnsibleGalaxyRegistryDao.ImportTask task = invocation.getArgument(0);
      waiting.set(task);
      return task;
    });
    when(registry.claimTask(anyString(), anyString(), any(), any())).thenAnswer(
        invocation -> Optional.of(claimed(waiting.get())));
    when(registry.findVersion(hosted.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(registry.finishTask(
        anyString(), anyString(), anyLong(), anyString(), any(), any(), any(),
        any(), any(), any(), any(), any(), any())).thenReturn(true);

    MavenResponse response = service.publish(
        hosted, "api/v3/artifacts/collections/", null,
        new ByteArrayInputStream(new byte[] {1}), FILENAME, SHA256,
        "alice", null, false);

    assertEquals(202, response.status());
    verify(registry).finishTask(
        eq(waiting.get().taskId()), eq("owner"), eq(3L),
        eq(AnsibleGalaxyRegistryDao.TASK_FAILED), any(),
        eq("conflict.collection_exists"), anyString(), eq("acme"), eq("tools"), eq("1.2.3"),
        eq(FILENAME), eq(SHA256), any());
    verify(assets, never()).storeCollection(any(), any(), any(), any(), any(), any(), any());
    verify(assets).delete(
        hosted, ".ansible/staging/" + waiting.get().taskId() + "/" + FILENAME);
    assertFalse(Files.exists(inspected.file()));
  }

  @Test
  void supersededTaskOwnerPreservesStagingForTheNewOwner() throws Exception {
    RepositoryRuntime hosted = runtime(3L, RepositoryType.HOSTED, null, List.of());
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspected("superseded");
    AnsibleGalaxyRegistryDao.CollectionVersion stored = version(hosted.id(), 34L, 24L);
    stubPersistence(hosted, inspected, stored);
    AssetRecord staged = asset(
        43L, hosted.id(), null, 53L, ".ansible/staging/task/" + FILENAME);
    when(assets.stageCollection(
        eq(hosted), anyString(), eq(FILENAME), eq(inspected.file()), eq("alice"), eq(null)))
        .thenReturn(staged);
    AtomicReference<AnsibleGalaxyRegistryDao.ImportTask> waiting = new AtomicReference<>();
    when(registry.createTask(any())).thenAnswer(invocation -> {
      AnsibleGalaxyRegistryDao.ImportTask task = invocation.getArgument(0);
      waiting.set(task);
      return task;
    });
    when(registry.claimTask(anyString(), anyString(), any(), any())).thenAnswer(
        invocation -> Optional.of(claimed(waiting.get())));
    when(registry.finishTask(
        anyString(), anyString(), anyLong(), anyString(), any(), any(), any(),
        any(), any(), any(), any(), any(), any())).thenReturn(false);

    assertEquals(202, service.publish(
        hosted, "api/v3/artifacts/collections/", null,
        new ByteArrayInputStream(new byte[] {1}), FILENAME, SHA256,
        "alice", null, false).status());

    verify(registry).finishTask(
        eq(waiting.get().taskId()), eq("owner"), eq(3L),
        eq(AnsibleGalaxyRegistryDao.TASK_COMPLETED), any(), eq(null), eq(null),
        eq("acme"), eq("tools"), eq("1.2.3"), eq(FILENAME), eq(SHA256), any());
    verify(registry, never()).finishTask(
        anyString(), anyString(), anyLong(), eq(AnsibleGalaxyRegistryDao.TASK_FAILED),
        any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(assets, never()).delete(
        hosted, ".ansible/staging/" + waiting.get().taskId() + "/" + FILENAME);
    assertFalse(Files.exists(inspected.file()));
  }

  @Test
  void stalePublisherReusesButNeverDeletesAConcurrentWinnerAsset() throws Exception {
    RepositoryRuntime hosted = runtime(4L, RepositoryType.HOSTED, null, List.of());
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspected("stale");
    AssetRecord winnerAsset = asset(
        42L, hosted.id(), 12L, 52L,
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME);
    AnsibleGalaxyRegistryDao.CollectionVersion winner = version(hosted.id(), 33L, winnerAsset.id());
    when(inspector.inspect(any())).thenReturn(inspected);
    when(registry.findVersion(hosted.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
    when(registry.tryAcquireLease(anyString(), anyString(), any())).thenReturn(Optional.of(
        new AnsibleGalaxyRegistryDao.Lease(
            "lease", "owner", 4L, Instant.now().plusSeconds(60), Instant.now())));
    when(assets.storeCollection(
        eq(hosted), anyString(), eq(inspected.file()), any(), any(), any(), any()))
        .thenReturn(new AnsibleGalaxyAssetSupport.StoredCollection(winnerAsset, false));
    when(assets.requiredBlob(winnerAsset))
        .thenReturn(blob(52L, inspected.size(), inspected.sha256()));
    when(registry.nextRepositoryRevision(hosted.id())).thenReturn(5L);
    when(registry.insertVersion(any())).thenThrow(new DuplicateKeyException("winner"));

    assertThrows(AnsibleGalaxyExceptions.Conflict.class, () -> service.putArtifact(
        hosted,
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME,
        new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1"));

    verify(assets, never()).delete(
        hosted, "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME);
    assertFalse(Files.exists(inspected.file()));
  }

  @Test
  void supportsNexusStyleArtifactPutAndMigrationRestore() throws Exception {
    RepositoryRuntime hosted = runtime(5L, RepositoryType.HOSTED, null, List.of());
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspected("put");
    AnsibleGalaxyRegistryDao.CollectionVersion stored = version(hosted.id(), 32L, 22L);
    stubPersistence(hosted, inspected, stored);

    MavenResponse response = service.putArtifact(
        hosted,
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME,
        new ByteArrayInputStream(new byte[] {1}), "nexus", "127.0.0.1");

    assertEquals(201, response.status());
    assertFalse(Files.exists(inspected.file()));

    AnsibleCollectionArchiveInspector.InspectedCollection duplicate = inspected("duplicate");
    when(registry.findVersion(hosted.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(stored));
    assertSame(stored, service.restoreCollectionForMigration(
        hosted, duplicate, Instant.EPOCH, "migration", "127.0.0.1"));
    AnsibleCollectionArchiveInspector.delete(duplicate.file());

    RepositoryRuntime group = runtime(6L, RepositoryType.GROUP, null, List.of(hosted));
    assertThrows(IllegalArgumentException.class, () -> service.restoreCollectionForMigration(
        group, inspected("group"), Instant.EPOCH, "migration", null));
  }

  @Test
  void validatesPublishAndPutInputsBeforeWriting() throws Exception {
    RepositoryRuntime hosted = runtime(4L, RepositoryType.HOSTED, null, List.of());
    assertThrows(AnsibleGalaxyExceptions.BadRequest.class, () -> service.publish(
        hosted, "api/v3/artifacts/collections/", "unexpected=1",
        new ByteArrayInputStream(new byte[0]), FILENAME, SHA256, "alice", null, false));
    assertThrows(AnsibleGalaxyExceptions.MethodNotAllowed.class, () -> service.publish(
        hosted, "api/v3/collections/acme/tools/", null,
        new ByteArrayInputStream(new byte[0]), FILENAME, SHA256, "alice", null, false));
    assertThrows(AnsibleGalaxyExceptions.BadRequest.class, () -> service.publish(
        hosted, "api/v3/artifacts/collections/", null,
        new ByteArrayInputStream(new byte[0]), FILENAME, "bad", "alice", null, false));
    assertThrows(AnsibleGalaxyExceptions.MethodNotAllowed.class, () -> service.putArtifact(
        hosted, "api/v3/collections/acme/tools/", new ByteArrayInputStream(new byte[0]),
        "alice", null));

    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspected("bad-name");
    when(inspector.inspect(any())).thenReturn(inspected);
    assertThrows(AnsibleGalaxyExceptions.BadRequest.class, () -> service.publish(
        hosted, "api/v3/artifacts/collections/", null,
        new ByteArrayInputStream(new byte[0]), "wrong.tar.gz", SHA256, "alice", null, false));
    assertFalse(Files.exists(inspected.file()));

    AnsibleCollectionArchiveInspector.InspectedCollection duplicate = inspected("duplicate");
    when(inspector.inspect(any())).thenReturn(duplicate);
    when(registry.findVersion(hosted.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(version(hosted.id(), 33L, 23L)));
    assertThrows(AnsibleGalaxyExceptions.Conflict.class, () -> service.publish(
        hosted, "api/v3/artifacts/collections/", null,
        new ByteArrayInputStream(new byte[0]), FILENAME, SHA256, "alice", null, false));
    assertFalse(Files.exists(duplicate.file()));
    verify(assets, never()).storeCollection(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void servesDiscoveryCollectionTaskAndArtifactRoutes() throws Exception {
    RepositoryRuntime hosted = runtime(5L, RepositoryType.HOSTED, null, List.of());
    AnsibleGalaxyRegistryDao.CollectionVersion version = version(hosted.id(), 34L, 24L);
    when(registry.listVersionNames(hosted.id(), "acme", "tools"))
        .thenReturn(List.of("1.2.3"));
    when(registry.findVersion(hosted.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(version));
    when(registry.listSignatures(version.id())).thenReturn(List.of());
    when(registry.findVersionByArtifactFilename(hosted.id(), FILENAME))
        .thenReturn(Optional.of(version));
    when(assets.serve(hosted.id(), version.artifactAssetId(), true))
        .thenReturn(MavenResponse.noBody(200, 4L, "application/octet-stream", SHA256, Instant.EPOCH));

    assertEquals("kkrepo", json(service.get(hosted, "api/", null, BASE, false, "alice"))
        .get("server_version"));
    Map<String, Object> collection = json(service.get(
        hosted, "api/v3/collections/acme/tools/", null, BASE, false, "alice"));
    assertEquals("1.2.3", map(collection.get("highest_version")).get("version"));
    Map<String, Object> versions = json(service.get(
        hosted, "api/v3/collections/acme/tools/versions/", null, BASE, false, "alice"));
    assertEquals("", map(versions.get("links")).get("next"));
    MavenResponse artifact = service.get(
        hosted,
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME,
        null, BASE, true, "alice");
    assertEquals(200, artifact.status());
    assertTrue(artifact.headers().get("Content-Disposition").toString().contains(FILENAME));

    String taskId = "0dfd1f0d-fa14-4caa-b928-be3ec7c8650e";
    when(registry.findTask(taskId)).thenReturn(Optional.of(task(
        taskId, hosted.id(), AnsibleGalaxyRegistryDao.TASK_FAILED, null, null, 24L)));
    Map<String, Object> failed = json(service.get(
        hosted, "api/v3/imports/collections/" + taskId + "/", null,
        BASE, false, "alice"));
    assertEquals("UNKNOWN", map(failed.get("error")).get("code"));
    assertEquals("no-store", failedHeader(service.get(
        hosted, "api/v3/imports/collections/" + taskId + "/", null,
        BASE, true, "alice"), "Cache-Control"));
  }

  @Test
  void reportsMissingAndInvalidGetRoutesConsistently() {
    RepositoryRuntime hosted = runtime(6L, RepositoryType.HOSTED, null, List.of());
    assertThrows(AnsibleGalaxyExceptions.BadRequest.class, () -> service.get(
        hosted, "api/v3/collections/acme/tools/versions/", "limit=1&limit=2",
        BASE, false, "alice"));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service.get(
        hosted, "api/v3/artifacts/collections/", null, BASE, false, "alice"));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service.get(
        hosted, "unknown", null, BASE, false, "alice"));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service.get(
        hosted, "api/v3/collections/acme/tools/", null, BASE, false, "alice"));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service.get(
        hosted, "api/v3/collections/acme/tools/versions/", null, BASE, false, "alice"));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service.get(
        hosted, "api/v3/collections/acme/tools/versions/1.2.3/", null,
        BASE, false, "alice"));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service.get(
        hosted,
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME,
        null, BASE, false, "alice"));
  }

  @Test
  void recoverySkipsInvalidCandidatesAndRecordsMissingStagingArtifacts() {
    AnsibleGalaxyRegistryDao.ImportTask candidate = task(
        "task-1", 9L, AnsibleGalaxyRegistryDao.TASK_WAITING, null, null, null);
    service.recoverTask(candidate);
    verify(registry, never()).claimTask(anyString(), anyString(), any(), any());

    RepositoryRuntime proxy = runtime(9L, RepositoryType.PROXY, "https://galaxy.example/", List.of());
    when(runtimes.resolveById(9L)).thenReturn(Optional.of(proxy));
    service.recoverTask(candidate);
    verify(registry, never()).claimTask(anyString(), anyString(), any(), any());

    RepositoryRuntime hosted = runtime(9L, RepositoryType.HOSTED, null, List.of());
    when(runtimes.resolveById(9L)).thenReturn(Optional.of(hosted));
    when(registry.claimTask(eq("task-1"), anyString(), any(), any()))
        .thenReturn(Optional.empty(), Optional.of(task(
            "task-1", 9L, AnsibleGalaxyRegistryDao.TASK_RUNNING, "owner", 7L, null)));
    service.recoverTask(candidate);
    service.recoverTask(candidate);
    verify(registry).finishTask(
        eq("task-1"), eq("owner"), eq(7L), eq(AnsibleGalaxyRegistryDao.TASK_FAILED),
        any(), eq("missing_staging_artifact"), anyString(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void recoveryFailsClosedOnIdentityDriftAndCleansTheStagingPath() throws Exception {
    RepositoryRuntime hosted = runtime(10L, RepositoryType.HOSTED, null, List.of());
    AnsibleGalaxyRegistryDao.ImportTask candidate = task(
        "task-2", hosted.id(), AnsibleGalaxyRegistryDao.TASK_WAITING, null, null, 99L);
    AnsibleGalaxyRegistryDao.ImportTask claimed = new AnsibleGalaxyRegistryDao.ImportTask(
        candidate.taskId(), candidate.repositoryId(), candidate.requester(),
        AnsibleGalaxyRegistryDao.TASK_RUNNING, List.of(), null, null,
        "different", candidate.nameLc(), candidate.versionNormalized(), candidate.artifactFilename(),
        candidate.expectedSha256(), candidate.actualSha256(), candidate.stagingAssetId(), 1,
        "owner", Instant.now().plusSeconds(60), 8L, Instant.now(), Instant.now(), null, Instant.now());
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspected("recover");
    when(runtimes.resolveById(hosted.id())).thenReturn(Optional.of(hosted));
    when(registry.claimTask(eq(candidate.taskId()), anyString(), any(), any()))
        .thenReturn(Optional.of(claimed));
    when(assets.open(hosted.id(), 99L)).thenReturn(new ByteArrayInputStream(new byte[] {1}));
    when(inspector.inspect(any())).thenReturn(inspected);
    when(registry.finishTask(
        anyString(), anyString(), anyLong(), anyString(), any(), any(), any(),
        any(), any(), any(), any(), any(), any())).thenReturn(true);

    service.recoverTask(candidate);

    verify(registry).finishTask(
        eq(candidate.taskId()), eq("owner"), eq(8L), eq(AnsibleGalaxyRegistryDao.TASK_FAILED),
        any(), eq("invalid"), anyString(), any(), any(), any(), any(), any(), any());
    verify(assets).delete(hosted, ".ansible/staging/" + candidate.taskId() + "/" + FILENAME);
    assertFalse(Files.exists(inspected.file()));
  }

  @Test
  void recoveryCompletesAValidDurableImportAfterReplicaRestart() throws Exception {
    RepositoryRuntime hosted = runtime(11L, RepositoryType.HOSTED, null, List.of());
    AnsibleGalaxyRegistryDao.ImportTask candidate = task(
        "task-3", hosted.id(), AnsibleGalaxyRegistryDao.TASK_WAITING, null, null, 101L);
    AnsibleGalaxyRegistryDao.ImportTask claimed = claimed(candidate);
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspected("recovered");
    AnsibleGalaxyRegistryDao.CollectionVersion stored = version(hosted.id(), 60L, 61L);
    stubPersistence(hosted, inspected, stored);
    when(runtimes.resolveById(hosted.id())).thenReturn(Optional.of(hosted));
    when(registry.claimTask(eq(candidate.taskId()), anyString(), any(), any()))
        .thenReturn(Optional.of(claimed));
    when(assets.open(hosted.id(), 101L)).thenReturn(new ByteArrayInputStream(new byte[] {1}));
    when(registry.finishTask(
        anyString(), anyString(), anyLong(), anyString(), any(), any(), any(),
        any(), any(), any(), any(), any(), any())).thenReturn(true);

    service.recoverTask(candidate);

    verify(registry).finishTask(
        eq(candidate.taskId()), eq("owner"), eq(3L),
        eq(AnsibleGalaxyRegistryDao.TASK_COMPLETED), any(), eq(null), eq(null),
        eq("acme"), eq("tools"), eq("1.2.3"), eq(FILENAME), eq(SHA256), any());
    verify(assets).delete(hosted, ".ansible/staging/" + candidate.taskId() + "/" + FILENAME);
  }

  @Test
  void groupReusesCurrentBindingsAndFallsBackToOrderedMemberArtifacts() throws Exception {
    RepositoryRuntime member = runtime(12L, RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(13L, RepositoryType.GROUP, null, List.of(member));
    AnsibleGalaxyRegistryDao.CollectionVersion version = version(member.id(), 70L, 71L);
    AnsibleGalaxyRegistryDao.GroupBinding binding = new AnsibleGalaxyRegistryDao.GroupBinding(
        group.id(), "acme", "tools", "1.2.3", member.id(), version.id(), 5L, 7L,
        SHA256, Instant.now(), Instant.now());
    when(registry.listVersionNames(member.id(), "acme", "tools")).thenReturn(List.of("1.2.3"));
    when(registry.currentRepositoryRevision(group.id())).thenReturn(7L);
    when(registry.currentRepositoryRevision(member.id())).thenReturn(5L);
    when(registry.findGroupBinding(group.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(binding));
    when(registry.findVersionById(version.id())).thenReturn(Optional.of(version));
    when(registry.listSignatures(version.id())).thenReturn(List.of());

    Map<String, Object> collection = json(service.get(
        group, "api/v3/collections/acme/tools/", null, BASE, false, "alice"));
    assertEquals("1.2.3", map(collection.get("highest_version")).get("version"));

    when(registry.findGroupBindingByArtifactFilename(group.id(), FILENAME))
        .thenReturn(Optional.empty());
    when(registry.findVersionByArtifactFilename(member.id(), FILENAME))
        .thenReturn(Optional.of(version));
    when(assets.serve(member.id(), version.artifactAssetId(), false)).thenReturn(MavenResponse.ok(
        new ByteArrayInputStream(new byte[] {1}), 1L,
        "application/octet-stream", SHA256, Instant.EPOCH));
    assertEquals(200, service.get(
        group,
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME,
        null, BASE, false, "alice").status());
    verify(registry).bindGroupSourceIfCurrent(any());
  }

  @Test
  void groupArtifactResolutionReturnsTheConcurrentBindingWinner() throws Exception {
    RepositoryRuntime first = runtime(14L, RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime second = runtime(15L, RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(16L, RepositoryType.GROUP, null, List.of(first, second));
    AnsibleGalaxyRegistryDao.CollectionVersion candidate = version(first.id(), 80L, 81L);
    AnsibleGalaxyRegistryDao.CollectionVersion winner = version(second.id(), 82L, 83L);
    AnsibleGalaxyRegistryDao.GroupBinding winningBinding =
        new AnsibleGalaxyRegistryDao.GroupBinding(
            group.id(), "acme", "tools", "1.2.3", second.id(), winner.id(), 6L, 9L,
            winner.artifactSha256(), Instant.now(), Instant.now());
    when(registry.currentRepositoryRevision(group.id())).thenReturn(9L);
    when(registry.currentRepositoryRevision(first.id())).thenReturn(5L);
    when(registry.findGroupBindingByArtifactFilename(group.id(), FILENAME))
        .thenReturn(Optional.empty());
    when(registry.findVersionByArtifactFilename(first.id(), FILENAME))
        .thenReturn(Optional.of(candidate));
    when(registry.bindGroupSourceIfCurrent(any())).thenReturn(true);
    when(registry.findGroupBinding(group.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(winningBinding));
    when(registry.findVersionById(winner.id())).thenReturn(Optional.of(winner));
    when(assets.serve(second.id(), winner.artifactAssetId(), false)).thenReturn(MavenResponse.ok(
        new ByteArrayInputStream(new byte[] {2}), 1L,
        "application/octet-stream", winner.artifactSha256(), Instant.EPOCH));

    assertEquals(200, service.get(
        group,
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME,
        null, BASE, false, "alice").status());

    verify(assets).serve(second.id(), winner.artifactAssetId(), false);
    verify(assets, never()).serve(first.id(), candidate.artifactAssetId(), false);
  }

  @Test
  void groupAndProxyResolutionFailClosedWithoutAMemberOrRequestedProxyMetadata() {
    RepositoryRuntime emptyGroup = runtime(14L, RepositoryType.GROUP, null, List.of());
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service.get(
        emptyGroup, "api/v3/collections/acme/tools/versions/1.2.3/",
        null, BASE, false, "alice"));

    RepositoryRuntime cycle = mock(RepositoryRuntime.class);
    when(cycle.id()).thenReturn(15L);
    when(cycle.format()).thenReturn(RepositoryFormat.ANSIBLEGALAXY);
    when(cycle.online()).thenReturn(true);
    when(cycle.isHosted()).thenReturn(false);
    when(cycle.isProxy()).thenReturn(false);
    when(cycle.members()).thenReturn(List.of(cycle));
    assertThrows(AnsibleGalaxyExceptions.BadRequest.class, () -> service.get(
        cycle, "api/v3/collections/acme/tools/versions/", null,
        BASE, false, "alice"));

    RepositoryRuntime proxy = runtime(
        16L, RepositoryType.PROXY, "https://galaxy.example/", List.of());
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service.get(
        proxy,
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME,
        null, BASE, false, "alice"));
  }

  @Test
  void proxyVersionListingKeepsMaterializedVersionsDuringUpstreamNotFound() throws Exception {
    RepositoryRuntime proxy = runtime(
        17L, RepositoryType.PROXY, "https://galaxy.example/", List.of());
    AnsibleGalaxyService resilient = spy(service);
    when(registry.listVersionNames(proxy.id(), "acme", "tools"))
        .thenReturn(List.of("1.2.3"));
    doThrow(new AnsibleGalaxyExceptions.NotFound("gone"))
        .when(resilient).fetchProxyVersionNames(proxy, "acme", "tools");

    Map<String, Object> page = json(resilient.get(
        proxy, "api/v3/collections/acme/tools/versions/", null,
        BASE, false, "alice"));

    assertEquals(1, map(page.get("meta")).get("count"));
  }

  private void stubPersistence(
      RepositoryRuntime runtime,
      AnsibleCollectionArchiveInspector.InspectedCollection inspected,
      AnsibleGalaxyRegistryDao.CollectionVersion stored) {
    when(inspector.inspect(any())).thenReturn(inspected);
    when(registry.findVersion(runtime.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.tryAcquireLease(anyString(), anyString(), any())).thenReturn(Optional.of(
        new AnsibleGalaxyRegistryDao.Lease(
            "lease", "owner", 3L, Instant.now().plusSeconds(60), Instant.now())));
    AssetRecord asset = asset(21L, runtime.id(), 11L, 22L,
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME);
    when(assets.storeCollection(
        eq(runtime), anyString(), eq(inspected.file()), any(), any(), any(), any()))
        .thenReturn(new AnsibleGalaxyAssetSupport.StoredCollection(asset, true));
    when(assets.requiredBlob(asset)).thenReturn(blob(22L, inspected.size(), inspected.sha256()));
    when(registry.nextRepositoryRevision(runtime.id())).thenReturn(4L);
    when(registry.insertVersion(any())).thenReturn(stored);
  }

  private AnsibleCollectionArchiveInspector.InspectedCollection inspected(String value)
      throws Exception {
    Path file = Files.writeString(tempDir.resolve(value + "-" + System.nanoTime() + ".tar.gz"), value);
    return new AnsibleCollectionArchiveInspector.InspectedCollection(
        file, Files.size(file), SHA256, FILENAME, "acme", "tools", "1.2.3",
        Map.of("description", "fixture", "authors", List.of("kkRepo")),
        Map.of("acme.base", ">=1.0.0"), ">=2.15");
  }

  private static AnsibleGalaxyRegistryDao.ImportTask claimed(
      AnsibleGalaxyRegistryDao.ImportTask waiting) {
    return new AnsibleGalaxyRegistryDao.ImportTask(
        waiting.taskId(), waiting.repositoryId(), waiting.requester(),
        AnsibleGalaxyRegistryDao.TASK_RUNNING, waiting.messages(), null, null,
        waiting.namespaceLc(), waiting.nameLc(), waiting.versionNormalized(),
        waiting.artifactFilename(), waiting.expectedSha256(), waiting.actualSha256(),
        waiting.stagingAssetId(), 1, "owner", Instant.now().plusSeconds(60), 3L,
        waiting.createdAt(), Instant.now(), null, Instant.now());
  }

  private static AnsibleGalaxyRegistryDao.ImportTask task(
      String taskId, long repositoryId, String state, String owner, Long fencingToken,
      Long stagingAssetId) {
    Instant now = Instant.parse("2026-07-21T08:00:00Z");
    return new AnsibleGalaxyRegistryDao.ImportTask(
        taskId, repositoryId, "alice", state, List.of(), null, null,
        "acme", "tools", "1.2.3", FILENAME, SHA256, SHA256, stagingAssetId, 1,
        owner, owner == null ? null : now.plusSeconds(60), fencingToken == null ? 0L : fencingToken,
        now, now, state.equals(AnsibleGalaxyRegistryDao.TASK_WAITING) ? null : now, now);
  }

  private static AnsibleGalaxyRegistryDao.CollectionVersion version(
      long repositoryId, long id, long assetId) {
    Instant now = Instant.parse("2026-07-21T08:00:00Z");
    return new AnsibleGalaxyRegistryDao.CollectionVersion(
        id, repositoryId, 11L, assetId,
        "acme", "acme", "tools", "tools", "1.2.3", "1.2.3",
        FILENAME, SHA256, 4L,
        Map.of("description", "fixture", "authors", List.of("kkRepo")),
        Map.of("acme.base", ">=1.0.0"), ">=2.15", "HOSTED", 4L,
        AnsibleGalaxyRegistryDao.VERSION_READY, now, now, now);
  }

  private static AssetRecord asset(
      long id, long repositoryId, Long componentId, Long blobId, String path) {
    return new AssetRecord(
        id, repositoryId, componentId, blobId, RepositoryFormat.ANSIBLEGALAXY,
        path, new byte[32], FILENAME, "ansible-collection", "application/octet-stream",
        4L, null, Instant.now(), Map.of());
  }

  private static AssetBlobRecord blob(long id, long size, String sha256) {
    return new AssetBlobRecord(
        id, 1L, "blob", new byte[32], "object", new byte[32], null, sha256, null,
        size, "application/octet-stream", "alice", "127.0.0.1",
        Instant.now(), Instant.now(), Map.of());
  }

  private static RepositoryRuntime runtime(
      long id, RepositoryType type, String remote, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, "ansible-" + id, RepositoryFormat.ANSIBLEGALAXY, type,
        "ansiblegalaxy-" + type.name().toLowerCase(), true, 1L, "ALLOW_ONCE",
        null, null, true, remote, 60, 60, members);
  }

  private Map<String, Object> json(MavenResponse response) throws Exception {
    try (var body = response.body()) {
      return mapper.readValue(body, new TypeReference<>() { });
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static Object failedHeader(MavenResponse response, String name) {
    return response.headers().get(name);
  }
}
