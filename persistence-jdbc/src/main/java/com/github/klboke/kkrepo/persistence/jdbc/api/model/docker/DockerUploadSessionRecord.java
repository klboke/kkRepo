package com.github.klboke.kkrepo.persistence.jdbc.api.model.docker;

import java.time.Instant;
import java.util.Map;

public record DockerUploadSessionRecord(
    String uuid,
    long repositoryId,
    String imageName,
    byte[] imageNameHash,
    String status,
    long nextOffset,
    String digestAlgorithm,
    String expectedDigest,
    String createdBy,
    String createdByIp,
    Instant expiresAt,
    String lockedBy,
    Instant lockedUntil,
    Map<String, Object> attributes,
    Instant createdAt,
    Instant updatedAt) {
}
