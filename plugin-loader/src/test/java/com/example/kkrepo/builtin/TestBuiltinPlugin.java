package com.example.kkrepo.builtin;

import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Test-only built-in plugin, discovered through a classpath service descriptor. */
public final class TestBuiltinPlugin
    implements KkRepoPlugin, RepositoryFormatProvider, RepositoryProtocolHandler {
  static final FormatId FORMAT = new FormatId("test-builtin");

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor("test-builtin", "1.0.0", PluginApiVersion.CURRENT, getClass().getName(),
        List.of("repository.format", "repository.handler"));
  }

  @Override
  public void contribute(PluginContext context) {
    context.registerRepositoryFormat(this);
    context.registerRepositoryProtocolHandler(this);
  }

  @Override
  public FormatId id() {
    return FORMAT;
  }

  @Override
  public RepositoryFormatDescriptor formatDescriptor() {
    return new RepositoryFormatDescriptor(FORMAT, "Test built-in", "", Set.of("hosted"));
  }

  @Override
  public Collection<RepositoryProtocolRoute> routes() {
    return List.of(RepositoryProtocolRoute.anyPath(FORMAT, RepositoryKind.HOSTED, "GET", 0));
  }

  @Override
  public RepositoryResponse handle(RepositoryRequest request) {
    return RepositoryResponse.noContent();
  }
}
