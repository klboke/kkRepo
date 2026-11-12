package com.github.klboke.kkrepo.plugin.api;

import java.io.IOException;
import java.util.Collection;

/** Framework-agnostic repository protocol extension point. */
public interface RepositoryProtocolHandler {

  Collection<RepositoryProtocolRoute> routes();

  RepositoryResponse handle(RepositoryRequest request) throws IOException;

  /**
   * When true, the host must not buffer the request body or multipart content. The handler is
   * expected to use the opaque {@link RepositoryContext#hostContext()} through a host service.
   * This keeps large uploads and downloads on their original servlet streams.
   */
  default boolean usesRawRequest() {
    return false;
  }
}
