package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SwiftBrowseServiceTest {

  @Test
  void movesSourceArchiveIntoReleaseDirectoryAndKeepsReleaseAsBranch() {
    RepositoryRecord group = repository(1L, "swift-group", RepositoryType.GROUP);
    RepositoryRecord stale = repository(2L, "swift-hosted", RepositoryType.HOSTED);
    RepositoryRecord source = repository(3L, "swift-proxy", RepositoryType.PROXY);
    String releasePath = "Alamofire/Alamofire/5.12.0";
    String archivePath = releasePath + ".zip";
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    AssetDao assets = mock(AssetDao.class);
    SwiftRegistryDao.Release release = release(source, "5.12.0");
    when(registry.findRelease(stale.id(), "alamofire", "alamofire", "5.12.0"))
        .thenReturn(Optional.empty());
    when(registry.findRelease(source.id(), "alamofire", "alamofire", "5.12.0"))
        .thenReturn(Optional.of(release));
    SwiftBrowseService service = new SwiftBrowseService(registry, assets);

    List<BrowseController.BrowseEntry> projected = service.project(
        group,
        List.of(stale, source),
        "Alamofire/Alamofire",
        List.of(
            entry("5.12.0", releasePath, stale.name(), false),
            entry("5.12.0.zip", archivePath, source.name(), true)));

    assertEquals(List.of("5.12.0"), projected.stream()
        .map(BrowseController.BrowseEntry::name)
        .toList());
    assertTrue(!projected.getFirst().leaf());
    assertEquals(source.name(), projected.getFirst().sourceRepository());
    assertEquals(null, projected.getFirst().downloadUrl());
  }

  @Test
  void listsReleaseMetadataArchiveAndBrowseOnlyManifestDirectory() {
    RepositoryRecord group = repository(1L, "swift-group", RepositoryType.GROUP);
    RepositoryRecord source = repository(2L, "swift-proxy", RepositoryType.PROXY);
    String releasePath = "Alamofire/Alamofire/5.12.0";
    AssetRecord archive = archive(source, releasePath + ".zip");
    SwiftRegistryDao.Release release = release(source, "5.12.0");
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    AssetDao assets = mock(AssetDao.class);
    when(registry.findRelease(source.id(), "alamofire", "alamofire", "5.12.0"))
        .thenReturn(Optional.of(release));
    when(registry.listManifests(release.id())).thenReturn(List.of(
        new SwiftRegistryDao.Manifest(release.id(), "Package@swift-6.2.swift", "6.2", 14L, "d"),
        new SwiftRegistryDao.Manifest(release.id(), "Package.swift", "", 12L, "b"),
        new SwiftRegistryDao.Manifest(release.id(), "Package@swift-6.0.swift", "6.0", 13L, "c"),
        new SwiftRegistryDao.Manifest(release.id(), "Package@swift-6.1.swift", "6.1", 15L, "e")));
    when(assets.findAssetById(release.archiveAssetId())).thenReturn(Optional.of(archive));
    SwiftBrowseService service = new SwiftBrowseService(registry, assets);

    List<BrowseController.BrowseEntry> releaseEntries = service.list(
        group, List.of(source), releasePath).orElseThrow();

    assertEquals(List.of("5.12.0", "5.12.0.zip", "swift_manifests"),
        releaseEntries.stream().map(BrowseController.BrowseEntry::name).toList());
    BrowseController.BrowseEntry metadata = releaseEntries.get(0);
    assertTrue(metadata.leaf());
    assertEquals(releasePath, metadata.path());
    assertEquals("/repository/swift-group/" + releasePath, metadata.downloadUrl());
    BrowseController.BrowseEntry archiveEntry = releaseEntries.get(1);
    assertTrue(archiveEntry.leaf());
    assertEquals(archive.path(), archiveEntry.path());
    assertEquals("/repository/swift-group/" + archive.path(), archiveEntry.downloadUrl());
    BrowseController.BrowseEntry manifestDirectory = releaseEntries.get(2);
    assertTrue(!manifestDirectory.leaf());
    assertEquals(releasePath + "/swift_manifests", manifestDirectory.path());
    assertEquals(null, manifestDirectory.downloadUrl());

    List<BrowseController.BrowseEntry> manifests = service.list(
        group, List.of(source), manifestDirectory.path()).orElseThrow();

    assertEquals(List.of(
        "Package.swift",
        "Package@swift-6.0.swift",
        "Package@swift-6.1.swift",
        "Package@swift-6.2.swift"),
        manifests.stream().map(BrowseController.BrowseEntry::name).toList());
    assertTrue(manifests.stream().allMatch(BrowseController.BrowseEntry::leaf));
    assertTrue(manifests.stream().allMatch(entry -> entry.downloadUrl() == null));
    assertTrue(manifests.stream().allMatch(entry -> entry.contentType().equals("text/x-swift")));
  }

  @Test
  void recognizesOnlyManifestEntriesAsBrowseOnlyPaths() {
    assertTrue(SwiftBrowseService.isVirtualManifestPath(
        "Alamofire/Alamofire/5.12.0/swift_manifests"));
    assertTrue(SwiftBrowseService.isVirtualManifestPath(
        "Alamofire/Alamofire/5.12.0/swift_manifests/Package@swift-6.0.swift"));
    assertTrue(!SwiftBrowseService.isVirtualManifestPath(
        "Alamofire/Alamofire/5.12.0/Package@swift-6.0.swift"));
  }

  private static BrowseController.BrowseEntry entry(
      String name,
      String path,
      String sourceRepository,
      boolean leaf) {
    return new BrowseController.BrowseEntry(
        name,
        path,
        sourceRepository,
        leaf,
        leaf ? 1024L : null,
        leaf ? "application/zip" : null,
        null,
        Instant.parse("2026-07-17T05:27:58Z"),
        leaf ? "/repository/swift-group/" + path : null);
  }

  private static AssetRecord archive(RepositoryRecord repository, String path) {
    return new AssetRecord(
        10L,
        repository.id(),
        null,
        100L,
        RepositoryFormat.SWIFT,
        path,
        PersistenceHashes.pathHash(path),
        "5.12.0.zip",
        "swift-source-archive",
        "application/zip",
        1024L,
        null,
        Instant.parse("2026-07-17T05:27:58Z"),
        Map.of());
  }

  private static SwiftRegistryDao.Release release(
      RepositoryRecord repository,
      String version) {
    Instant updatedAt = Instant.parse("2026-07-17T05:27:58Z");
    return new SwiftRegistryDao.Release(
        50L,
        repository.id(),
        20L,
        "alamofire",
        "Alamofire",
        "alamofire",
        "Alamofire",
        version,
        updatedAt,
        "{}",
        "a".repeat(64),
        10L,
        null,
        null,
        null,
        "GITHUB_PROXY",
        7L,
        SwiftRegistryDao.RELEASE_READY,
        updatedAt,
        updatedAt);
  }

  private static RepositoryRecord repository(
      long id,
      String name,
      RepositoryType type) {
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.SWIFT,
        type,
        "swift-" + type.name().toLowerCase(),
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }
}
