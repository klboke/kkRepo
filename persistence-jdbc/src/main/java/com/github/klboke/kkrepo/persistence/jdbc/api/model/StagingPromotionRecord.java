package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.time.Instant;
import java.util.Map;

/** One preflight or promotion attempt for a staging session. */
public record StagingPromotionRecord(
    Long id,
    long sessionId,
    String state,
    String fenceToken,
    String manifestSha256,
    Map<String, Object> report,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt) {
}
