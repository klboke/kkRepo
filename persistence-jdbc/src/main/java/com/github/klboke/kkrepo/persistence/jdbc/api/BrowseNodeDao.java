package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BrowseNodeDao {
  void upsertPathAncestors(long repositoryId, String fullPath, Long assetId, Long componentId);

  int deleteByAssetId(long assetId);

  int deleteAllForRepository(long repositoryId);

  List<String> listChildPaths(long repositoryId, String parentPath);

  List<BrowseChild> listChildren(long repositoryId, String parentPath);

  /** Exact indexed lookup used when a projected Browse path differs from the storage path. */
  default Optional<BrowseChild> findNode(long repositoryId, String path) {
    return Optional.empty();
  }

  record BrowseChild(
      long id,
      String path,
      String displayName,
      int depth,
      Long assetId,
      Long componentId,
      Long assetSize,
      String assetContentType,
      String assetSha1,
      Instant assetLastUpdatedAt,
      boolean hasChildren,
      boolean hasAssetSubtree) {
    public boolean leaf() { return assetId != null && !hasChildren; }
  }
}
