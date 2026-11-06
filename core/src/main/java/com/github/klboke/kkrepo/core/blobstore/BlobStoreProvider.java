package com.github.klboke.kkrepo.core.blobstore;

import com.github.klboke.kkrepo.core.BlobStorage;
import java.util.Set;

/**
 * Pluggable blob-store backend. One implementation per storage technology; the server resolves a
 * persisted blob-store row to a provider and asks it for a {@link BlobStorage}.
 *
 * <p>Implementations must be safe to call from every replica: {@link #create(BlobStoreDescriptor)}
 * may run concurrently on many nodes for the same store, and no provider may treat its JVM as the
 * source of truth. Any client caching a provider performs is node-local and must be rebuildable
 * after {@link #invalidate(long)}.
 */
public interface BlobStoreProvider {

  /** Canonical discriminator persisted in {@code blob_store.type}, e.g. {@code s3}. */
  String type();

  /** Whether this provider owns the given row, matching on {@code type} and the legacy engine. */
  boolean supports(BlobStoreDescriptor descriptor);

  /**
   * Fingerprint of the effective configuration. The server keys cached {@link BlobStorage}
   * wrappers on it, so it must change whenever a setting that invalidates an existing client or
   * in-flight upload changes, and must stay stable otherwise.
   */
  String signature(BlobStoreDescriptor descriptor);

  /** Builds a storage wrapper; the caller owns its lifecycle and calls {@link BlobStorage#close()}. */
  BlobStorage create(BlobStoreDescriptor descriptor);

  /** Metrics label distinguishing backends that share a provider, e.g. {@code aws-s3}. */
  default String engine(BlobStoreDescriptor descriptor) {
    return type();
  }

  /**
   * Store IDs whose clients this provider still caches although they are no longer active, so the
   * server can release clients for deleted stores.
   */
  default Set<Long> cachedStoreIdsMissingFrom(Set<Long> activeStoreIds) {
    return Set.of();
  }

  /** Drops any node-local client cached for the store. */
  default void invalidate(long storeId) {
  }
}
