package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Mirrors Nexus Maven group metadata cache eviction. Nexus marks cached group metadata stale when a
 * direct member uploads metadata and deletes the cached merge when a member deletes metadata. This
 * service deletes the cached merge for both cases; without a stale bit, deletion is the equivalent
 * local signal that the next request must re-merge member metadata.
 */
@Service
public class MavenGroupMetadataInvalidator {
  private final RepositoryDao repositoryDao;
  private final RepositoryRuntimeRegistry runtimeRegistry;
  private final BlobStorageRegistry blobStorageRegistry;
  private final MavenAssetWriter writer;
  private final NexusLikeCacheController cacheController;
  private final MavenPathParser parser = new MavenPathParser();

  @Autowired
  public MavenGroupMetadataInvalidator(
      RepositoryDao repositoryDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      BlobStorageRegistry blobStorageRegistry,
      MavenAssetWriter writer,
      NexusLikeCacheController cacheController) {
    this.repositoryDao = repositoryDao;
    this.runtimeRegistry = runtimeRegistry;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.cacheController = cacheController;
  }

  public MavenGroupMetadataInvalidator(
      RepositoryDao repositoryDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      BlobStorageRegistry blobStorageRegistry,
      MavenAssetWriter writer) {
    this(repositoryDao, runtimeRegistry, blobStorageRegistry, writer, null);
  }

  public void memberMetadataStored(RepositoryRuntime member, MavenPath path) {
    memberAssetStored(member, path);
  }

  public void memberMetadataDeleted(RepositoryRuntime member, MavenPath path) {
    memberAssetDeleted(member, path);
  }

  public void memberAssetStored(RepositoryRuntime member, MavenPath path) {
    invalidateContainingGroups(member, path, path != null && parser.isRepositoryMetadata(path));
  }

  public void memberAssetDeleted(RepositoryRuntime member, MavenPath path) {
    invalidateContainingGroups(member, path, path != null && parser.isRepositoryMetadata(path));
  }

  private void invalidateContainingGroups(RepositoryRuntime member, MavenPath path, boolean deleteMetadataMerge) {
    if (member == null || path == null || path.isSubordinate()) {
      return;
    }
    invalidateContainingGroups(member.id(), path, deleteMetadataMerge, new HashSet<>());
  }

  private void invalidateContainingGroups(
      long memberRepositoryId,
      MavenPath path,
      boolean deleteMetadataMerge,
      Set<Long> visitedGroups) {
    for (RepositoryRecord groupRecord : repositoryDao.listGroupsContaining(memberRepositoryId)) {
      Long groupId = groupRecord.id();
      if (groupId == null || !visitedGroups.add(groupId)) {
        continue;
      }
      runtimeRegistry.invalidate(groupRecord.name());
      if (cacheController != null) {
        cacheController.invalidateAfterCommit(groupId, cacheType(path));
      }
      RepositoryRuntime group = runtimeRegistry.resolveById(groupId).orElse(null);
      if (deleteMetadataMerge && parser.isRepositoryMetadata(path) && group != null && group.blobStoreId() != null) {
        BlobStorage storage = blobStorageRegistry.forBlobStoreId(group.blobStoreId());
        writer.deleteAsset(group, storage, path, false);
      }
      invalidateContainingGroups(groupId, path, deleteMetadataMerge, visitedGroups);
    }
  }

  private static NexusCacheType cacheType(MavenPath path) {
    return path != null && "maven-metadata.xml".equals(path.main().fileName())
        ? NexusCacheType.METADATA
        : NexusCacheType.CONTENT;
  }
}
