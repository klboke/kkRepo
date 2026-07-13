package com.github.klboke.kkrepo.persistence.jdbc.api.model.docker;

import java.time.Instant;

public record DockerUploadChunkRecord(
    Long id,
    String sessionUuid,
    int chunkIndex,
    long startOffset,
    long endOffset,
    String blobRef,
    String objectKey,
    String sha256,
    long size,
    Instant createdAt) {
}
