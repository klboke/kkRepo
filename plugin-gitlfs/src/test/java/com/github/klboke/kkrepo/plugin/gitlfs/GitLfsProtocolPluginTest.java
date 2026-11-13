package com.github.klboke.kkrepo.plugin.gitlfs;

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
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsWriteOperations;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitLfsProtocolPluginTest {

  @Test
  void delegatesWritesToTheHostOperation() throws Exception {
    GitLfsProtocolPlugin plugin = new GitLfsProtocolPlugin();
    RecordingContext context = new RecordingContext();
    plugin.contribute(context);
    assertSame(plugin, context.handler);
    assertEquals(2, plugin.routes().size());

    GitLfsWriteOperations operations = request -> {
      assertEquals("objects/batch", request.path());
      assertEquals("ADD", request.context().policy().writePolicy());
      return RepositoryResponse.status(202);
    };
    RepositoryDescriptor descriptor = descriptor();
    RepositoryResponse response = context.handler.handle(new RepositoryRequest(
        descriptor,
        "repo",
        "objects/batch",
        "POST",
        Map.of("Content-Type", java.util.List.of("application/vnd.git-lfs+json")),
        Map.of(),
        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        null,
        new TestRepositoryContext(descriptor, operations),
        Map.of()));

    assertEquals(202, response.status());
  }

  private static RepositoryDescriptor descriptor() {
    return new RepositoryDescriptor(
        1L,
        "repo",
        new FormatId("gitlfs"),
        RepositoryKind.HOSTED,
        "gitlfs-hosted",
        true,
        1L,
        "ADD",
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
      GitLfsWriteOperations operations) implements RepositoryContext {

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
