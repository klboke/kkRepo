package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;

/**
 * Durable, feature-neutral content-change stream emitted by the core asset persistence path.
 *
 * <p>Consumers own their cursors separately. Reading this stream must never be required to finish
 * an upload transaction.
 */
public interface ArtifactChangeDao {
  long append(ArtifactChange change);

  List<ArtifactChange> listAfter(long lastSeenId, int maxItems);

  enum ChangeKind {
    CONTENT_CREATED,
    CONTENT_REPLACED
  }

  record ArtifactChange(
      Long id,
      long repositoryId,
      long assetId,
      Long previousAssetBlobId,
      long assetBlobId,
      ChangeKind changeKind,
      Instant occurredAt) {
  }
}
