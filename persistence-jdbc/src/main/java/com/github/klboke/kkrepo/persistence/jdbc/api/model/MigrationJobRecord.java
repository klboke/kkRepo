package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.time.Instant;
import java.util.Map;

public record MigrationJobRecord(
    Long id,
    String sourceNexusVersion,
    String sourceDataPath,
    String status,
    Map<String, Object> options,
    Map<String, Object> summary,
    Instant startedAt,
    Instant finishedAt) {
}
