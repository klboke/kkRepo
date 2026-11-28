package com.github.klboke.kkrepo.plugin.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreDescriptor;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.RemoteFetcher;
import com.github.klboke.kkrepo.plugin.api.RemoteRequest;
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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultPluginContextTest {

  @Test
  void registersEveryPortAndNormalizesTheirKeys() {
    DefaultPluginContext context = new DefaultPluginContext();

    context.registerRemoteTransport(transport(" HTTP "));
    context.registerRepositoryFormat(format(" Demo "));
    context.registerRepositoryProtocolHandler(handler(
        RepositoryProtocolRoute.anyPath(new FormatId("demo"), RepositoryKind.HOSTED, "get", 0)));
    context.registerBlobStoreProvider(store(" FILE "));

    assertEquals(1, context.remoteTransports().size());
    assertEquals("http", context.remoteTransports().getFirst().type().trim().toLowerCase());
    assertEquals(1, context.repositoryFormats().size());
    assertEquals("demo", context.repositoryFormats().getFirst().id().value());
    assertEquals(1, context.repositoryProtocolHandlers().size());
    assertEquals(1, context.blobStoreProviders().size());
  }

  @Test
  void rejectsMissingAndDuplicateRemoteTransports() {
    DefaultPluginContext context = new DefaultPluginContext();
    assertThrows(IllegalArgumentException.class, () -> context.registerRemoteTransport(null));
    assertThrows(IllegalArgumentException.class, () -> context.registerRemoteTransport(transport(" ")));
    context.registerRemoteTransport(transport("http"));
    assertThrows(IllegalArgumentException.class, () -> context.registerRemoteTransport(transport("HTTP")));
  }

  @Test
  void rejectsInvalidAndDuplicateRepositoryFormats() {
    DefaultPluginContext context = new DefaultPluginContext();
    assertThrows(IllegalArgumentException.class, () -> context.registerRepositoryFormat(null));
    assertThrows(IllegalArgumentException.class, () -> context.registerRepositoryFormat(
        new FormatProvider(new FormatId("demo"), null)));
    assertThrows(IllegalArgumentException.class, () -> context.registerRepositoryFormat(
        new FormatProvider(new FormatId("demo"),
            new RepositoryFormatDescriptor(new FormatId("other"), "Other", "", Set.of()))));

    context.registerRepositoryFormat(format("demo"));
    assertThrows(IllegalArgumentException.class, () -> context.registerRepositoryFormat(format("DEMO")));
  }

  @Test
  void rejectsInvalidHandlersAndDuplicateRoutesWhateverTheOrder() {
    DefaultPluginContext context = new DefaultPluginContext();
    RepositoryProtocolRoute route =
        RepositoryProtocolRoute.anyPath(new FormatId("demo"), RepositoryKind.HOSTED, "GET", 0);

    assertThrows(IllegalArgumentException.class, () -> context.registerRepositoryProtocolHandler(null));
    assertThrows(IllegalArgumentException.class, () -> context.registerRepositoryProtocolHandler(
        new Handler(null)));
    assertThrows(IllegalArgumentException.class, () -> context.registerRepositoryProtocolHandler(
        new Handler(List.of())));
    assertThrows(IllegalArgumentException.class, () -> context.registerRepositoryProtocolHandler(
        new Handler(java.util.Arrays.asList(route, null))));

    context.registerRepositoryProtocolHandler(handler(route));
    assertThrows(IllegalArgumentException.class, () -> context.registerRepositoryProtocolHandler(
        handler(RepositoryProtocolRoute.anyPath(
            new FormatId("demo"), RepositoryKind.HOSTED, "GET", 99))));
  }

  @Test
  void rejectsInvalidAndDuplicateBlobStoreProviders() {
    DefaultPluginContext context = new DefaultPluginContext();
    assertThrows(IllegalArgumentException.class, () -> context.registerBlobStoreProvider(null));
    assertThrows(IllegalArgumentException.class, () -> context.registerBlobStoreProvider(store(" ")));

    context.registerBlobStoreProvider(store("file"));
    assertThrows(IllegalArgumentException.class, () -> context.registerBlobStoreProvider(store("FILE")));
  }

  private static RemoteTransportProvider transport(String type) {
    return new RemoteTransportProvider() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public RemoteFetcher create(RemoteTransportConfig config) {
        return request -> new RemoteResponse(200, null, null);
      }
    };
  }

  private static RepositoryFormatProvider format(String id) {
    FormatId formatId = new FormatId(id);
    return new FormatProvider(formatId,
        new RepositoryFormatDescriptor(formatId, "Demo", "", Set.of()));
  }

  private record FormatProvider(FormatId id, RepositoryFormatDescriptor descriptor)
      implements RepositoryFormatProvider {
    @Override
    public RepositoryFormatDescriptor formatDescriptor() {
      return descriptor;
    }
  }

  private static RepositoryProtocolHandler handler(RepositoryProtocolRoute route) {
    return new Handler(List.of(route));
  }

  private record Handler(List<RepositoryProtocolRoute> routes) implements RepositoryProtocolHandler {
    @Override
    public RepositoryResponse handle(RepositoryRequest request) {
      return RepositoryResponse.noContent();
    }
  }

  private static BlobStoreProvider store(String type) {
    return new BlobStoreProvider() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public boolean supports(BlobStoreDescriptor descriptor) {
        return false;
      }

      @Override
      public String signature(BlobStoreDescriptor descriptor) {
        return "";
      }

      @Override
      public BlobStorage create(BlobStoreDescriptor descriptor) {
        return null;
      }
    };
  }
}
