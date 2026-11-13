package com.github.klboke.kkrepo.plugin.gitlfs;

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
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsWriteOperations;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Extracted Git LFS write route. Batch, verify and object upload enter the plugin; metadata,
 * leases, policies, transactions and audit stay behind the host service. Streaming downloads remain
 * in the built-in handler until the neutral response supports streaming.
 */
public final class GitLfsProtocolPlugin implements KkRepoPlugin, RepositoryProtocolHandler {
  private static final List<RepositoryProtocolRoute> ROUTES = List.of(
      RepositoryProtocolRoute.anyPath(
          new FormatId("gitlfs"), RepositoryKind.HOSTED, "POST", -100),
      RepositoryProtocolRoute.anyPath(
          new FormatId("gitlfs"), RepositoryKind.HOSTED, "PUT", -100));

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor(
        "gitlfs-protocol",
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
    GitLfsWriteOperations operations = request.context().service(GitLfsWriteOperations.class)
        .orElseThrow(() -> new IllegalStateException("Git LFS write service is not available"));
    return operations.handle(request);
  }
}
