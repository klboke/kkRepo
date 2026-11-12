package com.github.klboke.kkrepo.plugin.builtin;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.plugin.api.BuiltinProtocolOperations;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Extracted catch-all route. Focused handlers keep their more specific or lower-order routes; this
 * plugin only owns the final fallback. It delegates to the host transactional handler and leaves
 * request bodies and streaming downloads on their original servlet streams.
 */
public final class BuiltinProtocolPlugin implements KkRepoPlugin, RepositoryProtocolHandler {
  private static final int ORDER = 999;
  private static final List<String> METHODS =
      List.of("GET", "HEAD", "PUT", "DELETE", "POST");
  private static final List<RepositoryProtocolRoute> ROUTES = buildRoutes();

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor(
        "builtin-protocol",
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
  public boolean usesRawRequest() {
    return true;
  }

  @Override
  public RepositoryResponse handle(RepositoryRequest request) throws IOException {
    BuiltinProtocolOperations operations =
        request.context().service(BuiltinProtocolOperations.class)
            .orElseThrow(() -> new IllegalStateException(
                "Built-in protocol operations are not available"));
    return operations.handle(request);
  }

  private static List<RepositoryProtocolRoute> buildRoutes() {
    List<RepositoryProtocolRoute> routes = new ArrayList<>();
    for (RepositoryFormat format : RepositoryFormat.values()) {
      FormatId id = new FormatId(format.id());
      for (RepositoryKind kind : RepositoryKind.values()) {
        for (String method : METHODS) {
          routes.add(RepositoryProtocolRoute.anyPath(id, kind, method, ORDER));
        }
      }
    }
    return List.copyOf(routes);
  }
}
