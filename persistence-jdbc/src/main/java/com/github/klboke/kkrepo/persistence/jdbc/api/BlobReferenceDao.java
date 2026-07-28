package com.github.klboke.kkrepo.persistence.jdbc.api;

/** Feature-neutral ownership contract for blobs that are not referenced by a user-visible asset. */
public interface BlobReferenceDao {
  /**
   * Publishes durable blob ownership while fencing the blob row against concurrent garbage
   * collection. Callers that also create owner metadata should do both in the same transaction.
   *
   * @return {@code true} when the exact ownership is durable (newly inserted or already present);
   *     {@code false} when the blob is missing or already behind the committed deletion fence
   */
  boolean retain(String ownerType, long ownerId, long blobId);

  int release(String ownerType, long ownerId, long blobId);

  boolean isReferenced(long blobId);
}
