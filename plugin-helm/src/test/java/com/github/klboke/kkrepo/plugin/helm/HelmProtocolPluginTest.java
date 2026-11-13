package com.github.klboke.kkrepo.plugin.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.plugin.api.CleanupRule;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import com.github.klboke.kkrepo.plugin.api.MultipartPart;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.RemoteTransportProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryFormatProvider;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.protocol.helm.HelmChartUploader;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HelmProtocolPluginTest {

  @Test
  void delegatesChartUploadToTheHostService() throws Exception {
    HelmProtocolPlugin plugin = new HelmProtocolPlugin();
    RecordingContext context = new RecordingContext();
    plugin.contribute(context);
    assertSame(plugin, context.handler);

    RepositoryDescriptor descriptor = descriptor();
    HelmChartUploader uploader = (repository, chart, user, ip) -> {
      assertEquals("repo", repository.name());
      assertEquals("chart.tgz", chart.filename());
      assertEquals("1.2.3.4", ip);
      return 201;
    };
    RepositoryResponse response = context.handler.handle(new RepositoryRequest(
        descriptor,
        "repo",
        "api/charts",
        "POST",
        Map.of(),
        Map.of(),
        new byte[0],
        new MultipartPart("chart", "chart.tgz", "application/gzip", new byte[] {1, 2}),
        new TestRepositoryContext(descriptor, uploader),
        Map.of()));

    assertEquals(201, response.status());
  }

  @Test
  void rejectsMissingMultipart() throws Exception {
    HelmProtocolPlugin plugin = new HelmProtocolPlugin();
    RecordingContext context = new RecordingContext();
    plugin.contribute(context);
    RepositoryDescriptor descriptor = descriptor();

    RepositoryResponse response = context.handler.handle(new RepositoryRequest(
        descriptor,
        "repo",
        "api/charts",
        "POST",
        Map.of(),
        Map.of(),
        new byte[0],
        null,
        new TestRepositoryContext(descriptor, null),
        Map.of()));

    assertEquals(400, response.status());
  }

  private static RepositoryDescriptor descriptor() {
    return new RepositoryDescriptor(
        1L,
        "repo",
        new FormatId("helm"),
        RepositoryKind.HOSTED,
        "helm-hosted",
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
      HelmChartUploader uploader) implements RepositoryContext {

    @Override
    public Optional<BlobStorage> blobs() {
      return Optional.empty();
    }

    @Override
    public <T> Optional<T> service(Class<T> type) {
      return uploader != null && type.isInstance(uploader)
          ? Optional.of(type.cast(uploader))
          : Optional.empty();
    }

    @Override
    public String clientAddress() {
      return "1.2.3.4";
    }
  }
}
