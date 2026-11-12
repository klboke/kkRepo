package com.example.kkrepo.test;

import com.github.klboke.kkrepo.plugin.api.CleanupCandidate;
import com.github.klboke.kkrepo.plugin.api.CleanupRequest;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.MaintenanceRequest;
import com.github.klboke.kkrepo.plugin.api.MaintenanceResult;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import com.github.klboke.kkrepo.plugin.api.RemoteFetcher;
import com.github.klboke.kkrepo.plugin.api.RemoteResponse;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportConfig;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test-only plugin, loaded from a signed JAR by {@code PluginLoaderTest}. */
public final class TestSignedPlugin
    implements KkRepoPlugin, RemoteTransportProvider, RepositoryFormatProvider,
        RepositoryProtocolHandler {

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
    context.registerRepositoryProtocolHandler(this);
    context.registerCleanupRule(new CleanupRule() {
      @Override
      public String id() {
        return "test-cleanup";
      }

      @Override
      public List<CleanupCandidate> candidates(CleanupRequest request) {
        return List.of(new CleanupCandidate("repo", "stale/a", "test", 1));
      }
    });
    context.registerMaintenanceTask(new MaintenanceTask() {
      @Override
      public String id() {
        return "test-maintenance";
      }

      @Override
      public MaintenanceResult run(MaintenanceRequest request) {
        return new MaintenanceResult(1, 0, List.of("test"));
      }
    });
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

  @Override
  public Collection<RepositoryProtocolRoute> routes() {
    return List.of(RepositoryProtocolRoute.anyPath(
        id(), RepositoryKind.HOSTED, "GET", 100));
  }

  @Override
  public RepositoryResponse handle(RepositoryRequest request) {
    return RepositoryResponse.ok(
        ("plugin:" + request.path()).getBytes(StandardCharsets.UTF_8));
  }
}
