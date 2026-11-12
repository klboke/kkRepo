package com.github.klboke.kkrepo.plugin.api;

import com.github.klboke.kkrepo.core.BlobStorage;
import java.util.Optional;

/**
 * Narrow access to the host repository context. It never exposes Spring, servlet or JDBC types.
 *
 * <p>Transactional operations stay behind host services; a plugin handler gets only the services it
 * declared through {@link #service(Class)}.
 */
public interface RepositoryContext {

  RepositoryDescriptor repository();

  /** Storage bound to the repository when the host exposes it. */
  Optional<BlobStorage> blobs();

  /** Host service such as a format-specific uploader; empty when the host did not register it. */
  <T> Optional<T> service(Class<T> type);

  /** Client address resolved by the host, or an empty string when unavailable. */
  default String clientAddress() {
    return "";
  }

  static RepositoryContext empty(RepositoryDescriptor repository) {
    return new RepositoryContext() {
      @Override
      public RepositoryDescriptor repository() {
        return repository;
      }

      @Override
      public Optional<BlobStorage> blobs() {
        return Optional.empty();
      }

      @Override
      public <T> Optional<T> service(Class<T> type) {
        return Optional.empty();
      }
    };
  }
}
