package com.github.klboke.kkrepo.server.routing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

class PluginProtocolDispatcherTest {

  @Test
  void convertsNeutralRequestAndResponse() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    PluginProtocolDispatcher dispatcher =
        new PluginProtocolDispatcher(List.of(handler));

    MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/repository/repo/a/b");
    servlet.addHeader("X-Test", "one");
    servlet.addParameter("q", "value");
    RepositoryProtocolRequest request = new RepositoryProtocolRequest(
        runtime(), "repo", "a/b", HttpMethod.GET, servlet, null, null);

    ResponseEntity<?> response = dispatcher.handle(request);

    assertEquals(201, response.getStatusCode().value());
    assertEquals(List.of("plugin"), response.getHeaders().get("X-Plugin"));
    assertArrayEquals("neutral:a/b".getBytes(StandardCharsets.UTF_8),
        (byte[]) response.getBody());
    assertEquals("a/b", handler.request.path());
    assertEquals("GET", handler.request.method());
    assertEquals("repo", handler.request.repository().name());
    assertEquals("raw", handler.request.repository().format().value());
    assertEquals(RepositoryKind.HOSTED, handler.request.repository().type());
    assertEquals("one", handler.request.headers().get("X-Test").getFirst());
    assertEquals("value", handler.request.queryParameters().get("q"));
  }

  @Test
  void forwardsRequestBodyAndMultipartPart() throws Exception {
    RecordingHandler handler = new RecordingHandler();
    PluginProtocolDispatcher dispatcher =
        new PluginProtocolDispatcher(List.of(handler));
    MockHttpServletRequest servlet =
        new MockHttpServletRequest("POST", "/repository/repo/api/charts");
    servlet.setContent("payload".getBytes(StandardCharsets.UTF_8));
    MockMultipartFile chart = new MockMultipartFile(
        "chart", "chart.tgz", "application/gzip", new byte[] {1, 2, 3});
    RepositoryProtocolRequest request = new RepositoryProtocolRequest(
        runtime(), "repo", "api/charts", HttpMethod.POST, servlet, null, chart);

    dispatcher.handle(request);

    assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), handler.request.body());
    assertEquals("chart", handler.request.multipart().name());
    assertEquals("chart.tgz", handler.request.multipart().filename());
    assertArrayEquals(new byte[] {1, 2, 3}, handler.request.multipart().content());
  }

  @Test
  void dispatchesThroughTheLegacyRoutingPipeline() {
    RecordingHandler handler = new RecordingHandler();
    PluginProtocolDispatcher adapter = new PluginProtocolDispatcher(List.of(handler));
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("repo")).thenReturn(Optional.of(runtime()));
    RepositoryProtocolDispatcher dispatcher =
        new RepositoryProtocolDispatcher(runtimes, List.of(adapter));
    MockHttpServletRequest servlet =
        new MockHttpServletRequest("GET", "/repository/repo/snapshots/a");

    ResponseEntity<?> response = dispatcher.dispatchRead("repo", servlet, HttpMethod.GET);

    assertEquals(201, response.getStatusCode().value());
    assertArrayEquals("neutral:snapshots/a".getBytes(StandardCharsets.UTF_8),
        (byte[]) response.getBody());
    assertEquals("snapshots/a", handler.request.path());
  }

  @Test
  void rejectsFormatThatTheServerDoesNotKnow() {
    RepositoryProtocolHandler handler = new NeutralHandler(
        RepositoryProtocolRoute.anyPath(
            new FormatId("unknown-format"), RepositoryKind.HOSTED, "GET", 0));

    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> new PluginProtocolDispatcher(List.of(handler)));

    assertTrue(error.getMessage().contains("unknown-format"));
  }

  @Test
  void emptyAdapterIsAllowedButHasNoRoutes() {
    PluginProtocolDispatcher dispatcher = new PluginProtocolDispatcher(List.of());

    assertTrue(dispatcher.routes().isEmpty());
    assertTrue(dispatcher.allowsEmptyRoutes());
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L,
        "repo",
        RepositoryFormat.RAW,
        RepositoryType.HOSTED,
        "raw-hosted",
        true,
        1L,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        null,
        null,
        List.of());
  }

  private static final class RecordingHandler implements RepositoryProtocolHandler {
    private RepositoryRequest request;

    @Override
    public Collection<RepositoryProtocolRoute> routes() {
      return List.of(RepositoryProtocolRoute.anyPath(
          new FormatId("raw"), RepositoryKind.HOSTED, "GET", 100),
          RepositoryProtocolRoute.anyPath(
              new FormatId("raw"), RepositoryKind.HOSTED, "POST", 100));
    }

    @Override
    public RepositoryResponse handle(RepositoryRequest request) {
      this.request = request;
      return new RepositoryResponse(
          201,
          Map.of("X-Plugin", List.of("plugin")),
          ("neutral:" + request.path()).getBytes(StandardCharsets.UTF_8));
    }
  }

  private record NeutralHandler(RepositoryProtocolRoute route)
      implements RepositoryProtocolHandler {
    @Override
    public Collection<RepositoryProtocolRoute> routes() {
      return List.of(route);
    }

    @Override
    public RepositoryResponse handle(RepositoryRequest request) {
      return RepositoryResponse.noContent();
    }
  }
}
