package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetMutationObserver;
import com.github.klboke.kkrepo.persistence.jdbc.api.MavenSnapshotDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.maven.path.MavenSnapshotBuild;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/** Transactional, rebuildable inventory; metadata writes share the same cross-replica family lock. */
@Component
public class MavenSnapshotAssetObserver implements AssetMutationObserver {
  private final MavenSnapshotDao snapshots;
  private final RepositoryDao repositories;

  public MavenSnapshotAssetObserver(MavenSnapshotDao snapshots, RepositoryDao repositories) {
    this.snapshots = snapshots;
    this.repositories = repositories;
  }

  @Override
  public boolean supports(AssetRecord asset) {
    if (asset.format() != RepositoryFormat.MAVEN2 || asset.path() == null) return false;
    int slash = asset.path().lastIndexOf('/');
    return slash > 0 && asset.path().substring(0, slash).endsWith("-SNAPSHOT");
  }

  @Override
  public void beforeMutation(AssetRecord asset) {
    FamilyPath path = family(asset);
    if (path == null) return;
    snapshots.lockFamily(asset.repositoryId(), path.path(), path.groupId(),
        path.artifactId(), path.baseVersion());
  }

  /** Serialize before the writer reads an existing asset ID, including checksum-only writes. */
  @Override
  public void beforeWrite(long repositoryId, RepositoryFormat format, String assetPath) {
    if (format != RepositoryFormat.MAVEN2) return;
    FamilyPath path = familyPath(assetPath);
    if (path != null && repositories.findById(repositoryId)
        .filter(repository -> repository.type() == RepositoryType.HOSTED).isPresent()) {
      snapshots.lockFamily(repositoryId, path.path(), path.groupId(), path.artifactId(), path.baseVersion());
    }
  }

  @Override
  public void stored(AssetRecord asset) { index(asset, false); }

  public void index(AssetRecord asset, boolean backfill) {
    FamilyPath path = family(asset);
    if (path == null) return;
    MavenSnapshotBuild.parse(asset.path()).ifPresent(build -> {
      long familyId = snapshots.lockFamily(asset.repositoryId(), path.path(), path.groupId(),
          path.artifactId(), path.baseVersion());
      snapshots.store(familyId, asset.repositoryId(), asset.id(), build.version(), build.timestamp(),
          build.buildNumber(), build.variant(), build.primary(), asset.lastUpdatedAt(), backfill);
    });
  }

  @Override
  public void deleted(AssetRecord asset) {
    if (asset.format() == RepositoryFormat.MAVEN2) snapshots.remove(asset.id());
  }

  private FamilyPath family(AssetRecord asset) {
    if (asset.format() != RepositoryFormat.MAVEN2 || asset.path() == null) return null;
    FamilyPath path = familyPath(asset.path());
    return path != null && repositories.findById(asset.repositoryId())
        .filter(repository -> repository.type() == RepositoryType.HOSTED).isPresent() ? path : null;
  }

  private static FamilyPath familyPath(String assetPath) {
    if (assetPath == null) return null;
    String[] segments = assetPath.split("/");
    if (segments.length < 4 || !segments[segments.length - 2].endsWith("-SNAPSHOT")) return null;
    String groupId = String.join(".", Arrays.copyOf(segments, segments.length - 3));
    String artifactId = segments[segments.length - 3];
    String baseVersion = segments[segments.length - 2];
    return new FamilyPath(assetPath.substring(0, assetPath.lastIndexOf('/')),
        groupId, artifactId, baseVersion);
  }

  private record FamilyPath(String path, String groupId, String artifactId, String baseVersion) {}
}
