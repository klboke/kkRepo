package com.github.klboke.kkrepo.persistence.jdbc.spi;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcOperations;

/** Batch persistence operations for resumable repository-data migration. */
public interface MigrationPersistenceDialect {
  void upsertDiscoveredAssets(JdbcOperations jdbc, List<DiscoveredAsset> assets);

  record DiscoveredAsset(
      long repositoryJobId,
      String sourceAssetId,
      String sourceComponentId,
      String sourcePath,
      byte[] sourcePathHash,
      String format,
      String namespace,
      String name,
      String version,
      String assetKind,
      String contentType,
      Long size,
      String sourceBlobRef,
      Timestamp sourceLastUpdatedAt,
      Timestamp sourceLastDownloadedAt,
      Timestamp sourceBlobCreatedAt,
      Timestamp sourceBlobUpdatedAt,
      String sourceCreatedBy,
      String sourceCreatedByIp,
      String status,
      Timestamp migratedAt,
      Long targetComponentId,
      Long targetAssetId,
      Long targetAssetBlobId,
      String metadataJson) {
  }
}
