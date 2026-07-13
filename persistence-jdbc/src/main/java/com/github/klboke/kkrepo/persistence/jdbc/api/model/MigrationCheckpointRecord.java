package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.time.Instant;

public record MigrationCheckpointRecord(
    long jobId,
    String sourceDatabase,
    String sourceClass,
    String sourceRid,
    String targetTable,
    String targetId,
    String sourceChecksum,
    Instant migratedAt) {
}
