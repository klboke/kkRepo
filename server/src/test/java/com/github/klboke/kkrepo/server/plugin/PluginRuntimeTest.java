package com.github.klboke.kkrepo.server.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
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

  @Test
  void exposesBlobStoreProvidersByNormalizedType() {
    BlobStoreProvider store = mock(BlobStoreProvider.class);
    when(store.type()).thenReturn("custom-store");
    LoadedPlugins loaded = new LoadedPlugins(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(store),
        List.of());

    try (PluginRuntime runtime = new PluginRuntime(loaded)) {
      assertEquals("custom-store", runtime.blobStoreProvider("CUSTOM-STORE").orElseThrow().type());
    }
  }

  @Test
  void rejectsDuplicateRepositoryFormatIds() {
    LoadedPlugins loaded = new LoadedPlugins(
        List.of(), List.of(), List.of(format("duplicate"), format("DUPLICATE")), List.of());

    assertThrows(IllegalStateException.class, () -> new PluginRuntime(loaded));
  }

  @Test
  void rejectsDuplicateBlobStoreProviderTypes() {
    BlobStoreProvider first = mock(BlobStoreProvider.class);
    BlobStoreProvider second = mock(BlobStoreProvider.class);
    when(first.type()).thenReturn("duplicate");
    when(second.type()).thenReturn("DUPLICATE");
    LoadedPlugins loaded = new LoadedPlugins(
        List.of(), List.of(), List.of(), List.of(), List.of(first, second), List.of());

    assertThrows(IllegalStateException.class, () -> new PluginRuntime(loaded));
  }

  @Test
  void blankLookupsReturnEmptyAndMissingTypesAreRejected() {
    LoadedPlugins loaded = new LoadedPlugins(
        List.of(), List.of(), List.of(format("demo")), List.of());
    try (PluginRuntime runtime = new PluginRuntime(loaded)) {
      assertTrue(runtime.repositoryFormat(null).isEmpty());
      assertTrue(runtime.repositoryFormat(" ").isEmpty());
      assertTrue(runtime.remoteTransport("missing").isEmpty());
      assertTrue(runtime.blobStoreProvider("missing").isEmpty());
    }
    BlobStoreProvider blank = mock(BlobStoreProvider.class);
    when(blank.type()).thenReturn(" ");
    LoadedPlugins invalidStore = new LoadedPlugins(
        List.of(), List.of(), List.of(), List.of(), List.of(blank), List.of());
    assertThrows(IllegalStateException.class, () -> new PluginRuntime(invalidStore));
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
