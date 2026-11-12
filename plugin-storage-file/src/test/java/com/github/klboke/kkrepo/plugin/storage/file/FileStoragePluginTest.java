package com.github.klboke.kkrepo.plugin.storage.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.blobstore.BlobStoreDescriptor;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FileStoragePluginTest {

  @Test
  void registersTheFileBackend() {
    FileStoragePlugin plugin = new FileStoragePlugin();
    RecordingContext context = new RecordingContext();

    plugin.contribute(context);

    assertEquals("storage-file", plugin.descriptor().id());
    assertNotNull(context.provider);
    assertEquals("file", context.provider.type());
    assertTrue(context.provider.supports(new BlobStoreDescriptor(
        1L, "file-store", "file", null, null, null, "", Map.of())));
  }

  private static final class RecordingContext implements PluginContext {
    private BlobStoreProvider provider;

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
      throw new UnsupportedOperationException();
    }

    @Override
    public void registerBlobStoreProvider(BlobStoreProvider provider) {
      this.provider = provider;
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
}
