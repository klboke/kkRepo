package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.time.Instant;
import java.util.Map;

/**
 * Durable multi-repository staging session.
 *
 * <p>Sessions, revisions and ownership live in the shared database so every replica observes the
 * same lifecycle. Node-local caches never decide session correctness.
 */
public record StagingSessionRecord(
    Long id,
    String name,
    String state,
    long revision,
    String ownerSource,
    String ownerUserId,
    Instant createdAt,
    Instant openedAt,
    Instant frozenAt,
    Instant finishedAt,
    Instant expiresAt,
    Long promotionId,
    Map<String, Object> attributes) {
}
