package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.Optional;

public interface ProxyStateDao {
  Optional<ProxyRemoteState> loadState(long repositoryId);

  boolean isBlocked(long repositoryId, Instant now);

  void recordSuccess(long repositoryId, Instant now);

  ProxyRemoteState recordFailure(long repositoryId, long blockSeconds, String error, Instant now);

  record ProxyRemoteState(
      long repositoryId,
      Instant blockedUntil,
      int failCount,
      Instant lastSuccessAt,
      Instant lastFailureAt,
      String lastError) {
  }
}
