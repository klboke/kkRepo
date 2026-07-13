package com.github.klboke.kkrepo.persistence.jdbc.api.model.docker;

import java.time.Instant;
import java.util.Map;

public record DockerManifestRecord(
    Long id,
    long repositoryId,
    String imageName,
    byte[] imageNameHash,
    String digestAlgorithm,
    String digest,
    byte[] digestHash,
    String mediaType,
    String artifactType,
    String subjectDigest,
    byte[] subjectDigestHash,
    long assetId,
    long size,
    String pushedBy,
    String pushedByIp,
    Instant deletedAt,
    Map<String, Object> attributes,
    Instant createdAt,
    Instant updatedAt) {
}
