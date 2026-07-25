package com.github.klboke.kkrepo.persistence.jdbc.api;

/** Feature-neutral ownership contract for blobs that are not referenced by a user-visible asset. */
public interface BlobReferenceDao {
  boolean retain(String ownerType, long ownerId, long blobId);

  int release(String ownerType, long ownerId, long blobId);

  boolean isReferenced(long blobId);
}
