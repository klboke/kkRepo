package com.github.klboke.kkrepo.plugin.api;

import java.io.IOException;
import java.util.Collection;

/** Framework-agnostic repository protocol extension point. */
public interface RepositoryProtocolHandler {

  Collection<RepositoryProtocolRoute> routes();

  RepositoryResponse handle(RepositoryRequest request) throws IOException;

  /**
   * When true, the host does not buffer the request body or multipart content: the handler reads
   * the client stream through {@link RepositoryRequest#openBody()} and must bound what it keeps in
   * memory. Large uploads then reach host services without an in-memory copy.
   */
  default boolean streamsRequestBody() {
    return false;
  }
}
