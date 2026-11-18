package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public interface OhpmRepositoryDao {
  Optional<Version> findVersion(long repositoryId, String packageName, String version, boolean proxy);

  Optional<Lease> acquire(String key, String owner, Instant now, Instant expiresAt);

  boolean renew(String key, String owner, long fence, Instant now, Instant expiresAt);

  void release(String key, String owner, long fence);

  record Version(Map<String, Object> manifest, String packageType, String hspType,
      String source, Map<String, Object> cache) {}

  record Lease(String key, String owner, long fence, Instant expiresAt) {}
}
