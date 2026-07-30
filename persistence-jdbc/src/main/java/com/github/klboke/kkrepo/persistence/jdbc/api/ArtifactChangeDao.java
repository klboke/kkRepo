package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable, feature-neutral content-change stream emitted by the core asset persistence path when
 * an optional server consumer activates {@link ArtifactChangeEventMode}.
 *
 * <p>Consumers own their cursors separately. Reading this stream must never be required to finish
 * an upload transaction.
 */
public interface ArtifactChangeDao {
  long append(ArtifactChange change);

  List<ArtifactChange> listAfter(long lastSeenId, int maxItems);

  int deleteThrough(long consumedThroughId, int maxItems);

  /**
   * Returns primary-key bounds for the retained prefix-compactable stream.
   *
   * <p>The range lets operational metrics estimate retained rows with two index seeks instead of
   * repeatedly scanning the full event table.
   */
  Optional<EventRange> retainedRange();

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

  record EventRange(long oldestId, long newestId, Instant oldestOccurredAt) {
    public long estimatedCount() {
      return Math.max(0, newestId - oldestId + 1);
    }
  }
}
