package com.github.klboke.kkrepo.persistence.jdbc.api.model.docker;

import java.time.Instant;

public record DockerTagRecord(
    Long id,
    long repositoryId,
    String imageName,
    byte[] imageNameHash,
    String tag,
    byte[] tagHash,
    long manifestId,
    String manifestDigest,
    String pushedBy,
    String pushedByIp,
    Instant createdAt,
    Instant updatedAt) {
}
