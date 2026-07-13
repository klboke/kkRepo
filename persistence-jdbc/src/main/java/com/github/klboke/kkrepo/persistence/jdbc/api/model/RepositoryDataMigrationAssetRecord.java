package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import java.time.Instant;
import java.util.Map;

public record RepositoryDataMigrationAssetRecord(
    Long id,
    long repositoryJobId,
    String sourceAssetId,
    String sourceComponentId,
    String sourcePath,
    byte[] sourcePathHash,
    RepositoryFormat format,
    String namespace,
    String name,
    String version,
    String assetKind,
    String contentType,
    Long size,
    String sourceBlobRef,
    Instant sourceLastUpdatedAt,
    Instant sourceLastDownloadedAt,
    Instant sourceBlobCreatedAt,
    Instant sourceBlobUpdatedAt,
    String sourceCreatedBy,
    String sourceCreatedByIp,
    String status,
    int attempts,
    Instant claimedAt,
    Instant migratedAt,
    Long targetComponentId,
    Long targetAssetId,
    Long targetAssetBlobId,
    String lastError,
    Map<String, Object> metadata,
    Instant discoveredAt) {
}
