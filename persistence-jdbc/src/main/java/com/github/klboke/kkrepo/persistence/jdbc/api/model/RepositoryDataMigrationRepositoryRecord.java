package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import java.time.Instant;
import java.util.Map;

public record RepositoryDataMigrationRepositoryRecord(
    Long id,
    long migrationJobId,
    String sourceRepositoryName,
    String targetRepositoryName,
    long targetRepositoryId,
    RepositoryFormat format,
    String status,
    String cursorPath,
    int pageSize,
    long totalAssets,
    long discoveredAssets,
    long migratedAssets,
    long failedAssets,
    Instant claimedAt,
    String lastError,
    Map<String, Object> options,
    Instant startedAt,
    Instant finishedAt) {
}
