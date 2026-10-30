package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.time.Instant;

/** One staged add/remove of a member in a group repository. */
public record StagingGroupChangeRecord(
    Long id,
    long sessionId,
    long groupRepositoryId,
    long memberRepositoryId,
    String action,
    Integer position,
    String status,
    Instant createdAt,
    Instant updatedAt) {
}
