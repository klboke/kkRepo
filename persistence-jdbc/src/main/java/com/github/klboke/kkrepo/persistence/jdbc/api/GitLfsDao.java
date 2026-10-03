package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable LFS upload ownership. All publication calls participate in the caller's transaction. */
public interface GitLfsDao {
  Upload create(long repositoryId, String oid, long size, String subjectKey, long blobStoreId);
  Optional<Upload> find(String id);
  Optional<Upload> claim(String id, String subjectKey, String blobRef, String objectKey);
  boolean renew(String id, long fence);
  boolean lockForPublication(String id, long fence);
  void published(String id, long fence, long assetId);
  void multipartStarted(String id, long fence, String multipartId);
  void abandon(String id, long fence);
  List<Upload> claimExpired(int limit);
  void reaped(String id, String cleanupToken);
  int prunePublished(int limit);

  record Upload(String id, long repositoryId, String oid, long size, String subjectKey,
                long blobStoreId, long generation, long fence, Instant repositoryVersion,
                Instant expiresAt, String state, String blobRef, String objectKey,
                String multipartId, String cleanupToken) {}
}
