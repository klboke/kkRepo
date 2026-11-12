package com.github.klboke.kkrepo.plugin.api;

import java.io.IOException;

/**
 * Host-side fallback for repository protocol requests not owned by a focused handler. The catch-all
 * plugin owns route selection and delegates here; repository resolution, security, transactions and
 * format services stay in the host.
 */
public interface BuiltinProtocolOperations extends PluginHostService {

  RepositoryResponse handle(RepositoryRequest request) throws IOException;
}
