package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import java.time.Instant;

/** Repository explicitly added to one staging session. */
public record StagingRepositoryRecord(
    long sessionId,
    long repositoryId,
    RepositoryFormat format,
    Instant createdAt) {
}
