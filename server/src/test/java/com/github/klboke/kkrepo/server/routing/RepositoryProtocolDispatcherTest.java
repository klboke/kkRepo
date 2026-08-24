package com.github.klboke.kkrepo.server.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class RepositoryProtocolDispatcherTest {
  @Test
  void exactPathWinsOverCatchAllForTheSameProtocol() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryFormat.HELM, RepositoryType.HOSTED);
    RecordingHandler fallback = handler(
        RepositoryProtocolRoute.anyPath(
            RepositoryFormat.HELM, RepositoryType.HOSTED, HttpMethod.POST, 1_000),
        ResponseEntity.status(202).build());
    RecordingHandler exact = handler(
        RepositoryProtocolRoute.exactPath(
            RepositoryFormat.HELM,
            RepositoryType.HOSTED,
            HttpMethod.POST,
            "api/charts",
            0),
        ResponseEntity.status(201).build());

    ResponseEntity<?> response = dispatcher(runtime, fallback, exact).dispatch(
        runtime.name(), request("POST", "/repository/repo/api/charts"),
        HttpMethod.POST, null);

    assertEquals(201, response.getStatusCode().value());
    assertEquals(0, fallback.invocations);
    assertEquals(1, exact.invocations);
    assertEquals("api/charts", exact.lastRequest.path());
    assertSame(runtime, exact.lastRequest.runtime());
  }

  @Test
  void formatTypeAndMethodArePartOfRouteIdentity() {
    RepositoryRuntime runtime = runtime(RepositoryFormat.NPM, RepositoryType.PROXY);
    RecordingHandler wrongType = handler(
        RepositoryProtocolRoute.anyPath(
            RepositoryFormat.NPM, RepositoryType.HOSTED, HttpMethod.GET, 0),
        ResponseEntity.ok().build());
    RecordingHandler wrongMethod = handler(
        RepositoryProtocolRoute.anyPath(
            RepositoryFormat.NPM, RepositoryType.PROXY, HttpMethod.PUT, 0),
        ResponseEntity.ok().build());

    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> dispatcher(
        runtime, wrongType, wrongMethod).dispatchRead(
            runtime.name(), request("GET", "/repository/repo/pkg"), HttpMethod.GET));
  }

  @Test
  void emptyExactPathCanOwnTheRepositoryRoot() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryFormat.PYPI, RepositoryType.HOSTED);
    RecordingHandler root = handler(
        RepositoryProtocolRoute.exactPath(
            RepositoryFormat.PYPI, RepositoryType.HOSTED, HttpMethod.POST, "", 0),
        ResponseEntity.status(204).build());

    ResponseEntity<?> response = dispatcher(runtime, root).dispatch(
        runtime.name(), request("POST", "/repository/repo"), HttpMethod.POST, null);

    assertEquals(204, response.getStatusCode().value());
    assertEquals("", root.lastRequest.path());
  }

  @Test
  void duplicateRegistrationsAcrossHandlersAreRejectedAtStartup() {
    RepositoryRuntime runtime = runtime(RepositoryFormat.RAW, RepositoryType.HOSTED);
    RecordingHandler first = handler(
        RepositoryProtocolRoute.exactPath(
            RepositoryFormat.RAW, RepositoryType.HOSTED, HttpMethod.GET, "asset", 10),
        ResponseEntity.ok().build());
    RecordingHandler second = handler(
        RepositoryProtocolRoute.exactPath(
            RepositoryFormat.RAW, RepositoryType.HOSTED, HttpMethod.GET, "asset", 10),
        ResponseEntity.ok().build());

    IllegalStateException error = assertThrows(
        IllegalStateException.class, () -> dispatcher(runtime, first, second));

    assertTrue(error.getMessage().startsWith(
        "Ambiguous repository protocol route registration"));
  }

  @Test
  void duplicateRegistrationInsideOneHandlerIsRejectedAtStartup() {
    RepositoryRuntime runtime = runtime(RepositoryFormat.RAW, RepositoryType.HOSTED);
    RepositoryProtocolRoute route = RepositoryProtocolRoute.exactPath(
        RepositoryFormat.RAW, RepositoryType.HOSTED, HttpMethod.GET, "asset", 10);
    RecordingHandler duplicate = new RecordingHandler(
        List.of(route, route), ResponseEntity.ok().build());

    assertThrows(IllegalStateException.class, () -> dispatcher(runtime, duplicate));
  }

  @Test
  void longerPrefixWinsBeforeRouteOrder() throws Exception {
    RepositoryRuntime runtime = runtime(RepositoryFormat.RAW, RepositoryType.HOSTED);
    RecordingHandler broad = handler(
        new RepositoryProtocolRoute(
            RepositoryFormat.RAW, RepositoryType.HOSTED, HttpMethod.GET, "api/**", 0),
        ResponseEntity.status(202).build());
    RecordingHandler narrow = handler(
        new RepositoryProtocolRoute(
            RepositoryFormat.RAW,
            RepositoryType.HOSTED,
            HttpMethod.GET,
            "api/packages/**",
            100),
        ResponseEntity.status(203).build());

    ResponseEntity<?> response = dispatcher(runtime, broad, narrow).dispatchRead(
        runtime.name(), request("GET", "/repository/repo/api/packages/demo"), HttpMethod.GET);

    assertEquals(203, response.getStatusCode().value());
    assertEquals(0, broad.invocations);
    assertEquals(1, narrow.invocations);
  }

  @Test
  void handlerMustRegisterAtLeastOneRoute() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryProtocolHandler empty = new RecordingHandler(List.of(), ResponseEntity.ok().build());

    assertThrows(
        IllegalStateException.class,
        () -> new RepositoryProtocolDispatcher(runtimes, List.of(empty)));
  }

  @Test
  void dispatcherMustHaveAtLeastOneHandler() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);

    IllegalStateException error = assertThrows(
        IllegalStateException.class,
        () -> new RepositoryProtocolDispatcher(runtimes, List.of()));

    assertEquals("No repository protocol routes are registered", error.getMessage());
  }

  @Test
  void nullHandlerRegistrationIsRejectedInsteadOfSilentlySkipped() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    List<RepositoryProtocolHandler> handlers = new ArrayList<>();
    handlers.add(handler(
        RepositoryProtocolRoute.anyPath(
            RepositoryFormat.RAW, RepositoryType.HOSTED, HttpMethod.GET, 0),
        ResponseEntity.ok().build()));
    handlers.add(null);

    IllegalStateException error = assertThrows(
        IllegalStateException.class,
        () -> new RepositoryProtocolDispatcher(runtimes, handlers));

    assertEquals("Repository protocol handler list contains null", error.getMessage());
  }

  @Test
  void mismatchedSecuritySnapshotFailsClosedWithoutResolvingAnotherRepository() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryRecord record = mock(RepositoryRecord.class);
    when(record.name()).thenReturn("authorized-repository");
    MockHttpServletRequest request = request("GET", "/repository/repo/asset");
    request.setAttribute(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE, record);
    RepositoryProtocolDispatcher dispatcher = new RepositoryProtocolDispatcher(
        runtimes,
        List.of(handler(
            RepositoryProtocolRoute.anyPath(
                RepositoryFormat.RAW, RepositoryType.HOSTED, HttpMethod.GET, 0),
            ResponseEntity.ok().build())));

    assertThrows(
        MavenExceptions.MavenNotFoundException.class,
        () -> dispatcher.dispatchRead("repo", request, HttpMethod.GET));
    verifyNoInteractions(runtimes);
  }

  @Test
  void readDispatchWrapsCheckedHandlerFailures() {
    RepositoryRuntime runtime = runtime(RepositoryFormat.RAW, RepositoryType.HOSTED);
    RepositoryProtocolHandler failing = new RepositoryProtocolHandler() {
      @Override
      public Collection<RepositoryProtocolRoute> routes() {
        return List.of(RepositoryProtocolRoute.anyPath(
            RepositoryFormat.RAW, RepositoryType.HOSTED, HttpMethod.GET, 0));
      }

      @Override
      public ResponseEntity<?> handle(RepositoryProtocolRequest request) throws java.io.IOException {
        throw new java.io.IOException("read failed");
      }
    };

    IllegalStateException error = assertThrows(
        IllegalStateException.class,
        () -> dispatcher(runtime, failing).dispatchRead(
            runtime.name(), request("GET", "/repository/repo/asset"), HttpMethod.GET));

    assertEquals("Repository read handler failed", error.getMessage());
    assertTrue(error.getCause() instanceof java.io.IOException);
  }

  @Test
  void wildcardIsOnlyAllowedAsCatchAllOrTrailingPrefix() {
    assertThrows(IllegalArgumentException.class, () -> new RepositoryProtocolRoute(
        RepositoryFormat.RAW,
        RepositoryType.HOSTED,
        HttpMethod.GET,
        "api/**/assets",
        0));
    assertThrows(IllegalArgumentException.class, () -> new RepositoryProtocolRoute(
        RepositoryFormat.RAW,
        RepositoryType.HOSTED,
        HttpMethod.GET,
        "api/*",
        0));
    assertThrows(IllegalArgumentException.class, () -> new RepositoryProtocolRoute(
        RepositoryFormat.RAW,
        RepositoryType.HOSTED,
        HttpMethod.GET,
        "api/**/**",
        0));
  }

  private static RepositoryProtocolDispatcher dispatcher(
      RepositoryRuntime runtime, RepositoryProtocolHandler... handlers) {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    when(runtimes.resolve(runtime.name())).thenReturn(Optional.of(runtime));
    return new RepositoryProtocolDispatcher(runtimes, List.of(handlers));
  }

  private static RecordingHandler handler(
      RepositoryProtocolRoute route, ResponseEntity<?> response) {
    return new RecordingHandler(List.of(route), response);
  }

  private static MockHttpServletRequest request(String method, String uri) {
    return new MockHttpServletRequest(method, uri);
  }

  private static RepositoryRuntime runtime(
      RepositoryFormat format, RepositoryType type) {
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
    private final Collection<RepositoryProtocolRoute> routes;
    private final ResponseEntity<?> response;
    private int invocations;
    private RepositoryProtocolRequest lastRequest;

    private RecordingHandler(
        Collection<RepositoryProtocolRoute> routes, ResponseEntity<?> response) {
      this.routes = routes;
      this.response = response;
    }

    @Override
    public Collection<RepositoryProtocolRoute> routes() {
      return routes;
    }

    @Override
    public ResponseEntity<?> handle(RepositoryProtocolRequest request) {
      invocations++;
      lastRequest = request;
      return response;
    }
  }
}
