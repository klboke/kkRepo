package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationJobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationRepositoryRecord;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface RepositoryDataMigrationDao {
  String REPOSITORY_DISCOVERING = "discovering";
  String REPOSITORY_READY = "ready";
  String REPOSITORY_MIGRATING = "migrating";
  String REPOSITORY_FINISHED = "finished";
  String REPOSITORY_FINISHED_WITH_FAILURES = "finished_with_failures";

  String ASSET_PENDING = "pending";
  String ASSET_MIGRATING = "migrating";
  String ASSET_MIGRATED = "migrated";
  String ASSET_FAILED = "failed";

  long createRepositoryJob(
      long migrationJobId,
      String sourceRepositoryName,
      String targetRepositoryName,
      long targetRepositoryId,
      RepositoryFormat format,
      int pageSize,
      Map<String, Object> options);

  List<RepositoryDataMigrationRepositoryRecord> listRepositories(long migrationJobId);

  Optional<RepositoryDataMigrationRepositoryRecord> findRepositoryJob(long repositoryJobId);

  Optional<RepositoryDataMigrationRepositoryRecord> claimRepositoryForDiscovery(Instant retryBefore);

  Optional<RepositoryDataMigrationRepositoryRecord> claimRepositoryForDiscovery(
      Long migrationJobId,
      Instant retryBefore);

  Map<ByteBuffer, TargetAssetRef> findTargetAssetsByPathHash(
      long targetRepositoryId,
      Collection<byte[]> pathHashes);

  void upsertDiscoveredAssets(
      long repositoryJobId,
      List<RepositoryDataMigrationAssetRecord> assets,
      Map<ByteBuffer, TargetAssetRef> existingTargets);

  void finishDiscoveryPage(long repositoryJobId, String nextCursor, boolean complete);

  void markDiscoveryFailure(long repositoryJobId, String error);

  List<AssetClaim> claimAssetsForMigration(int limit, int maxAttempts, Instant retryBefore);

  List<AssetClaim> claimAssetsForMigration(Long migrationJobId, int limit, int maxAttempts, Instant retryBefore);

  void markAssetMigrated(long assetId, long repositoryJobId,
      Long targetComponentId, Long targetAssetId, Long targetAssetBlobId);

  void markAssetFailed(long assetId, long repositoryJobId, int maxAttempts, String error);

  int retryFailedAssets(long migrationJobId);

  void refreshRepositoryProgress(long repositoryJobId);

  MigrationJobProgress jobProgress(long migrationJobId);

  void updateMigrationJobSummary(long migrationJobId, String status, Map<String, Object> summary);

  void setPackageMigrationEnabled(long migrationJobId, boolean enabled);

  List<MigrationJobRecord> listRepositoryDataJobs(int limit);

  record TargetAssetRef(
      Long componentId,
      long assetId,
      Long assetBlobId) {
  }

  record AssetClaim(
      RepositoryDataMigrationAssetRecord asset,
      long migrationJobId,
      String sourceRepositoryName,
      String targetRepositoryName,
      long targetRepositoryId,
      RepositoryFormat repositoryFormat,
      String sourceBaseUrl,
      Map<String, Object> jobOptions) {
  }

  record MigrationJobProgress(
      int repositories,
      long discoveredAssets,
      long totalAssets,
      long migratedAssets,
      long failedAssets,
      long pendingAssets,
      boolean active,
      boolean failedRepositories) {
  }
}
