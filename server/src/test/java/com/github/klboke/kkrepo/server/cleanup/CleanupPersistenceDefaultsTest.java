package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.CleanupFamilyCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

class CleanupPersistenceDefaultsTest {
  private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

  @Test
  void assetDefaultsFilterAndBatchWithoutDuplicateLookups() {
    AssetDao dao = mock(AssetDao.class, Answers.CALLS_REAL_METHODS);
    AssetRecord unbound = asset(1, null, "one.bin");
    AssetRecord bound = asset(2, 20L, "two.bin");
    doReturn(Optional.of(unbound)).when(dao).findAssetById(1);
    doReturn(Optional.of(bound)).when(dao).findAssetById(2);
    doReturn(Optional.of(unbound)).when(dao).findAssetByPath(7, "one.bin");
    doReturn(Optional.of(bound)).when(dao).findAssetByPath(7, "two.bin");
    doReturn(List.of(unbound)).when(dao).listAssetsByComponent(10);
    doReturn(List.of(bound)).when(dao).listAssetsByComponent(20);
    doReturn(List.of(new AssetWithBlob(unbound, null), new AssetWithBlob(bound, null)))
        .when(dao).listAssetWithBlobPage(7, 0, 10);

    assertSame(unbound, dao.findAssetByIdForUpdate(1).orElseThrow());
    assertEquals(List.of(unbound), dao.listUnboundAssetWithBlobPage(7, 0, 10)
        .stream().map(AssetWithBlob::asset).toList());
    assertEquals(List.of(unbound, bound), dao.listAssetsByComponents(List.of(10L, 20L, 10L)));
    assertEquals(Map.of(1L, unbound, 2L, bound),
        dao.findAssetsByIds(java.util.Arrays.asList(1L, null, 2L, 1L)));
    assertEquals(Map.of("one.bin", unbound, "two.bin", bound),
        dao.findAssetsByPaths(
            7, java.util.Arrays.asList("one.bin", null, "two.bin", "one.bin")));
    assertEquals(List.of(bound), dao.listAssetsByComponentForUpdate(20));
    assertEquals(List.of(), dao.listAssetsByComponents(null));
    assertEquals(Map.of(), dao.findAssetsByIds(List.of()));
    assertEquals(Map.of(), dao.findAssetsByPaths(7, null));
  }

  @Test
  void componentDefaultsResumeOnlyAfterACompleteFamily() {
    ComponentDao dao = mock(ComponentDao.class, Answers.CALLS_REAL_METHODS);
    ComponentRecord alpha = component(1, null, "alpha", null);
    ComponentRecord beta = component(2, "scope", "beta", "kind");
    ComponentRecord gamma = component(3, "scope", "gamma", "kind");
    doReturn(Optional.of(beta)).when(dao).findById(2);
    doReturn(List.of(gamma, alpha, beta)).when(dao).listByRepositoryId(7);

    assertSame(beta, dao.findByIdForUpdate(2).orElseThrow());
    assertEquals(List.of(gamma, alpha), dao.listByRepositoryId(7, 2));
    assertEquals(List.of(alpha, beta, gamma), dao.listCleanupPage(7, null, 10));
    assertEquals(List.of(gamma), dao.listCleanupPage(
        7, new CleanupFamilyCursor("scope", "beta", "kind"), 10));
    assertEquals(List.of(alpha), dao.listCleanupPage(7, null, 1));
  }

  @Test
  void dockerDefaultsProduceStableBoundedPagesAndTagMaps() {
    DockerRegistryDao dao = mock(DockerRegistryDao.class, Answers.CALLS_REAL_METHODS);
    DockerManifestRecord manifest = mock(DockerManifestRecord.class);
    DockerTagRecord first = tag(1, 10, "latest");
    DockerTagRecord second = tag(2, 20, "stable");
    doReturn(Optional.of(manifest)).when(dao)
        .findManifestByDigest(7, "demo", "sha256:one");
    doReturn(List.of(first)).when(dao).listTagsForManifest(10);
    doReturn(List.of(second)).when(dao).listTagsForManifest(20);
    doReturn(List.of(
        candidate(5), candidate(2), candidate(8), candidate(1)))
        .when(dao).listManifestCleanupCandidates(7, false, null, null, 2);

    assertSame(manifest,
        dao.findManifestByDigestForUpdate(7, "demo", "sha256:one").orElseThrow());
    assertEquals(Map.of(10L, List.of(first), 20L, List.of(second)),
        dao.listTagsForManifests(java.util.Arrays.asList(10L, null, 20L, 10L)));
    assertEquals(List.of(first), dao.listTagsForManifestForUpdate(10));
    assertEquals(List.of(2L, 5L), dao.listManifestCleanupCandidatesPage(7, 1, 2)
        .stream().map(DockerRegistryDao.CleanupManifestCandidate::assetId).toList());
    assertEquals(Map.of(), dao.listTagsForManifests(null));

    DockerRegistryDao.CleanupManifestCandidate legacy =
        new DockerRegistryDao.CleanupManifestCandidate("demo", "sha256:legacy");
    assertEquals(0, legacy.assetId());
  }

  @Test
  void cleanupPolicyDefaultsProvideBoundedBatchFallbacks() {
    CleanupPolicyDao dao = mock(CleanupPolicyDao.class, Answers.CALLS_REAL_METHODS);
    CleanupPolicyDao.CleanupPolicy first = cleanupPolicy(1L);
    CleanupPolicyDao.CleanupPolicy second = cleanupPolicy(2L);
    CleanupPolicyDao.CleanupPolicy withoutId = cleanupPolicy(null);
    CleanupPolicyDao.TargetRepository target = new CleanupPolicyDao.TargetRepository(
        7, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, true);
    CleanupPolicyDao.CleanupSchedule schedule = new CleanupPolicyDao.CleanupSchedule(
        1, "0 0 2 * * ?", "UTC", true, NOW, NOW, NOW);
    doReturn(List.of(second, withoutId, first)).when(dao).listPolicies();
    doReturn(Optional.of(first)).when(dao).findPolicy(1);
    doReturn(Optional.empty()).when(dao).findPolicy(2);
    doReturn(List.of(target)).when(dao).listTargets(1);
    doReturn(List.of()).when(dao).listTargets(2);
    doReturn(Optional.of(schedule)).when(dao).findSchedule(1);
    doReturn(Optional.empty()).when(dao).findSchedule(2);

    assertEquals(List.of(first, second), dao.listPolicies(0, 2));
    assertEquals(List.of(first), dao.listPolicies(-1, 0));
    assertEquals(
        Map.of(1L, first),
        dao.findPolicies(java.util.Arrays.asList(1L, null, 2L, 1L)));
    assertEquals(Map.of(), dao.findPolicies(null));
    assertEquals(
        Map.of(1L, List.of(target), 2L, List.of()),
        dao.listTargets(java.util.Arrays.asList(1L, null, 2L, 1L)));
    assertEquals(Map.of(), dao.listTargets((java.util.Collection<Long>) null));
    assertTrue(dao.isPolicyTarget(1, 7));
    assertFalse(dao.isPolicyTarget(1, 8));
    assertEquals(
        Map.of(1L, schedule),
        dao.findSchedules(java.util.Arrays.asList(1L, null, 2L, 1L)));
    assertEquals(Map.of(), dao.findSchedules(List.of()));

    CleanupPolicyDao.CleanupRunRepository runRepository =
        mock(CleanupPolicyDao.CleanupRunRepository.class);
    CleanupPolicyDao.CleanupRunItem item = mock(CleanupPolicyDao.CleanupRunItem.class);
    doReturn(101L).when(runRepository).id();
    doReturn(List.of(runRepository)).when(dao).listRunRepositories(9);
    doReturn(List.of(item)).when(dao).listRunItems(101, 0, 1);

    dao.createRunRepositories(null);
    dao.createRunRepositories(List.of(runRepository));
    dao.upsertRunItems(null);
    dao.upsertRunItems(List.of(item));
    assertSame(runRepository, dao.findRunRepository(9, 101).orElseThrow());
    assertTrue(dao.findRunRepository(9, 999).isEmpty());
    assertEquals(
        Map.of(101L, List.of(item)),
        dao.listRunItems(java.util.Arrays.asList(101L, null), 0));
    assertEquals(Map.of(), dao.listRunItems((java.util.Collection<Long>) null, 10));
    assertEquals(Map.of(101L, List.of(item)), dao.listRunItemsByRun(9, 1));
    verify(dao).createRunRepository(runRepository);
    verify(dao).upsertRunItem(item);

    doReturn(4).when(dao).deleteTerminalRunsBefore(NOW, 3, 2);
    CleanupPolicyDao.CleanupHistoryPruneResult pruned =
        dao.pruneTerminalRunHistory(NOW, 3, 2, 100);
    assertEquals(4, pruned.deletedRuns());
    assertEquals(0, pruned.deletedRunItems());

    byte[] subjectHash = new byte[] {1};
    CleanupPolicyDao.CleanupProtection protection =
        mock(CleanupPolicyDao.CleanupProtection.class);
    CleanupPolicyDao.CleanupProtectionLookup lookup =
        new CleanupPolicyDao.CleanupProtectionLookup("one", "ASSET", "asset:1", subjectHash);
    doReturn(Optional.of(protection)).when(dao)
        .findActiveProtection(7, "ASSET", "asset:1", subjectHash, NOW);
    assertEquals(
        Map.of("one", protection),
        dao.findActiveProtections(7, List.of(lookup), NOW));
    assertEquals(Map.of(), dao.findActiveProtections(7, null, NOW));
  }

  @Test
  void cleanupCompatibilityConstructorsPreserveLegacyDefaults() {
    CleanupPolicyDao.CleanupRun run = new CleanupPolicyDao.CleanupRun(
        1L,
        7,
        2,
        "EXECUTE",
        "MANUAL",
        "SUCCEEDED",
        "admin",
        null,
        100,
        10,
        Map.of(),
        List.of(),
        4,
        3,
        2,
        1,
        0,
        null,
        NOW,
        NOW,
        NOW,
        NOW);
    assertFalse(run.cancelRequested());
    assertEquals(0, run.wouldDeleteSubjects());
    assertNull(run.cancelledAt());

    CleanupPolicyDao.CleanupRunRepository repository =
        new CleanupPolicyDao.CleanupRunRepository(
            2L,
            1,
            7,
            "raw",
            RepositoryFormat.RAW,
            RepositoryType.HOSTED,
            "SUCCEEDED",
            4,
            3,
            2,
            1,
            false,
            null,
            NOW,
            NOW,
            NOW,
            NOW);
    assertNull(repository.scanBudget());
    assertEquals(0, repository.attemptCount());
    assertEquals(3, repository.maxAttempts());
    assertEquals(NOW, repository.nextAttemptAt());
    assertEquals(0, repository.wouldDeleteSubjects());

    CleanupPolicyDao.CleanupRunItem item = new CleanupPolicyDao.CleanupRunItem(
        3L,
        2,
        "ASSET",
        "asset:1",
        new byte[] {1},
        "family",
        "name",
        "1.0",
        "name/1.0.bin",
        NOW,
        NOW,
        1,
        10,
        "DELETED",
        Map.of(),
        null,
        NOW,
        NOW);
    assertNull(item.expectedContentToken());
    assertEquals(0, item.expectedUsageRevision());
    assertNull(item.protectionId());
    assertNull(item.evaluatedAt());

    CleanupPolicyDao.ClaimedRunRepository claim =
        new CleanupPolicyDao.ClaimedRunRepository(
            2,
            1,
            7,
            "raw",
            RepositoryFormat.RAW,
            RepositoryType.HOSTED,
            "worker",
            "lease",
            3,
            1,
            3,
            NOW);
    assertFalse(claim.takeover());
    assertFalse(new CleanupPolicyDao.CleanupHistoryPruneResult(0, 0).workPerformed());
    assertTrue(new CleanupPolicyDao.CleanupHistoryPruneResult(0, 1).workPerformed());

    CleanupPolicyDao.CleanupUsage usage =
        new CleanupPolicyDao.CleanupUsage(3, 7, NOW, NOW, 2, NOW);
    assertEquals(3, usage.assetId());
    assertEquals(
        List.of(
            CleanupPolicyDao.CleanupUsageWriteOutcome.WRITTEN,
            CleanupPolicyDao.CleanupUsageWriteOutcome.COALESCED,
            CleanupPolicyDao.CleanupUsageWriteOutcome.NOT_TRACKED),
        List.of(CleanupPolicyDao.CleanupUsageWriteOutcome.values()));
    CleanupPolicyDao.CleanupProtection concreteProtection =
        new CleanupPolicyDao.CleanupProtection(
            4L,
            "REPOSITORY",
            7L,
            "ASSET",
            "asset:1",
            new byte[] {1},
            "MANUAL",
            null,
            "release",
            true,
            null,
            NOW,
            "admin",
            NOW,
            NOW);
    assertEquals("release", concreteProtection.reason());
  }

  private static AssetRecord asset(long id, Long componentId, String path) {
    return new AssetRecord(
        id, 7, componentId, id + 100, RepositoryFormat.RAW, path, new byte[] {(byte) id},
        path, "FILE", "application/octet-stream", 10L, null, NOW, Map.of());
  }

  private static ComponentRecord component(
      long id, String namespace, String name, String kind) {
    return new ComponentRecord(
        id, 7, RepositoryFormat.RAW, namespace, name, "1", kind,
        new byte[] {(byte) id}, Map.of(), NOW);
  }

  private static DockerTagRecord tag(long id, long manifestId, String name) {
    return new DockerTagRecord(
        id, 7, "demo", new byte[32], name, new byte[32], manifestId,
        "sha256:" + id, "admin", "127.0.0.1", NOW, NOW);
  }

  private static DockerRegistryDao.CleanupManifestCandidate candidate(long assetId) {
    return new DockerRegistryDao.CleanupManifestCandidate(
        "demo", "sha256:" + assetId, assetId, null, NOW, 10);
  }

  private static CleanupPolicyDao.CleanupPolicy cleanupPolicy(Long id) {
    return new CleanupPolicyDao.CleanupPolicy(
        id,
        "cleanup-" + id,
        RepositoryFormat.RAW,
        null,
        Map.of(),
        1,
        "ACTIVE",
        100,
        10,
        NOW,
        NOW);
  }
}
