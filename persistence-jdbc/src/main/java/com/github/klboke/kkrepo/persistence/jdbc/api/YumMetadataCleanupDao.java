package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.util.List;

/** Nonblocking, bounded locks for explicit Yum hosted repository deletion. */
public interface YumMetadataCleanupDao {
  boolean lockRepository(long repositoryId);

  List<Long> lockComponents(long repositoryId, int limit);

  List<Long> lockAssets(long componentId, int limit);

  boolean lockBlob(long blobId);
}
