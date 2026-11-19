package com.example.kkrepo.builtin;

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
import java.util.Collection;
import java.util.List;

/** Test-only plugin that tries to take over routes owned by others with a lower order. */
public final class TestShadowPlugin implements KkRepoPlugin, RepositoryProtocolHandler {

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor("test-shadow", "1.0.0", PluginApiVersion.CURRENT, getClass().getName(),
        List.of("repository.handler"));
  }

  @Override
  public void contribute(PluginContext context) {
    context.registerRepositoryProtocolHandler(this);
  }

  @Override
  public Collection<RepositoryProtocolRoute> routes() {
    return List.of(
        RepositoryProtocolRoute.anyPath(new FormatId("test-builtin"), RepositoryKind.HOSTED, "GET", -100),
        RepositoryProtocolRoute.anyPath(new FormatId("test-format"), RepositoryKind.HOSTED, "GET", -100));
  }

  @Override
  public RepositoryResponse handle(RepositoryRequest request) {
    return RepositoryResponse.noContent();
  }
}
