package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DockerAuthTokenDao {
  void insert(
      String tokenHash,
      String source,
      String userId,
      String realmId,
      Long apiKeyId,
      List<Map<String, Object>> scopes,
      Instant expiresAt);

  Optional<TokenRecord> findValid(String tokenHash, Instant now);

  int deleteExpired(Instant now);

  record TokenRecord(
      String tokenHash,
      String subjectSource,
      String subjectUserId,
      String subjectRealmId,
      Long subjectApiKeyId,
      Map<String, Object> scopes,
      Instant expiresAt) {
  }
}
