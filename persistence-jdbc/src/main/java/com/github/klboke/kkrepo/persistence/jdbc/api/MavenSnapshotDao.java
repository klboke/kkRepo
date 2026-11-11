package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Rebuildable snapshot inventory. Asset rows remain authoritative. All mutations are transactional. */
public interface MavenSnapshotDao {
  long lockFamily(long repositoryId, String path, String groupId, String artifactId, String baseVersion);
  Optional<Family> findFamily(long id);
  void store(long familyId, long repositoryId, long assetId, String version, long timestamp,
      int buildNumber, String variant, boolean primary, Instant modifiedAt, boolean backfill);
  void remove(long assetId);
  Optional<Build> findBuild(long buildId);
  List<Build> page(long repositoryId, long afterBuildId, int limit);
  Optional<Build> retentionBoundary(long familyId, int count);
  List<Long> assetIds(long buildId, int limit);
  boolean hasLatestVariant(long buildId);
  List<Long> backfillPage(long afterAssetId, int limit);
  List<Long> reconciliationPage(long afterBuildId, int limit);
  void reconcile(long buildId);
  boolean tryLockBlob(long blobId);
  boolean tryLockComponent(long componentId);

  record Family(long id, long repositoryId, String path, String groupId, String artifactId,
      String baseVersion) {}
  record Build(long id, long familyId, long repositoryId, String version, long timestamp,
      int buildNumber, long revision, int assetCount, int primaryCount, Instant mutatedAt) {}
}
