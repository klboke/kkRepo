package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.CleanupFamilyCursor;
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
}
