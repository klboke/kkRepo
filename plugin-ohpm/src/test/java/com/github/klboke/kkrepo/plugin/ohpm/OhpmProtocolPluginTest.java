package com.github.klboke.kkrepo.plugin.ohpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryPolicy;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmWriteOperations;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OhpmProtocolPluginTest {

  @Test
  void delegatesWritesToTheHostOperation() throws Exception {
    OhpmProtocolPlugin plugin = new OhpmProtocolPlugin();
    RecordingContext context = new RecordingContext();
    plugin.contribute(context);
    assertSame(plugin, context.handler);
    assertEquals(9, plugin.routes().size());

    OhpmWriteOperations operations = request -> {
      assertEquals("-/package/demo", request.path());
      assertEquals("ALLOW", request.context().policy().writePolicy());
      return RepositoryResponse.status(201);
    };
    RepositoryDescriptor descriptor = descriptor();
    RepositoryResponse response = context.handler.handle(new RepositoryRequest(
        descriptor,
        "repo",
        "-/package/demo",
        "PUT",
        Map.of("Content-Type", java.util.List.of("application/json")),
        Map.of(),
        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        null,
        new TestRepositoryContext(descriptor, operations),
        Map.of()));

    assertEquals(201, response.status());
  }

  private static RepositoryDescriptor descriptor() {
    return new RepositoryDescriptor(
        1L,
        "repo",
        new FormatId("ohpm"),
        RepositoryKind.HOSTED,
        "ohpm-hosted",
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
    public void registerBlobStoreProvider(com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider provider) {
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
      OhpmWriteOperations operations) implements RepositoryContext {

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

    @Override
    public RepositoryPolicy policy() {
      return new RepositoryPolicy(repository.writePolicy(), repository.versionPolicy(),
          repository.layoutPolicy());
    }
  }
}
