package com.github.klboke.kkrepo.protocol.ohpm;

import com.github.klboke.kkrepo.plugin.api.PluginHostService;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import java.io.IOException;

/**
 * Host-side OHPM write operations (login, publish, unpublish, dist-tags). The plugin owns HTTP
 * routing and neutral response shape; authentication, leases, policies, transactions and audit stay
 * in the host service. Read/download streaming remains in the built-in handler.
 */
public interface OhpmWriteOperations extends PluginHostService {

  RepositoryResponse handle(RepositoryRequest request) throws IOException;
}
