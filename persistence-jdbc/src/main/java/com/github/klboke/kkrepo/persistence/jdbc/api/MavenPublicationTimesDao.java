package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.Optional;

/** Verified upstream publication times used by the release-age policy on every replica. */
public interface MavenPublicationTimesDao {
  Optional<PublicationTime> find(long repositoryId, byte[] sourceVersionHash);

  void record(long repositoryId, byte[] sourceVersionHash, PublicationTime time);

  record PublicationTime(Instant publishedAt, Instant verifiedAt) {}
}
