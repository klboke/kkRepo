package com.github.klboke.kkrepo.server.routing.builtin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.plugin.builtin.BuiltinProtocolPlugin;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.routing.LegacyRepositoryProtocolHandler;
import com.github.klboke.kkrepo.server.routing.PluginProtocolDispatcher;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolDispatcher;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRoute;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class BuiltinProtocolPluginRoutingTest {

  @Test
  void pluginCatchAllWinsOverTheBuiltinFallbackAndKeepsStreaming() throws Exception {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("repo")).thenReturn(Optional.of(runtime()));
    BuiltinRepositoryProtocolHandler host = mock(BuiltinRepositoryProtocolHandler.class);
    doReturn(ResponseEntity.status(206)
        .header("Content-Range", "bytes 0-11/12")
        .body((StreamingResponseBody) output ->
            output.write("plugin-stream".getBytes(StandardCharsets.UTF_8))))
        .when(host).handle(any());
    ServerBuiltinProtocolOperations hostOperations =
        new ServerBuiltinProtocolOperations(host, runtimes, new ObjectMapper());
    PluginProtocolDispatcher plugins = new PluginProtocolDispatcher(
        List.of(new BuiltinProtocolPlugin()), List.of(hostOperations));
    AtomicInteger fallbackCalls = new AtomicInteger();
    LegacyRepositoryProtocolHandler fallback = new BuiltinFallback(fallbackCalls);
    RepositoryProtocolDispatcher dispatcher =
        new RepositoryProtocolDispatcher(runtimes, List.of(plugins, fallback));
    MockHttpServletRequest servlet =
        new MockHttpServletRequest("GET", "/repository/repo/path/artifact.bin");

    ResponseEntity<?> response = dispatcher.dispatchRead("repo", servlet, HttpMethod.GET);

    assertEquals(206, response.getStatusCode().value());
    assertEquals(List.of("bytes 0-11/12"), response.getHeaders().get("Content-Range"));
    StreamingResponseBody body = (StreamingResponseBody) response.getBody();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    body.writeTo(output);
    assertArrayEquals(
        "plugin-stream".getBytes(StandardCharsets.UTF_8), output.toByteArray());
    assertEquals(0, fallbackCalls.get(),
        "The built-in fallback must not run when the plugin catch-all is loaded");
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
        "ALLOW",
        "RELEASE",
        "STRICT",
        true,
        null,
        null,
        null,
        null,
        null,
        List.of());
  }

  /** Mirrors the built-in catch-all route contract, one order below the plugin. */
  private static final class BuiltinFallback implements LegacyRepositoryProtocolHandler {
    private static final List<RepositoryProtocolRoute> ROUTES = buildRoutes();
    private final AtomicInteger calls;

    private BuiltinFallback(AtomicInteger calls) {
      this.calls = calls;
    }

    @Override
    public Collection<RepositoryProtocolRoute> routes() {
      return ROUTES;
    }

    @Override
    public ResponseEntity<?> handle(RepositoryProtocolRequest request) {
      calls.incrementAndGet();
      return ResponseEntity.ok("fallback");
    }

    private static List<RepositoryProtocolRoute> buildRoutes() {
      List<RepositoryProtocolRoute> routes = new ArrayList<>();
      for (RepositoryFormat format : RepositoryFormat.values()) {
        for (RepositoryType type : RepositoryType.values()) {
          for (HttpMethod method : List.of(
              HttpMethod.GET,
              HttpMethod.HEAD,
              HttpMethod.PUT,
              HttpMethod.DELETE,
              HttpMethod.POST)) {
            routes.add(RepositoryProtocolRoute.anyPath(format, type, method, 1_000));
          }
        }
      }
      return List.copyOf(routes);
    }
  }
}
