package com.github.klboke.kkrepo.plugin.ohpm;

import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmWriteOperations;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * Extracted OHPM write route. Login, publish, unpublish and dist-tag operations enter the plugin;
 * authentication, leases, policies, transactions and audit stay behind the host service. Streaming
 * GET/HEAD reads stay in the built-in handler.
 */
public final class OhpmProtocolPlugin implements KkRepoPlugin, RepositoryProtocolHandler {
  private static final List<RepositoryProtocolRoute> ROUTES = Stream.of("POST", "PUT", "DELETE")
      .flatMap(method -> Stream.of(RepositoryKind.HOSTED, RepositoryKind.PROXY, RepositoryKind.GROUP)
          .map(kind -> RepositoryProtocolRoute.anyPath(
              new FormatId("ohpm"), kind, method, -100)))
      .toList();

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor(
        "ohpm-protocol",
        "1.0.0",
        PluginApiVersion.CURRENT,
        getClass().getName(),
        List.of("repository.handler"));
  }

  @Override
  public void contribute(PluginContext context) {
    context.registerRepositoryProtocolHandler(this);
  }

  @Override
  public Collection<RepositoryProtocolRoute> routes() {
    return ROUTES;
  }

  @Override
  public RepositoryResponse handle(RepositoryRequest request) throws IOException {
    OhpmWriteOperations operations = request.context().service(OhpmWriteOperations.class)
        .orElseThrow(() -> new IllegalStateException("OHPM write service is not available"));
    return operations.handle(request);
  }
}
