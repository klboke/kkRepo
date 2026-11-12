package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Deletes only a locked, revalidated historical build; current metadata remains valid byte-for-byte. */
@Service
public class MavenSnapshotDeletionService {
  private final MavenSnapshotDao snapshots;
  private final AssetDao assets;
  private final BrowseNodeDao browse;
  private final ComponentDao components;
  private final AssetMetadataCache cache;
  private final RepositoryRuntimeRegistry runtimes;
  private final MavenGroupMetadataInvalidator groups;

  public MavenSnapshotDeletionService(MavenSnapshotDao snapshots, AssetDao assets, BrowseNodeDao browse,
      ComponentDao components, AssetMetadataCache cache, RepositoryRuntimeRegistry runtimes,
      MavenGroupMetadataInvalidator groups) {
    this.snapshots = snapshots; this.assets = assets; this.browse = browse; this.components = components;
    this.cache = cache; this.runtimes = runtimes; this.groups = groups;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public int delete(String repository, long buildId) {
    var runtime = runtimes.resolve(repository).orElseThrow();
    var build = snapshots.findBuild(buildId).orElse(null);
    if (build == null) return 0;
    if (build.repositoryId() != runtime.id()) throw new IllegalArgumentException("snapshot repository mismatch");
    var family = snapshots.findFamily(build.familyId()).orElseThrow();
    snapshots.lockFamily(runtime.id(), family.path(), family.groupId(), family.artifactId(), family.baseVersion());
    List<Long> ids = snapshots.assetIds(buildId, 4097);
    if (ids.size() > 4096) throw new IllegalStateException("snapshot build exceeds bounded delete size");
    List<AssetRecord> rows = assets.findAssetsByIdsForUpdate(ids).values().stream()
        .sorted(java.util.Comparator.comparing(AssetRecord::id)).toList();
    List<Long> blobIds = rows.stream().map(AssetRecord::assetBlobId).filter(Objects::nonNull).distinct().sorted().toList();
    // Uploads may hold a shared blob before their family lock. Never wait in the inverse order.
    for (long blobId : blobIds) {
      if (!snapshots.tryLockBlob(blobId)) throw new IllegalStateException("snapshot blob is busy; retry cleanup");
    }
    List<Long> componentIds = rows.stream().map(AssetRecord::componentId).filter(Objects::nonNull)
        .distinct().sorted().toList();
    // Imported/native writers can share a base-version component with surviving builds.
    // Check every component, even when it will not be pruned.
    for (long componentId : componentIds) {
      if (!snapshots.tryLockComponent(componentId)) throw new IllegalStateException("snapshot component is busy; retry cleanup");
    }
    List<Long> emptyComponents = componentIds.stream()
        .filter(id -> !assets.hasComponentAssetsOutside(id, ids)).toList();
    for (AssetRecord row : rows) {
      browse.deleteByAssetId(row.id());
      assets.deleteAssetById(row.id());
      cache.evictAfterCommit(row.repositoryId(), row.path());
    }
    for (long blobId : blobIds) assets.markBlobDeletedIfUnreferenced(blobId, "snapshot build cleanup");
    // Base-version components normally retain assets. Imported timestamp components may become empty.
    emptyComponents.forEach(components::deleteIfNoAssets);
    if (!rows.isEmpty()) groups.memberAssetDeleted(runtime, new MavenPathParser().parsePath(rows.getFirst().path()).main());
    return rows.size();
  }
}
