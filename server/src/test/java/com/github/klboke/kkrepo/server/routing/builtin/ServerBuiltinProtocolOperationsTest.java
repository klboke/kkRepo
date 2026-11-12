package com.github.klboke.kkrepo.server.routing.builtin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryPolicy;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class ServerBuiltinProtocolOperationsTest {

  @Test
  void convertsAByteArrayResponse() throws Exception {
    BuiltinRepositoryProtocolHandler handler = mock(BuiltinRepositoryProtocolHandler.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("repo")).thenReturn(Optional.of(runtime()));
    doReturn(ResponseEntity.status(201).header("X-Builtin", "yes")
        .body(new byte[] {1, 2, 3}))
        .when(handler).handle(any());
    ServerBuiltinProtocolOperations operations =
        new ServerBuiltinProtocolOperations(handler, runtimes, new ObjectMapper());

    RepositoryResponse response = operations.handle(request(context(descriptor())));

    assertEquals(201, response.status());
    assertEquals(List.of("yes"), response.headers().get("X-Builtin"));
    assertArrayEquals(new byte[] {1, 2, 3}, response.body());
  }

  @Test
  void keepsAStreamingResponseStreamed() throws Exception {
    BuiltinRepositoryProtocolHandler handler = mock(BuiltinRepositoryProtocolHandler.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("repo")).thenReturn(Optional.of(runtime()));
    StreamingResponseBody body = output -> output.write("artifact".getBytes(StandardCharsets.UTF_8));
    doReturn(ResponseEntity.status(206).header("Content-Range", "bytes 0-7/8").body(body))
        .when(handler).handle(any());
    ServerBuiltinProtocolOperations operations =
        new ServerBuiltinProtocolOperations(handler, runtimes, new ObjectMapper());

    RepositoryResponse response = operations.handle(request(context(descriptor())));

    assertTrue(response.isStreaming());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.stream().writeTo(output);
    assertArrayEquals("artifact".getBytes(StandardCharsets.UTF_8), output.toByteArray());
    assertEquals(List.of("bytes 0-7/8"), response.headers().get("Content-Range"));
  }

  @Test
  void serializesJsonBodies() throws Exception {
    BuiltinRepositoryProtocolHandler handler = mock(BuiltinRepositoryProtocolHandler.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("repo")).thenReturn(Optional.of(runtime()));
    doReturn(ResponseEntity.ok(Map.of("ok", true))).when(handler).handle(any());
    ServerBuiltinProtocolOperations operations =
        new ServerBuiltinProtocolOperations(handler, runtimes, new ObjectMapper());

    RepositoryResponse response = operations.handle(request(context(descriptor())));

    assertEquals(true, new ObjectMapper().readTree(response.body()).get("ok").asBoolean());
  }

  @Test
  void rejectsAnUnknownRepository() {
    BuiltinRepositoryProtocolHandler handler = mock(BuiltinRepositoryProtocolHandler.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve("repo")).thenReturn(Optional.empty());
    ServerBuiltinProtocolOperations operations =
        new ServerBuiltinProtocolOperations(handler, runtimes, new ObjectMapper());

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> operations.handle(request(context(descriptor()))));
  }

  @Test
  void rejectsARequestWithoutTheHostServlet() {
    ServerBuiltinProtocolOperations operations = new ServerBuiltinProtocolOperations(
        mock(BuiltinRepositoryProtocolHandler.class), mock(RepositoryRuntimeRegistry.class),
        new ObjectMapper());
    RepositoryDescriptor descriptor = descriptor();

    assertThrows(IllegalStateException.class, () -> operations.handle(new RepositoryRequest(
        descriptor, "repo", "a/b", "GET", Map.of(), Map.of(), new byte[0], null,
        RepositoryContext.empty(descriptor), Map.of())));
  }

  private static RepositoryRequest request(RepositoryContext context) {
    return new RepositoryRequest(
        context.repository(), "repo", "a/b", "GET", Map.of(), Map.of(),
        new byte[0], null, context, Map.of());
  }

  private static RepositoryContext context(RepositoryDescriptor descriptor) {
    MockHttpServletRequest servlet =
        new MockHttpServletRequest("GET", "/repository/repo/a/b");
    return new RepositoryContext() {
      @Override
      public RepositoryDescriptor repository() {
        return descriptor;
      }

      @Override
      public Optional<BlobStorage> blobs() {
        return Optional.empty();
      }

      @Override
      public <T> Optional<T> service(Class<T> type) {
        return Optional.empty();
      }

      @Override
      public RepositoryPolicy policy() {
        return new RepositoryPolicy("ALLOW", "RELEASE", "STRICT");
      }

      @Override
      public Object hostContext() {
        return servlet;
      }
    };
  }

  private static RepositoryDescriptor descriptor() {
    return new RepositoryDescriptor(
        1L, "repo", new FormatId("raw"), RepositoryKind.HOSTED, "raw-hosted",
        true, 1L, "ALLOW", "RELEASE", "STRICT", null, Map.of());
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "repo", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted",
        true, 1L, "ALLOW", "RELEASE", "STRICT", true, null, null, null, null, null, List.of());
  }
}
