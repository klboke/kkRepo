package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.core.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DockerRetentionTest {
  private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
  private final DockerRegistryDao docker = mock(DockerRegistryDao.class);
  private final AssetDao assets = mock(AssetDao.class);
  private final Map<Long, DockerManifestRecord> manifests = new LinkedHashMap<>();
  private final Map<Long, List<DockerTagRecord>> tags = new LinkedHashMap<>();
  private final CleanupSubjectScanner scanner = new CleanupSubjectScanner(
      mock(ComponentDao.class), assets, docker, new CleanupPolicyCapabilities(), null, null, null);
  private final RepositoryRecord repository = new RepositoryRecord(1L, "images", RepositoryFormat.DOCKER,
      RepositoryType.HOSTED, "docker-hosted", true, 1L, null, null, null, null, "ALLOW", false, Map.of());

  @BeforeEach
  void fixture() {
    when(docker.findManifestsByAssetIds(any())).thenReturn(manifests);
    when(docker.listTagsForManifests(any())).thenReturn(tags);
    when(docker.listManifestCleanupCandidatesPage(eq(1L), anyLong(), anyInt())).thenAnswer(call -> {
      long after = call.getArgument(1);
      int limit = call.getArgument(2);
      return manifests.values().stream().filter(m -> m.assetId() > after).limit(limit)
          .map(m -> new DockerRegistryDao.CleanupManifestCandidate(m.imageName(), m.digest(),
              m.assetId(), OLD, m.updatedAt(), m.size())).toList();
    });
  }

  @Test
  void dockerCapabilityUsesManifestAgeWithoutInventingAVersionComparator() {
    var capabilities = new CleanupPolicyCapabilities();
    assertTrue(capabilities.supportsRetainCount(RepositoryFormat.DOCKER));
    assertTrue(capabilities.versionComparator(RepositoryFormat.DOCKER).isEmpty());
    assertTrue(capabilities.all().stream().filter(c -> c.format() == RepositoryFormat.DOCKER)
        .findFirst().orElseThrow().retainCountSupported());
  }

  @Test
  void retentionBoundaryCoversTheWholeImageEvenWhenNewestIsOutsideThePage() {
    add(1, "team/api", OLD, "z-old", "alias");
    add(2, "team/api", OLD.plusSeconds(2), "a-new");
    when(docker.findCleanupRetentionBoundary(1, "team/api", 1)).thenReturn(Optional.of(
        new DockerRegistryDao.CleanupRetentionBoundary(2, OLD.plusSeconds(2))));
    var result = scanner.scan(repository, Map.of("retainCount", 1), 1, NOW);
    assertTrue(result.truncated());
    assertEquals(List.of(1L), result.candidates().stream().map(c -> c.subject().identityId()).toList());
    assertEquals(1L, result.nextCursor().subjectId());
  }

  @Test
  void eachImageHasItsOwnMinimumAndAgeCriteriaStillApply() {
    add(1, "team/api", OLD, "v1");
    add(2, "team/api", NOW.minusSeconds(60), "v2");
    add(3, "team/api", NOW, "v3");
    add(4, "team/web", OLD, "v1");
    when(docker.findCleanupRetentionBoundary(1, "team/api", 1)).thenReturn(Optional.of(
        new DockerRegistryDao.CleanupRetentionBoundary(3, NOW)));
    when(docker.findCleanupRetentionBoundary(1, "team/web", 1)).thenReturn(Optional.of(
        new DockerRegistryDao.CleanupRetentionBoundary(4, OLD)));
    var result = scanner.scan(repository, Map.of("retainCount", 1, "publishedOlderThanDays", 30), 20, NOW);
    assertEquals(List.of(1L), result.candidates().stream().map(c -> c.subject().identityId()).toList());
  }

  @Test
  void fewerThanNPreservesEveryTaggedManifestAndTimestampTiesAreStable() {
    add(1, "team/api", OLD, "v1");
    add(2, "team/api", OLD, "v2");
    assertTrue(scanner.scan(repository, Map.of("retainCount", 3), 20, NOW).candidates().isEmpty());
    when(docker.findCleanupRetentionBoundary(1, "team/api", 1)).thenReturn(Optional.of(
        new DockerRegistryDao.CleanupRetentionBoundary(2, OLD)));
    assertEquals(1L, scanner.scan(repository, Map.of("retainCount", 1), 20, NOW)
        .candidates().getFirst().subject().identityId());
    assertEquals(2, scanner.scan(repository, Map.of("retainCount", 0), 20, NOW).candidates().size());
  }

  @Test
  void liveParentsProtectChildrenAndCountAloneDoesNotRemoveUntaggedUploads() {
    add(1, "team/api", OLD);
    assertTrue(scanner.scan(repository, Map.of("retainCount", 1), 20, NOW).candidates().isEmpty());
    var criteria = Map.<String, Object>of("retainCount", 1, "publishedOlderThanDays", 30);
    assertEquals(1, scanner.scan(repository, criteria, 20, NOW).candidates().size());
    when(docker.hasLiveManifestReference(1, "sha256:1")).thenReturn(true);
    assertTrue(scanner.scan(repository, criteria, 20, NOW).candidates().isEmpty());
    assertTrue(scanner.scan(repository, Map.of("publishedOlderThanDays", 30), 20, NOW).candidates().isEmpty());
  }

  @Test
  void finalRevalidationObservesNewParentsAndChangesToOtherVersions() {
    var manifest = add(1, "team/api", OLD, "v1");
    when(docker.findManifestByDigest(1, "team/api", "sha256:1")).thenReturn(Optional.of(manifest));
    when(docker.listTagsForManifest(1)).thenReturn(tags.get(1L));
    var subject = new CleanupSubjectScanner.Subject(1, "DOCKER_MANIFEST", "key", new byte[0],
        "team/api", "team/api", "team/api@sha256:1", "sha256:1", "team/api@sha256:1", OLD, OLD, 1, 10);
    var criteria = Map.<String, Object>of("retainCount", 1);
    when(docker.findCleanupRetentionBoundary(1, "team/api", 1)).thenReturn(Optional.of(
        new DockerRegistryDao.CleanupRetentionBoundary(2, OLD.plusSeconds(1))));
    assertNull(scanner.revalidateForDeletion(repository, criteria, subject, NOW));
    // A different replica removed the newer image after preview: this image now fills the minimum.
    when(docker.findCleanupRetentionBoundary(1, "team/api", 1)).thenReturn(Optional.of(
        new DockerRegistryDao.CleanupRetentionBoundary(1, OLD)));
    assertEquals("newest Docker manifests retained", scanner.revalidateForDeletion(repository, criteria, subject, NOW));
    when(docker.hasLiveManifestReference(1, "sha256:1")).thenReturn(true);
    assertEquals("referenced by a live Docker manifest", scanner.revalidateForDeletion(repository, criteria, subject, NOW));
    scanner.lockPackageRoots(repository, List.of(subject.deletePath()));
    verify(docker).lockRepositoryForCleanup(1);
  }

  private DockerManifestRecord add(long id, String image, Instant updated, String... names) {
    var manifest = new DockerManifestRecord(id, 1, image, new byte[32], "sha256", "sha256:" + id,
        new byte[32], "application/vnd.oci.image.manifest.v1+json", null, null, null,
        id, 10, "admin", null, null, Map.of(), OLD, updated);
    manifests.put(id, manifest);
    tags.put(id, Arrays.stream(names).map(name -> new DockerTagRecord(id, 1, image, new byte[32],
        name, new byte[32], id, manifest.digest(), "admin", null, updated, updated)).toList());
    return manifest;
  }
}
