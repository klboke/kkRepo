package com.github.klboke.kkrepo.protocol.gitlfs;

import com.github.klboke.kkrepo.plugin.api.PluginHostService;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import java.io.IOException;

/**
 * Host-side Git LFS write operations (batch, verify and object upload). The plugin owns the HTTP
 * routing and neutral response shape; object metadata, leases, policies, transactions and audit stay
 * in the host service. Read/download streaming intentionally remains in the built-in handler until
 * the neutral response supports streaming.
 */
public interface GitLfsWriteOperations extends PluginHostService {

  RepositoryResponse handle(RepositoryRequest request) throws IOException;
}
