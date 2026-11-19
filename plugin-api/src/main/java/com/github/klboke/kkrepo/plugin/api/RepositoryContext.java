package com.github.klboke.kkrepo.plugin.api;

import com.github.klboke.kkrepo.core.BlobStorage;
import java.util.Optional;

/**
 * Narrow access to the host repository context. It never exposes Spring, servlet or JDBC types.
 *
 * <p>Transactional operations stay behind host services; a plugin handler gets only the services it
 * declared through {@link #service(Class)}. Host services receive the context back as the opaque
 * identity of the current request, so request-scoped details (caller identity, client address,
 * public base URL) never cross the plugin boundary.
 */
public interface RepositoryContext {

  RepositoryDescriptor repository();

  /** Storage bound to the repository when the host exposes it. */
  Optional<BlobStorage> blobs();

  /** Host service such as a format-specific uploader; empty when the host did not register it. */
  <T> Optional<T> service(Class<T> type);

  /** Repository write/version/layout policies. */
  default RepositoryPolicy policy() {
    return new RepositoryPolicy("", "", "");
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
