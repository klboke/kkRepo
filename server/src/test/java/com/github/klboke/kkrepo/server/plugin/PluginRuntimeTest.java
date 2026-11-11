package com.github.klboke.kkrepo.server.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.RemoteFetcher;
import com.github.klboke.kkrepo.plugin.api.RemoteResponse;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportConfig;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.loader.LoadedPlugins;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PluginRuntimeTest {

  @Test
  void indexesRemoteTransportsAndRepositoryFormatsByNormalizedKey() {
    RemoteTransportProvider transport = transport("test-transport");
    RepositoryFormatProvider format = format("custom-format");
    LoadedPlugins loaded = new LoadedPlugins(
        List.of(), List.of(transport), List.of(format), List.of());

    try (PluginRuntime runtime = new PluginRuntime(loaded)) {
      assertEquals("test-transport", runtime.remoteTransport("TEST-TRANSPORT")
          .orElseThrow().type());
      assertEquals("custom-format", runtime.repositoryFormat("CUSTOM-FORMAT")
          .orElseThrow().id().value());
      assertTrue(runtime.repositoryFormatIds().contains("custom-format"));
    }
  }

  @Test
  void rejectsDuplicateRemoteTransportTypes() {
    LoadedPlugins loaded = new LoadedPlugins(
        List.of(),
        List.of(transport("duplicate"), transport("DUPLICATE")),
        List.of(),
        List.of());

    assertThrows(IllegalStateException.class, () -> new PluginRuntime(loaded));
  }

  private static RemoteTransportProvider transport(String type) {
    return new RemoteTransportProvider() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public RemoteFetcher create(RemoteTransportConfig config) {
        return request -> new RemoteResponse(200, Map.of(), new byte[0]);
      }
    };
  }

  private static RepositoryFormatProvider format(String id) {
    return new RepositoryFormatProvider() {
      private final FormatId formatId = new FormatId(id);

      @Override
      public FormatId id() {
        return formatId;
      }

      @Override
      public RepositoryFormatDescriptor formatDescriptor() {
        return new RepositoryFormatDescriptor(formatId, "Custom", "", Set.of());
      }
    };
  }
}
