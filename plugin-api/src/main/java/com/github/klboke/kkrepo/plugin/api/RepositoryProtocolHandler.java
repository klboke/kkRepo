package com.github.klboke.kkrepo.plugin.api;

import java.io.IOException;
import java.util.Collection;

/** Framework-agnostic repository protocol extension point. */
public interface RepositoryProtocolHandler {

  Collection<RepositoryProtocolRoute> routes();

  RepositoryResponse handle(RepositoryRequest request) throws IOException;
}
