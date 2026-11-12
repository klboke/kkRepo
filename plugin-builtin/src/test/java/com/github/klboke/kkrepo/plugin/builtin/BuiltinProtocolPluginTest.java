package com.github.klboke.kkrepo.plugin.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.plugin.api.BuiltinProtocolOperations;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuiltinProtocolPluginTest {

  @Test
  void registersTheFinalFallbackForEveryFormatAndMethod() {
    BuiltinProtocolPlugin plugin = new BuiltinProtocolPlugin();
    RecordingContext context = new RecordingContext();

    plugin.contribute(context);

    assertEquals("builtin-protocol", plugin.descriptor().id());
    assertSame(plugin, context.handler);
    assertTrue(plugin.usesRawRequest());
    assertEquals(
        RepositoryFormat.values().length * RepositoryKind.values().length * 5,
        plugin.routes().size());
    assertTrue(plugin.routes().stream().anyMatch(route ->
        route.format().equals(new FormatId("raw"))
            && route.type() == RepositoryKind.HOSTED
            && route.method().equals("GET")
            && route.order() == 999));
  }

  @Test
  void delegatesToTheHostOperations() throws Exception {
    BuiltinProtocolPlugin plugin = new BuiltinProtocolPlugin();
    RepositoryDescriptor descriptor = descriptor();
    BuiltinProtocolOperations operations = request -> {
      assertEquals("a/b", request.path());
      return RepositoryResponse.status(206);
    };

    RepositoryResponse response = plugin.handle(new RepositoryRequest(
        descriptor,
        "repo",
        "a/b",
        "GET",
        Map.of(),
        Map.of(),
        new byte[0],
        null,
        new TestRepositoryContext(descriptor, operations),
        Map.of()));

    assertEquals(206, response.status());
  }

  private static RepositoryDescriptor descriptor() {
    return new RepositoryDescriptor(
        1L,
        "repo",
        new FormatId("raw"),
        RepositoryKind.HOSTED,
        "raw-hosted",
        true,
        1L,
        "ALLOW",
        "RELEASE",
        "STRICT",
        null,
        Map.of());
  }

  private static final class RecordingContext implements PluginContext {
    private RepositoryProtocolHandler handler;

    @Override
    public void registerRemoteTransport(RemoteTransportProvider provider) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void registerRepositoryFormat(RepositoryFormatProvider provider) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void registerRepositoryProtocolHandler(RepositoryProtocolHandler handler) {
      this.handler = handler;
    }

    @Override
    public void registerBlobStoreProvider(
        com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider provider) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void registerCleanupRule(CleanupRule rule) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void registerMaintenanceTask(MaintenanceTask task) {
      throw new UnsupportedOperationException();
    }
  }

  private record TestRepositoryContext(
      RepositoryDescriptor repository,
      BuiltinProtocolOperations operations) implements RepositoryContext {

    @Override
    public Optional<BlobStorage> blobs() {
      return Optional.empty();
    }

    @Override
    public <T> Optional<T> service(Class<T> type) {
      return operations != null && type.isInstance(operations)
          ? Optional.of(type.cast(operations))
          : Optional.empty();
    }
  }
}
