package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.swift.SwiftVersions;
import com.github.klboke.kkrepo.protocol.swift.SwiftToolsVersions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Projects Swift's extracted manifest assets onto the logical tree shown by Nexus Browse.
 *
 * <p>Manifest bytes remain persisted because the registry endpoint serves them through the
 * {@code swift-version} query. They are not independent user-facing Browse files.
 */
@Component
final class SwiftBrowseService {
  private final SwiftRegistryDao registry;
  private final AssetDao assets;

  SwiftBrowseService(SwiftRegistryDao registry, AssetDao assets) {
    this.registry = registry;
    this.assets = assets;
  }

  Optional<List<BrowseController.BrowseEntry>> list(
      RepositoryRecord visibleRepository,
      List<RepositoryRecord> sources,
      String parent) {
    if (visibleRepository.format() != RepositoryFormat.SWIFT) {
      return Optional.empty();
    }
    if (isManifestDirectoryPath(parent)) {
      String releasePath = parent.substring(0, parent.length() - "/swift_manifests".length());
      return findRelease(sources, releasePath)
          .map(release -> manifestEntries(releasePath, release));
    }
    if (!isPackageVersionPath(parent)) {
      return Optional.empty();
    }
    return findRelease(sources, parent)
        .map(release -> releaseEntries(visibleRepository, parent, release));
  }

  List<BrowseController.BrowseEntry> project(
      RepositoryRecord visibleRepository,
      List<RepositoryRecord> sources,
      String parent,
      List<BrowseController.BrowseEntry> entries) {
    if (visibleRepository.format() != RepositoryFormat.SWIFT
        || !isPackagePath(parent)) {
      return entries;
    }
    List<BrowseController.BrowseEntry> projected = new ArrayList<>(entries.size());
    for (BrowseController.BrowseEntry entry : entries) {
      if (entry.leaf() && isSourceArchivePath(entry.path())) {
        String releasePath = entry.path().substring(0, entry.path().length() - ".zip".length());
        if (findRelease(sources, releasePath).isPresent()) {
          continue;
        }
      }
      if (!entry.leaf() && isPackageVersionPath(entry.path())) {
        Optional<ResolvedRelease> resolved = findRelease(sources, entry.path());
        if (resolved.isPresent()) {
          ResolvedRelease value = resolved.orElseThrow();
          projected.add(new BrowseController.BrowseEntry(
              entry.name(),
              entry.path(),
              value.source().name(),
              false,
              entry.size(),
              entry.contentType(),
              entry.sha1(),
              value.release().updatedAt(),
              null));
          continue;
        }
      }
      projected.add(entry);
    }
    return projected;
  }

  static boolean isVirtualManifestPath(String path) {
    String[] parts = split(path);
    if (parts.length != 4 && parts.length != 5) {
      return false;
    }
    if (!"swift_manifests".equals(parts[3])
        || !isPackageVersionPath(parts[0] + "/" + parts[1] + "/" + parts[2])) {
      return false;
    }
    return parts.length == 4
        || "Package.swift".equals(parts[4])
        || SwiftToolsVersions.fromManifestFilename(parts[4]).isPresent();
  }

  private List<BrowseController.BrowseEntry> releaseEntries(
      RepositoryRecord visibleRepository,
      String releasePath,
      ResolvedRelease resolved) {
    SwiftRegistryDao.Release release = resolved.release();
    List<BrowseController.BrowseEntry> entries = new ArrayList<>();
    entries.add(new BrowseController.BrowseEntry(
        release.version(),
        releasePath,
        resolved.source().name(),
        true,
        null,
        "text/plain",
        null,
        release.updatedAt(),
        BrowseDownloadUrls.asset(visibleRepository, releasePath)));
    AssetRecord archive = assets.findAssetById(release.archiveAssetId()).orElse(null);
    String archivePath = archive == null ? releasePath + ".zip" : archive.path();
    entries.add(new BrowseController.BrowseEntry(
        archive == null ? release.version() + ".zip" : archive.name(),
        archivePath,
        resolved.source().name(),
        true,
        archive == null ? null : archive.size(),
        archive == null ? "application/zip" : archive.contentType(),
        null,
        archive == null ? release.updatedAt() : archive.lastUpdatedAt(),
        BrowseDownloadUrls.asset(visibleRepository, archivePath)));
    entries.add(new BrowseController.BrowseEntry(
        "swift_manifests",
        releasePath + "/swift_manifests",
        resolved.source().name(),
        false,
        null,
        null,
        null,
        release.updatedAt(),
        null));
    return entries;
  }

  private List<BrowseController.BrowseEntry> manifestEntries(
      String releasePath,
      ResolvedRelease resolved) {
    return registry.listManifests(resolved.release().id()).stream()
        .sorted(Comparator.comparing(SwiftRegistryDao.Manifest::filename))
        .map(manifest -> new BrowseController.BrowseEntry(
            manifest.filename(),
            releasePath + "/swift_manifests/" + manifest.filename(),
            resolved.source().name(),
            true,
            null,
            "text/x-swift",
            null,
            resolved.release().updatedAt(),
            null))
        .toList();
  }

  private Optional<ResolvedRelease> findRelease(
      List<RepositoryRecord> sources,
      String path) {
    String[] parts = split(path);
    if (parts.length != 3 || !SwiftVersions.isValid(parts[2])) {
      return Optional.empty();
    }
    String scope = parts[0].toLowerCase(Locale.ROOT);
    String name = parts[1].toLowerCase(Locale.ROOT);
    for (RepositoryRecord source : sources) {
      Optional<SwiftRegistryDao.Release> release = registry.findRelease(
          source.id(), scope, name, parts[2]);
      if (release.isPresent()) {
        return Optional.of(new ResolvedRelease(source, release.orElseThrow()));
      }
    }
    return Optional.empty();
  }

  private static boolean isPackagePath(String path) {
    String[] parts = split(path);
    return parts.length == 2;
  }

  private static boolean isPackageVersionPath(String path) {
    String[] parts = split(path);
    return parts.length == 3 && SwiftVersions.isValid(parts[2]);
  }

  private static boolean isManifestDirectoryPath(String path) {
    String[] parts = split(path);
    return parts.length == 4
        && "swift_manifests".equals(parts[3])
        && isPackageVersionPath(parts[0] + "/" + parts[1] + "/" + parts[2]);
  }

  private static boolean isSourceArchivePath(String path) {
    return path != null
        && path.endsWith(".zip")
        && isPackageVersionPath(path.substring(0, path.length() - ".zip".length()));
  }

  private static String[] split(String path) {
    return path == null || path.isBlank() ? new String[0] : path.split("/");
  }

  private record ResolvedRelease(
      RepositoryRecord source,
      SwiftRegistryDao.Release release) {}
}
