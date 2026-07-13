package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.time.Instant;
import java.util.Map;

public record PubUploadSessionRecord(
    Long id,
    long repositoryId,
    String sessionId,
    String fieldToken,
    String principalUserId,
    Long principalApiKeyId,
    String status,
    Instant expiresAt,
    Long blobStoreId,
    String blobRef,
    String objectKey,
    String md5,
    String sha1,
    String sha256,
    String sha512,
    Long size,
    String packageName,
    String version,
    Map<String, Object> pubspec,
    String errorMessage,
    Instant finalizedAt,
    Instant createdAt,
    Instant updatedAt) {
}
