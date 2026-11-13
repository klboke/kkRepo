package com.github.klboke.kkrepo.server.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.plugin.api.CleanupCandidate;
import com.github.klboke.kkrepo.plugin.api.CleanupRequest;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.MaintenanceRequest;
import com.github.klboke.kkrepo.plugin.api.MaintenanceResult;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
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

  @Test
  void exposesStorageCleanupAndMaintenancePorts() {
    BlobStoreProvider store = mock(BlobStoreProvider.class);
    when(store.type()).thenReturn("custom-store");
    CleanupRule cleanup = new CleanupRule() {
      @Override
      public String id() {
        return "cleanup-1";
      }

      @Override
      public List<CleanupCandidate> candidates(CleanupRequest request) {
        return List.of();
      }
    };
    MaintenanceTask maintenance = new MaintenanceTask() {
      @Override
      public String id() {
        return "maintenance-1";
      }

      @Override
      public MaintenanceResult run(MaintenanceRequest request) {
        return new MaintenanceResult(0, 0, List.of());
      }
    };
    LoadedPlugins loaded = new LoadedPlugins(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(store),
        List.of(cleanup),
        List.of(maintenance),
        List.of());

    try (PluginRuntime runtime = new PluginRuntime(loaded)) {
      assertEquals("custom-store", runtime.blobStoreProvider("CUSTOM-STORE").orElseThrow().type());
      assertEquals(1, runtime.cleanupRules().size());
      assertEquals("cleanup-1", runtime.cleanupRules().getFirst().id());
      assertEquals(1, runtime.maintenanceTasks().size());
      assertEquals("maintenance-1", runtime.maintenanceTasks().getFirst().id());
    }
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
