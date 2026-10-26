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
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao;
import com.github.klboke.kkrepo.server.routingrules.RepositoryRoutingPolicy;
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
  void packageProtocolsMatchTheirCanonicalNamesWithoutLowercasingRawAssets() {
    record Case(RepositoryFormat format, List<String> matchers, List<String> aliases, String allowed) {}
    var cases = List.of(
        new Case(RepositoryFormat.SWIFT, List.of("/private/.*"), List.of(
            "PRIVATE/pkg/1.0.0.zip", "PRIVATE/pkg/1.0.0.json", "PriVaTe/PKG/1.0.0/Package.swift", "PR%49VATE/pkg.json"), "public/PKG/1.0.0.zip"),
        new Case(RepositoryFormat.PUB, List.of("/api/packages/private", "/api/packages/private/versions/1.0.0",
            "/api/archives/private-1.0.0.tar.gz", "/packages/private/versions/1.0.0.tar.gz", "/private/1.0.0/.*",
            "/api/package-names", "/api/package-name-completion-data"), List.of("api/packages/private/",
            "api/packages/private/versions/1.0.0/", "api/archives/private-1.0.0.tar.gz/",
            "packages/private/versions/1.0.0.tar.gz/", "private/1.0.0/private-1.0.0.tar.gz/",
            "private/1.0.0/version.json/", "api/package-names/", "api/package-name-completion-data/"), "api/packages/public"),
        new Case(RepositoryFormat.CARGO, List.of("/api/v1/crates/private/.*", "/crates/private/.*", "/pr/iv/private", "/config.json", "/api/v1/crates"),
            List.of("api/v1/crates/PRIVATE/1.0.0/download", "api/v1/crates/%20PRIVATE/1.0.0/download",
                "crates/PRIVATE/1.0.0/PRIVATE-1.0.0.crate", "crates/PRIVATE/1.0.0/download", "pr/iv/private/", "config.json/", "api/v1/crates/"), "api/v1/crates/public/1.0.0/download"),
        new Case(RepositoryFormat.NUGET, List.of("/v3-flatcontainer/private/.*", "/v3/registration5-semver[12]/private/.*"),
            List.of("v3-flatcontainer/PRIVATE/index.json", "v3/registration5-semver1/PRIVATE/index.json",
                "v3/registration5-semver2/PRIVATE/index.json"), "v3-flatcontainer/public/index.json"),
        new Case(RepositoryFormat.R, List.of("/src/contrib/private_.*\\.tar\\.gz", "/src/contrib/PACKAGES"),
            List.of("src/contrib/private_1.0.tar.gz/", "src/contrib/PACKAGES/"), "src/contrib/public_1.0.tar.gz"),
        new Case(RepositoryFormat.APT, List.of("/dists/stable/Release"), List.of("dists/stable/Release/"), "dists/public/Release"),
        new Case(RepositoryFormat.ALPINE, List.of("/v3.20/main/x86_64/APKINDEX.tar.gz"),
            List.of("v3.20/main/x86_64/APKINDEX.tar.gz/"), "v3.20/public/x86_64/APKINDEX.tar.gz"),
        new Case(RepositoryFormat.CONDA, List.of("/linux-64/repodata.json"), List.of("linux-64/repodata.json/"), "noarch/repodata.json"),
        new Case(RepositoryFormat.YUM, List.of("/private.rpm"), List.of("private.rpm/"), "public.rpm"),
        new Case(RepositoryFormat.RUBYGEMS, List.of("/gems/private-1.0.0.gem"), List.of("gems/private-1.0.0.gem/"), "gems/public-1.0.0.gem"),
        new Case(RepositoryFormat.ANSIBLEGALAXY, List.of("/api/v3/collections/private/pkg/versions"),
            List.of("api/v3/collections/private/pkg/versions/"), "api/v3/collections/public/pkg/versions/"),
        new Case(RepositoryFormat.HELM, List.of("/index\\.yaml", "/private.tgz"),
            List.of("INDEX.YAML", "iNdEx.YaMl"), "PRIVATE.tgz"),
        new Case(RepositoryFormat.RAW, List.of("/private/.*"), List.of("private/file"), "PRIVATE/file"));
    for (Case test : cases) {
      RepositoryRuntime proxy = runtime(test.format(), RepositoryType.PROXY);
      RepositoryRuntime group = runtime(test.format(), RepositoryType.GROUP).withMembers(List.of(proxy));
      var rules = mock(RoutingRuleDao.class);
      when(rules.bindings()).thenReturn(List.of(new RoutingRuleDao.Binding(1,
          new RoutingRuleDao.Rule(1, "private", "", "BLOCK", test.matchers()))));
      for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
        for (String alias : test.aliases()) {
          RecordingHandler handler = handler(RepositoryProtocolRoute.anyPath(test.format(), RepositoryType.PROXY,
              method, 0), ResponseEntity.ok().build());
          var dispatcher = dispatcher(proxy, handler);
          dispatcher.setRoutingPolicy(initializedPolicy(rules));
          assertThrows(RepositoryRoutingPolicy.Blocked.class,
              () -> dispatcher.dispatchRead("repo", request(method.name(), "/repository/repo/" + alias), method),
              test.format() + " " + alias);
          assertEquals(0, handler.invocations);
          assertEquals(200, dispatcher.dispatchRead("repo",
              request(method.name(), "/repository/repo/" + test.allowed()), method).getStatusCode().value());
          assertEquals(200, dispatcher.dispatchRead("repo",
              request(method.name(), "/repository/repo/unknown-path"), method).getStatusCode().value());
          RecordingHandler grouped = handler(RepositoryProtocolRoute.anyPath(test.format(), RepositoryType.GROUP,
              method, 0), ResponseEntity.ok().build());
          var groupDispatcher = dispatcher(group, grouped);
          groupDispatcher.setRoutingPolicy(initializedPolicy(rules));
          groupDispatcher.dispatchRead("repo", request(method.name(), "/repository/repo/" + alias), method);
          assertTrue(grouped.lastRequest.runtime().members().isEmpty(), test.format() + " " + alias);
        }
      }
    }
  }

  @Test
  void terraformRoutingUsesTheCredentialFreePathFromAuthentication() {
    RepositoryRuntime proxy = runtime(RepositoryFormat.TERRAFORM, RepositoryType.PROXY);
    RepositoryRuntime group = runtime(RepositoryFormat.TERRAFORM, RepositoryType.GROUP).withMembers(List.of(proxy));
    var rules = mock(RoutingRuleDao.class);
    when(rules.bindings()).thenReturn(List.of(new RoutingRuleDao.Binding(1,
        new RoutingRuleDao.Rule(1, "private", "", "BLOCK", List.of("/v1/modules/private/.*")))));
    for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
      for (RepositoryRuntime target : List.of(proxy, group)) {
        RecordingHandler handler = handler(RepositoryProtocolRoute.anyPath(
            RepositoryFormat.TERRAFORM, target.type(), method, 0), ResponseEntity.ok().build());
        var dispatcher = dispatcher(target, handler);
        dispatcher.setRoutingPolicy(initializedPolicy(rules));
        var request = request(method.name(), "/repository/repo/v1/modules/url-token/private/m/aws/versions");
        request.setAttribute(RepositorySecurityFilter.NORMALIZED_REPOSITORY_PATH_ATTRIBUTE,
            "v1/modules/private/m/aws/versions");
        if (target.isProxy()) {
          assertThrows(RepositoryRoutingPolicy.Blocked.class,
              () -> dispatcher.dispatchRead("repo", request, method));
          assertEquals(0, handler.invocations);
        } else {
          dispatcher.dispatchRead("repo", request, method);
          assertTrue(handler.lastRequest.runtime().members().isEmpty());
        }
      }
    }
  }

  @Test
  void npmRulesUseThePackageIdentityConsumedByHandlers() {
    RepositoryRuntime proxy = runtime(RepositoryFormat.NPM, RepositoryType.PROXY);
    RepositoryRuntime group = runtime(RepositoryFormat.NPM, RepositoryType.GROUP).withMembers(List.of(proxy));
    var rules = mock(RoutingRuleDao.class);
    when(rules.bindings()).thenReturn(List.of(new RoutingRuleDao.Binding(1,
        new RoutingRuleDao.Rule(1, "private", "", "BLOCK", List.of("/private(?:/.*)?",
            "/@scope/private(?:/.*)?", "/-/package/private/dist-tags(?:/.*)?")))));
    for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
      for (String path : List.of("private", "%20private", "private%20", "private/",
          "@scope%2Fprivate", "@scope/private%20", "%20private/1.0.0",
          "private%20/-/private-1.0.0.tgz", "private%20/-rev/1",
          "-/package/%20private/dist-tags", "-/package/private%20/dist-tags/latest")) {
        RecordingHandler denied = handler(RepositoryProtocolRoute.anyPath(
            RepositoryFormat.NPM, RepositoryType.PROXY, method, 0), ResponseEntity.ok().build());
        var direct = dispatcher(proxy, denied);
        direct.setRoutingPolicy(initializedPolicy(rules));
        assertThrows(RepositoryRoutingPolicy.Blocked.class,
            () -> direct.dispatchRead("repo", request(method.name(), "/repository/repo/" + path), method), path);
        assertEquals(0, denied.invocations);
        assertEquals(200, direct.dispatchRead("repo",
            request(method.name(), "/repository/repo/public"), method).getStatusCode().value());
        assertEquals(200, direct.dispatchRead("repo",
            request(method.name(), "/repository/repo/%2520private"), method).getStatusCode().value());
        assertEquals(200, direct.dispatchRead("repo",
            request(method.name(), "/repository/repo/-/ping"), method).getStatusCode().value());
        RecordingHandler filtered = handler(RepositoryProtocolRoute.anyPath(
            RepositoryFormat.NPM, RepositoryType.GROUP, method, 0), ResponseEntity.ok().build());
        var grouped = dispatcher(group, filtered);
        grouped.setRoutingPolicy(initializedPolicy(rules));
        grouped.dispatchRead("repo", request(method.name(), "/repository/repo/" + path), method);
        assertTrue(filtered.lastRequest.runtime().members().isEmpty(), path);
      }
    }
  }

  @Test
  void routingRulesPreserveSemicolonsConsumedByRawHandlers() {
    RepositoryRuntime runtime = runtime(RepositoryFormat.RAW, RepositoryType.PROXY);
    var rules = mock(RoutingRuleDao.class);
    when(rules.bindings()).thenReturn(List.of(
        new RoutingRuleDao.Binding(1,
            new RoutingRuleDao.Rule(
                1, "public", "", "ALLOW", List.of("/public/.*")))));
    for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
      RecordingHandler raw = handler(RepositoryProtocolRoute.anyPath(
          RepositoryFormat.RAW, RepositoryType.PROXY, method, 0), ResponseEntity.ok().build());
      var dispatcher = dispatcher(runtime, raw);
      dispatcher.setRoutingPolicy(initializedPolicy(rules));
      assertThrows(RepositoryRoutingPolicy.Blocked.class,
          () -> dispatcher.dispatchRead("repo",
              request(method.name(), "/repository/repo/public;private/a"), method));
      assertEquals(0, raw.invocations);
      assertEquals(200, dispatcher.dispatchRead("repo",
          request(method.name(), "/repository/repo/public/a;b"), method).getStatusCode().value());
      assertEquals("public/a;b", raw.lastRequest.path());
    }
  }

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
  private static RepositoryRoutingPolicy initializedPolicy(RoutingRuleDao rules) {
    var policy = new RepositoryRoutingPolicy(rules);
    policy.initialize();
    return policy;
  }
}
