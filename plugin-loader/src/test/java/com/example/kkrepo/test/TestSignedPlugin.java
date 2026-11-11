package com.example.kkrepo.test;

import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import com.github.klboke.kkrepo.plugin.api.RemoteFetcher;
import com.github.klboke.kkrepo.plugin.api.RemoteResponse;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportConfig;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test-only plugin, loaded from a signed JAR by {@code PluginLoaderTest}. */
public final class TestSignedPlugin
    implements KkRepoPlugin, RemoteTransportProvider, RepositoryFormatProvider {

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor(
        "test-signed",
        "1.0.0",
        PluginApiVersion.CURRENT,
        getClass().getName(),
        List.of("remote.transport"));
  }

  @Override
  public void contribute(PluginContext context) {
    context.registerRemoteTransport(this);
    context.registerRepositoryFormat(this);
  }

  @Override
  public FormatId id() {
    return new FormatId("test-format");
  }

  @Override
  public RepositoryFormatDescriptor formatDescriptor() {
    return new RepositoryFormatDescriptor(
        id(), "Test Format", "Test-only repository format", Set.of("hosted"));
  }

  @Override
  public String type() {
    return "test-signed";
  }

  @Override
  public RemoteFetcher create(RemoteTransportConfig config) {
    return request -> new RemoteResponse(
        200, Map.of(), "ok".getBytes(StandardCharsets.UTF_8));
  }
}
