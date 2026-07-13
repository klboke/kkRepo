package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;

public interface MetadataRebuildDao {
  void enqueue(long repositoryId, String scopeKey);

  void reenqueueFailure(Claim claim, RuntimeException error);

  int delete(long repositoryId, String scopeKey);

  List<Claim> claim(int maxItems);

  long countBacklog();

  long oldestBacklogAgeSeconds();

  long countFailures();

  record Claim(
      long repositoryId,
      String scopeKey,
      Instant requestedAt,
      int attempts,
      String lastError) {}
}
