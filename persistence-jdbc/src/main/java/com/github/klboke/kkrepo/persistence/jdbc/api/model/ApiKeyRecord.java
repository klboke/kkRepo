package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.time.LocalDateTime;
import java.util.Map;

public record ApiKeyRecord(
    Long id,
    String domain,
    String ownerSource,
    String ownerUserId,
    String displayName,
    String status,
    String apiKeyHash,
    String tokenPrefix,
    Map<String, Object> scopes,
    String encryptedPayload,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime expiresAt,
    LocalDateTime lastUsedAt) {
}
