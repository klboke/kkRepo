package com.github.klboke.kkrepo.server.routing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import com.github.klboke.kkrepo.plugin.api.PluginHostService;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
    assertEquals("neutral:a/b", written(response));
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

    assertArrayEquals(new byte[0], handler.request.body());
    assertEquals("chart", handler.request.multipart().name());
    assertEquals("chart.tgz", handler.request.multipart().filename());
    assertArrayEquals(new byte[] {1, 2, 3}, handler.request.multipart().content());
  }

  @Test
  void dispatchesThroughTheLegacyRoutingPipeline() throws Exception {
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
    assertEquals("neutral:snapshots/a", written(response));
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

  @Test
  void exposesHostServicesAndTheRoutedRuntimeThroughTheRepositoryContext() throws Exception {
    HostCounter counter = new HostCounter();
    RepositoryRuntime runtime = runtime(RepositoryFormat.GITLFS, RepositoryType.HOSTED);
    MockHttpServletRequest servlet =
        new MockHttpServletRequest("POST", "/repository/repo/info/lfs/objects/batch");
    RepositoryProtocolHandler handler = new NeutralHandler(RepositoryProtocolRoute.anyPath(
        new FormatId("gitlfs"), RepositoryKind.HOSTED, "POST", 0)) {
      @Override
      public RepositoryResponse handle(RepositoryRequest request) {
        request.context().service(HostCounter.class).orElseThrow().calls.incrementAndGet();
        PluginRepositoryContext host = PluginRepositoryContext.of(request.context());
        assertSame(runtime, host.runtime());
        assertSame(servlet, host.servletRequest());
        return RepositoryResponse.status(202);
      }
    };
    PluginProtocolDispatcher dispatcher =
        new PluginProtocolDispatcher(List.of(handler), List.of(counter));

    ResponseEntity<?> response = dispatcher.handle(new RepositoryProtocolRequest(
        runtime, "repo", "info/lfs/objects/batch", HttpMethod.POST, servlet, null, null));

    assertEquals(202, response.getStatusCode().value());
    assertNull(response.getBody());
    assertEquals(1, counter.calls.get());
    assertThrows(IllegalStateException.class,
        () -> PluginRepositoryContext.of(RepositoryContext.empty(descriptor())));
  }

  @Test
  void streamsTheRequestBodyWithoutBufferingWhenTheHandlerAsksForIt() throws Exception {
    MockHttpServletRequest servlet =
        new MockHttpServletRequest("PUT", "/repository/repo/big/upload");
    servlet.setContent("large-body".getBytes(StandardCharsets.UTF_8));
    servlet.setQueryString("q=a+b&flag&q=ignored");
    RepositoryProtocolHandler handler = new NeutralHandler(RepositoryProtocolRoute.anyPath(
        new FormatId("raw"), RepositoryKind.HOSTED, "PUT", 999)) {
      @Override
      public boolean streamsRequestBody() {
        return true;
      }

      @Override
      public RepositoryResponse handle(RepositoryRequest request) throws java.io.IOException {
        assertArrayEquals(new byte[0], request.body());
        assertTrue(request.isBodyStreamed());
        assertSame(servlet.getInputStream(), request.bodyStream());
        assertEquals("large-body", new String(request.openBody().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(Map.of("q", "a b", "flag", ""), request.queryParameters());
        return RepositoryResponse.noContent();
      }
    };
    PluginProtocolDispatcher dispatcher = new PluginProtocolDispatcher(List.of(handler));

    ResponseEntity<?> response = dispatcher.handle(new RepositoryProtocolRequest(
        runtime(), "repo", "big/upload", HttpMethod.PUT, servlet, null, null));

    assertEquals(204, response.getStatusCode().value());
  }

  @Test
  void streamedResponsesAreWrittenByTheHostAndHeadResponsesHaveNoBody() throws Exception {
    RepositoryProtocolHandler handler = new NeutralHandler(RepositoryProtocolRoute.anyPath(
        new FormatId("raw"), RepositoryKind.HOSTED, "GET", 0)) {
      @Override
      public Collection<RepositoryProtocolRoute> routes() {
        return List.of(
            RepositoryProtocolRoute.anyPath(new FormatId("raw"), RepositoryKind.HOSTED, "GET", 0),
            RepositoryProtocolRoute.anyPath(new FormatId("raw"), RepositoryKind.HOSTED, "HEAD", 0),
            RepositoryProtocolRoute.anyPath(new FormatId("raw"), RepositoryKind.HOSTED, "POST", 0));
      }

      @Override
      public RepositoryResponse handle(RepositoryRequest request) {
        if (request.method().equals("POST")) {
          return new RepositoryResponse(200, Map.of(), "posted".getBytes(StandardCharsets.UTF_8));
        }
        return RepositoryResponse.streaming(200, Map.of("Content-Length", List.of("8")),
            output -> output.write("streamed".getBytes(StandardCharsets.UTF_8)));
      }
    };
    PluginProtocolDispatcher dispatcher = new PluginProtocolDispatcher(List.of(handler));

    ResponseEntity<?> get = dispatcher.handle(request(HttpMethod.GET));
    assertInstanceOf(StreamingResponseBody.class, get.getBody());
    assertEquals("streamed", written(get));
    ResponseEntity<?> head = dispatcher.handle(request(HttpMethod.HEAD));
    assertNull(head.getBody());
    assertEquals(8, head.getHeaders().getContentLength());
    ResponseEntity<?> post = dispatcher.handle(request(HttpMethod.POST));
    assertArrayEquals("posted".getBytes(StandardCharsets.UTF_8), (byte[]) post.getBody());
  }

  private static RepositoryProtocolRequest request(HttpMethod method) {
    return new RepositoryProtocolRequest(runtime(), "repo", "a", method,
        new MockHttpServletRequest(method.name(), "/repository/repo/a"), null, null);
  }

  private static String written(ResponseEntity<?> response) throws java.io.IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ((StreamingResponseBody) response.getBody()).writeTo(output);
    return output.toString(StandardCharsets.UTF_8);
  }

  private static RepositoryDescriptor descriptor() {
    return new RepositoryDescriptor(1L, "repo", new FormatId("raw"), RepositoryKind.HOSTED, "raw-hosted",
        true, 1L, null, null, null, null, Map.of());
  }

  private static final class HostCounter implements PluginHostService {
    private final AtomicInteger calls = new AtomicInteger();
  }

  private static RepositoryRuntime runtime() {
    return runtime(RepositoryFormat.RAW, RepositoryType.HOSTED);
  }

  private static RepositoryRuntime runtime(RepositoryFormat format, RepositoryType type) {
    return new RepositoryRuntime(
        1L,
        "repo",
        format,
        type,
        format.name().toLowerCase() + "-" + type.name().toLowerCase(),
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

  private static class NeutralHandler implements RepositoryProtocolHandler {
    private final RepositoryProtocolRoute route;

    NeutralHandler(RepositoryProtocolRoute route) {
      this.route = route;
    }

    @Override
    public Collection<RepositoryProtocolRoute> routes() {
      return List.of(route);
    }

    @Override
    public RepositoryResponse handle(RepositoryRequest request) throws java.io.IOException {
      return RepositoryResponse.noContent();
    }
  }
}
